// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.migrate1x.database;

import java.sql.DatabaseMetaData;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.veridger.database.repository.SchemaHistoryRepository;
import org.veridger.migrate1x.database.LegacyObject.Kind;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * What {@code retire-legacy} asks of the 1.x database: the list of everything the old installer made in it, and the removal.
 *
 * <p><b>This is the one class of the product that is written for one DBMS, and the 1.x source is why.</b> The 1.x ledger ran
 * on PostgreSQL only (its installer made PL/pgSQL functions, triggers and roles there), so the objects to remove are
 * PostgreSQL objects and their {@code DROP} statements are PostgreSQL's. The ledger of 2.0 stays ANSI and never reaches this class:
 * it works on the data source of the legacy ledger ({@link LegacySourceConfiguration}), through its {@link JdbcClient} and
 * its transaction (principle 4.4: the service {@code LegacyRetireService} holds the transaction, this class joins it; PostgreSQL
 * DDL is transactional, so a failing statement leaves everything in place).
 *
 * <p>The list comes from the {@code information_schema} wherever it has the data (triggers with their table, tables, views,
 * sequences). {@code pg_catalog} is read only where the standard views cannot answer: the identity arguments of a function
 * ({@code DROP FUNCTION name(arguments)}; the standard views do not give them, and an overload of the same name is another
 * object), who owns an object, the roles and who is connected. Identifiers are quoted with the quote string of the driver
 * ({@link DatabaseMetaData#getIdentifierQuoteString}); a value is a bind parameter, only an identifier is part of a
 * statement.
 *
 * <p>The order of the drop is {@link Kind}: triggers, views, tables, functions and procedures, sequences, the schema, the
 * roles. The functions come after the tables on purpose: a table of 1.x has CHECK constraints that call a function
 * ({@code ledger._author_ok}, {@code sql/10_tables.sql} of 1.x), so the function cannot go first. All tables go in one
 * statement, because they refer to each other by foreign keys. Nothing is dropped with {@code CASCADE}: an object that
 * this class did not list makes {@code DROP SCHEMA} fail, and the whole run rolls back. A component that only
 * {@link LegacySourceWiring} makes, like {@link LegacySourceRepository}.
 */
@Component
public final class LegacySchemaRepository {

    private static final Pattern SCHEMA_PATTERN = Pattern.compile("[a-z][a-z0-9_]{0,29}");
    private static final Pattern ROLE = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,62}");

    private static final String SCHEMA_PARAMETER = "schema";
    private static final String ROLES_PARAMETER = "roles";
    private static final String ROLE_PARAMETER = "role";

    private static final String SCHEMA_EXISTS = "SELECT COUNT(*) FROM information_schema.schemata "
            + "WHERE schema_name = :schema";
    private static final String TRIGGERS = "SELECT DISTINCT trigger_name, event_object_table "
            + "FROM information_schema.triggers WHERE trigger_schema = :schema";
    private static final String TABLES_AND_VIEWS = "SELECT table_name, table_type FROM information_schema.tables "
            + "WHERE table_schema = :schema AND table_type IN ('BASE TABLE', 'VIEW')";
    private static final String SEQUENCES = "SELECT sequence_name FROM information_schema.sequences "
            + "WHERE sequence_schema = :schema";
    private static final String HOLDS_TABLE = "SELECT COUNT(*) FROM information_schema.tables "
            + "WHERE table_schema = :schema AND table_name = :table";
    private static final String CATALOG = "SELECT catalog_name FROM information_schema.schemata "
            + "WHERE schema_name = :schema";

    // pg_catalog from here on: what the standard views do not answer (see the class comment)
    private static final String ROUTINES = "SELECT p.proname, p.prokind, "
            + "pg_catalog.pg_get_function_identity_arguments(p.oid) AS identity_arguments "
            + "FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace "
            + "WHERE n.nspname = :schema AND p.prokind IN ('f', 'p')";
    private static final String NOT_OWNED_RELATIONS = "SELECT c.relname FROM pg_catalog.pg_class c "
            + "JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = :schema "
            + "AND NOT pg_catalog.pg_has_role(CURRENT_USER, c.relowner, 'USAGE')";
    private static final String NOT_OWNED_ROUTINES = "SELECT p.proname FROM pg_catalog.pg_proc p "
            + "JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = :schema "
            + "AND NOT pg_catalog.pg_has_role(CURRENT_USER, p.proowner, 'USAGE')";
    private static final String NOT_OWNED_SCHEMA = "SELECT n.nspname FROM pg_catalog.pg_namespace n "
            + "WHERE n.nspname = :schema AND NOT pg_catalog.pg_has_role(CURRENT_USER, n.nspowner, 'USAGE')";
    private static final String SESSIONS_AS_ROLE = "SELECT a.pid, r.rolname FROM pg_catalog.pg_stat_activity a "
            + "JOIN pg_catalog.pg_roles r ON r.oid = a.usesysid WHERE a.datname = current_database() "
            + "AND a.pid <> pg_backend_pid() AND r.rolname IN (:roles) AND r.rolname <> CURRENT_USER";
    private static final String SESSIONS_WITH_LOCKS = "SELECT DISTINCT l.pid FROM pg_catalog.pg_locks l "
            + "JOIN pg_catalog.pg_class c ON c.oid = l.relation "
            + "JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace "
            + "JOIN pg_catalog.pg_database d ON d.oid = l.database "
            + "WHERE n.nspname = :schema AND d.datname = current_database() AND l.pid <> pg_backend_pid()";
    private static final String EXISTING_ROLES = "SELECT rolname FROM pg_catalog.pg_roles WHERE rolname IN (:roles)";
    private static final String MAY_DROP_ROLES = "SELECT rolsuper, rolcreaterole FROM pg_catalog.pg_roles "
            + "WHERE rolname = CURRENT_USER";
    private static final String MEMBERS_OUTSIDE = "SELECT COUNT(*) FROM pg_catalog.pg_auth_members m "
            + "JOIN pg_catalog.pg_roles g ON g.oid = m.roleid JOIN pg_catalog.pg_roles u ON u.oid = m.member "
            + "WHERE g.rolname = :role AND u.rolname NOT IN (:roles)";
    private static final String OWNED_RELATIONS_ELSEWHERE = "SELECT COUNT(*) FROM pg_catalog.pg_class c "
            + "JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace "
            + "JOIN pg_catalog.pg_roles r ON r.oid = c.relowner WHERE r.rolname = :role AND n.nspname <> :schema "
            + "AND n.nspname NOT LIKE 'pg_toast%' AND n.nspname NOT LIKE 'pg_temp%'";
    private static final String OWNED_ROUTINES_ELSEWHERE = "SELECT COUNT(*) FROM pg_catalog.pg_proc p "
            + "JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace "
            + "JOIN pg_catalog.pg_roles r ON r.oid = p.proowner WHERE r.rolname = :role AND n.nspname <> :schema";
    private static final String OWNED_SCHEMAS_ELSEWHERE = "SELECT COUNT(*) FROM pg_catalog.pg_namespace n "
            + "JOIN pg_catalog.pg_roles r ON r.oid = n.nspowner WHERE r.rolname = :role AND n.nspname <> :schema";

    private final DataSource dataSource;
    private final JdbcClient jdbc;
    private final String schema;
    private final List<String> roles;

    /** The repository of the legacy database of the settings {@code ledger.import.source.*}, on the legacy beans. */
    @Autowired
    public LegacySchemaRepository(@Qualifier(LegacySourceRepository.JDBC_CLIENT) JdbcClient jdbc,
            @Qualifier(LegacySourceConfiguration.DATA_SOURCE) DataSource dataSource, LegacySourceProperties properties) {
        this(jdbc, dataSource, properties.schema(), properties.roles());
    }

    /**
     * @param jdbc       the client over {@code dataSource}
     * @param dataSource the connection of the account that removes the objects
     * @param schema     the legacy schema (lower case letters, digits and {@code _}, at most 30)
     * @param roles      the roles of the old installer
     * @throws IllegalArgumentException when the schema or a role name is not an identifier of the usual form
     */
    public LegacySchemaRepository(JdbcClient jdbc, DataSource dataSource, String schema, List<String> roles) {
        if (schema == null || !SCHEMA_PATTERN.matcher(schema).matches()) {
            throw new IllegalArgumentException("the legacy schema must be lower case letters, digits and _ (max 30): "
                    + schema);
        }
        for (String role : roles) {
            if (!ROLE.matcher(role).matches()) {
                throw new IllegalArgumentException("a role must be letters, digits and _ (max 63): " + role);
            }
        }
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.schema = schema;
        this.roles = List.copyOf(roles);
    }

    public String schema() {
        return schema;
    }

    /** Whether the legacy schema holds a table of this name (it is how the schema of 2.0 itself is recognised). */
    public boolean holdsTable(String table) {
        return count(HOLDS_TABLE, Map.of(SCHEMA_PARAMETER, schema, "table", table)) > 0;
    }

    /**
     * Everything that would be dropped, with what stands in the way. Reads only. The objects are in the order of the drop; two
     * calls on an unchanged database give the same list.
     *
     * @param protectedAccounts the owner account and the application account of the 2.0 settings: a role of that name stays
     */
    public LegacyInventory inventory(Set<String> protectedAccounts) {
        List<LegacyObject> objects = new ArrayList<>();
        boolean schemaExists = count(SCHEMA_EXISTS, Map.of(SCHEMA_PARAMETER, schema)) > 0;
        if (schemaExists) {
            objects.addAll(triggers());
            objects.addAll(tablesAndViews());
            objects.addAll(routines());
            objects.addAll(sequences());
            objects.add(new LegacyObject.Named(Kind.SCHEMA, schema));
        }
        List<LegacyRoleSkip> skipped = new ArrayList<>();
        for (String role : existingRoles()) {
            whyKept(role, protectedAccounts).ifPresentOrElse(reason -> skipped.add(new LegacyRoleSkip(role, reason)),
                    () -> objects.add(new LegacyObject.Named(Kind.ROLE, role)));
        }
        objects.sort(Comparator.comparing(LegacyObject::kind).thenComparing(LegacyObject::line));
        skipped.sort(Comparator.comparing(LegacyRoleSkip::line));
        return new LegacyInventory(objects, skipped, schemaExists ? notOwned() : List.of(), sessions());
    }

    /**
     * Drops the objects, one statement after the other in the order given (the order of {@link #inventory}); the tables go in
     * one statement. It joins the transaction of the caller, which decides whether the drops stay.
     */
    public void drop(List<LegacyObject> objects) {
        String quote = identifierQuote();
        List<String> tables = objects.stream().filter(LegacySchemaRepository::isTable)
                .map(table -> qualified(quote, ((LegacyObject.Named) table).name())).toList();
        for (LegacyObject object : objects) {
            if (isTable(object)) {
                if (!tables.isEmpty()) {
                    jdbc.sql(dropTablesSql(tables)).update();
                    tables = List.of();
                }
            } else {
                for (String statement : statementsOf(object, quote)) {
                    jdbc.sql(statement).update();
                }
            }
        }
    }

    private static String dropTablesSql(List<String> quotedTables) {
        return "DROP TABLE " + String.join(", ", quotedTables);
    }

    private static boolean isTable(LegacyObject object) {
        return object instanceof LegacyObject.Named named && named.kind() == Kind.TABLE;
    }

    /** The statements that drop one object that is not a table. */
    private List<String> statementsOf(LegacyObject object, String quote) {
        return switch (object) {
            case LegacyObject.Trigger trigger -> List.of("DROP TRIGGER " + quoted(quote, trigger.name()) + " ON "
                    + qualified(quote, trigger.table()));
            case LegacyObject.Routine routine -> List.of("DROP " + routine.kind() + " "
                    + qualified(quote, routine.name()) + "(" + routine.arguments() + ")");
            case LegacyObject.Named named -> statementsOfNamed(named, quote);
        };
    }

    private List<String> statementsOfNamed(LegacyObject.Named named, String quote) {
        return switch (named.kind()) {
            case VIEW -> List.of("DROP VIEW " + qualified(quote, named.name()));
            // IF EXISTS: the sequence of an identity column went with its table
            case SEQUENCE -> List.of("DROP SEQUENCE IF EXISTS " + qualified(quote, named.name()));
            case SCHEMA -> List.of("DROP SCHEMA " + quoted(quote, named.name()));
            case ROLE -> List.of("REVOKE ALL ON DATABASE " + quoted(quote, catalog()) + " FROM "
                    + quoted(quote, named.name()), "DROP ROLE " + quoted(quote, named.name()));
            case TRIGGER, TABLE, FUNCTION, PROCEDURE -> throw new IllegalArgumentException(
                    "not dropped by its name here: " + named.line());
        };
    }

    private String qualified(String quote, String name) {
        return quoted(quote, schema) + "." + quoted(quote, name);
    }

    private static String quoted(String quote, String name) {
        return quote.isEmpty() ? name : quote + name.replace(quote, quote + quote) + quote;
    }

    /** The quote string of the driver; empty when the driver does not quote. */
    private String identifierQuote() {
        String quote = SchemaHistoryRepository.inspect(dataSource, DatabaseMetaData::getIdentifierQuoteString);
        return quote.isBlank() ? "" : quote;
    }

    /** The database the legacy schema is in. */
    private String catalog() {
        return jdbc.sql(CATALOG).param(SCHEMA_PARAMETER, schema).query(String.class).single();
    }

    private List<LegacyObject> triggers() {
        return jdbc.sql(TRIGGERS).param(SCHEMA_PARAMETER, schema)
                .query((rs, row) -> (LegacyObject) new LegacyObject.Trigger(rs.getString("event_object_table"),
                        rs.getString("trigger_name")))
                .list();
    }

    private List<LegacyObject> tablesAndViews() {
        return jdbc.sql(TABLES_AND_VIEWS).param(SCHEMA_PARAMETER, schema)
                .query((rs, row) -> (LegacyObject) new LegacyObject.Named(
                        "VIEW".equals(rs.getString("table_type")) ? Kind.VIEW : Kind.TABLE, rs.getString("table_name")))
                .list();
    }

    private List<LegacyObject> routines() {
        return jdbc.sql(ROUTINES).param(SCHEMA_PARAMETER, schema)
                .query((rs, row) -> (LegacyObject) new LegacyObject.Routine(
                        "p".equals(rs.getString("prokind")) ? Kind.PROCEDURE : Kind.FUNCTION, rs.getString("proname"),
                        rs.getString("identity_arguments")))
                .list();
    }

    private List<LegacyObject> sequences() {
        return jdbc.sql(SEQUENCES).param(SCHEMA_PARAMETER, schema)
                .query((rs, row) -> (LegacyObject) new LegacyObject.Named(Kind.SEQUENCE, rs.getString("sequence_name")))
                .list();
    }

    /** The objects of the legacy schema that the running account has no right to drop: tables, routines, the schema. */
    private List<String> notOwned() {
        Map<String, Object> parameters = Map.of(SCHEMA_PARAMETER, schema);
        Set<String> names = new TreeSet<>();
        for (String sql : List.of(NOT_OWNED_RELATIONS, NOT_OWNED_ROUTINES, NOT_OWNED_SCHEMA)) {
            names.addAll(jdbc.sql(sql).params(parameters).query(String.class).list());
        }
        return List.copyOf(names);
    }

    /**
     * The other sessions that are in the way: a session of a 1.x role (another account than the one that runs the command, whose
     * own pool cannot be told from a 1.x session) and a session that holds a lock on a relation of the legacy schema. A session
     * of another account is only visible to an account that may see it ({@code pg_stat_activity}); that is a limit of this check.
     */
    private List<String> sessions() {
        Set<String> sessions = new TreeSet<>();
        if (!roles.isEmpty()) {
            jdbc.sql(SESSIONS_AS_ROLE).param(ROLES_PARAMETER, roles)
                    .query((rs, row) -> "pid " + rs.getLong("pid") + " as " + rs.getString("rolname")).list()
                    .forEach(sessions::add);
        }
        jdbc.sql(SESSIONS_WITH_LOCKS).param(SCHEMA_PARAMETER, schema)
                .query((rs, row) -> "pid " + rs.getLong("pid") + " uses the schema " + schema).list()
                .forEach(sessions::add);
        return List.copyOf(sessions);
    }

    private List<String> existingRoles() {
        if (roles.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(EXISTING_ROLES).param(ROLES_PARAMETER, roles).query(String.class).list().stream().sorted()
                .toList();
    }

    /** Why the role stays (the first reason that holds); empty when it is dropped. */
    private Optional<LegacyRoleSkip.Reason> whyKept(String role, Set<String> protectedAccounts) {
        String running = jdbc.sql("SELECT CURRENT_USER").query(String.class).single();
        if (Objects.equals(role, running)) {
            return Optional.of(LegacyRoleSkip.Reason.RUNNING_ACCOUNT);
        }
        if (protectedAccounts.contains(role)) {
            return Optional.of(LegacyRoleSkip.Reason.LEDGER_2_ACCOUNT);
        }
        boolean mayDrop = jdbc.sql(MAY_DROP_ROLES).query((rs, row) -> rs.getBoolean("rolsuper")
                || rs.getBoolean("rolcreaterole")).single();
        if (!mayDrop) {
            return Optional.of(LegacyRoleSkip.Reason.NO_PRIVILEGE);
        }
        if (count(MEMBERS_OUTSIDE, Map.of(ROLE_PARAMETER, role, ROLES_PARAMETER, roles)) > 0) {
            return Optional.of(LegacyRoleSkip.Reason.OTHER_MEMBERS);
        }
        Map<String, Object> owner = Map.of(ROLE_PARAMETER, role, SCHEMA_PARAMETER, schema);
        if (count(OWNED_RELATIONS_ELSEWHERE, owner) + count(OWNED_ROUTINES_ELSEWHERE, owner)
                + count(OWNED_SCHEMAS_ELSEWHERE, owner) > 0) {
            return Optional.of(LegacyRoleSkip.Reason.OWNS_OTHER_OBJECTS);
        }
        return Optional.empty();
    }

    private long count(String sql, Map<String, Object> parameters) {
        return jdbc.sql(sql).params(parameters).query(Long.class).single();
    }
}
