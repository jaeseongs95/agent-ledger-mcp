package org.pgledger.database.repository;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.pgledger.database.time.UtcTime;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;
import org.pgledger.domain.value.Sha256;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.DatabaseMetaDataCallback;
import org.springframework.jdbc.support.JdbcUtils;
import org.springframework.jdbc.support.MetaDataAccessException;
import org.springframework.stereotype.Repository;

/**
 * The migration bookkeeping on the ledger {@link DataSource}: the table {@code schema_history}, the history of the three products
 * in one form (one row per attempt, key {@code (version, attempt)}; a row is inserted as FAILED when its attempt starts and the
 * attempt turns that one row to SUCCEEDED at its end; no other row is updated, none is deleted) and its rows. A repository like
 * the others: it takes the {@link JdbcClient} and the data source of the
 * container (principle 3.9), and joins the transaction of its caller (principle 4.4; the migrator's service method). The
 * schema of the tables ({@code ledger.schema.name}) is a property of that one data source: its connections are made with
 * that schema ({@code DriverDataSources}), so nothing here chooses a schema per call.
 *
 * <p>The one exception to "one data source" is the check of the application account ({@code ApplicationAccountAccess}),
 * which asks the same questions over a connection of the application account; its configuration makes a second instance
 * over that account's data source.
 */
@Repository
public class SchemaHistoryRepository {

    /** Result of one attempt to apply a version; a row starts as FAILED (the claim of the attempt) and turns to SUCCEEDED at its end. */
    public enum Outcome {
        SUCCEEDED, FAILED
    }

    /**
     * One row of {@code getTypeInfo}: the name of a type the database offers, its JDBC type ({@code java.sql.Types}), its precision and
     * its {@code CREATE_PARAMS} (the parameters used in creating the type, may be null: it tells what can be given, not what is required).
     */
    public record TypeInfo(String name, int dataType, long precision, String createParams) {

        /** Whether the type reports parameters (a length) for its creation, that is, {@code CREATE_PARAMS} is not null or blank. */
        public boolean needsLength() {
            return createParams != null && !createParams.isBlank();
        }
    }

    /**
     * One row of schema_history: attempt number {@code attempt} (from 1) of {@code version}, with the file name and the SHA-256 of
     * the file before the placeholder is replaced.
     */
    public record Row(int version, int attempt, String script, Sha256 digest, Instant appliedAt, Outcome outcome) {
    }

    private static final Pattern SIMPLE_NAME = Pattern.compile("[a-z][a-z0-9_]{0,29}");
    private static final String TABLE = "schema_history";

    private final DataSource dataSource;
    private final JdbcClient jdbc;

    public SchemaHistoryRepository(JdbcClient jdbc, DataSource dataSource) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
    }

    /** The name as the DBMS stores an unquoted identifier (JDBC metadata tells the folding). */
    public static String fold(DatabaseMetaData metaData, String name) throws SQLException {
        if (metaData.storesUpperCaseIdentifiers()) {
            return name.toUpperCase(Locale.ROOT);
        }
        return metaData.storesLowerCaseIdentifiers() ? name.toLowerCase(Locale.ROOT) : name;
    }

    /**
     * A name as the exact pattern of {@code DatabaseMetaData.getTables} and the other catalog calls: their schema and table
     * arguments are LIKE patterns (JDBC), where {@code _} and {@code %} are wildcards, so {@code schema_history} would match
     * {@code schemaXhistory} too. The escape character is the one the driver reports
     * ({@code getSearchStringEscape}); a driver that reports none gets the name unchanged. A null stays null (no restriction).
     */
    public static String exactPattern(DatabaseMetaData metaData, String name) throws SQLException {
        String escape = metaData.getSearchStringEscape();
        if (name == null || escape == null || escape.isEmpty()) {
            return name;
        }
        StringBuilder exact = new StringBuilder(name.length() + 4);
        for (char c : name.toCharArray()) {
            if (c == '_' || c == '%' || escape.indexOf(c) >= 0) {
                exact.append(escape);
            }
            exact.append(c);
        }
        return exact.toString();
    }

    /**
     * Reads the database metadata through Spring's {@link JdbcUtils}, which gets and releases the connection. This and
     * {@link #fold} are the contact points with raw JDBC metadata, kept to the few questions that no Spring Data or
     * {@code JdbcClient} call answers: whether a table or a schema exists, how the DBMS folds an unquoted identifier,
     * and the nullability and length of a column. ANSI has no portable statement for these, and the JDBC metadata is the
     * portable way (the schema files and the DBMS stay the one source of truth).
     */
    public static <T> T inspect(DataSource dataSource, DatabaseMetaDataCallback<T> callback) {
        try {
            return JdbcUtils.extractDatabaseMetaData(dataSource, callback);
        } catch (MetaDataAccessException e) {
            throw new InfrastructureException(InfrastructureCode.INTERNAL_ERROR,
                    List.of("cannot inspect the database"), e);
        }
    }

    /**
     * Creates the schema when it is missing (the schema name is an identifier, so it cannot be a bind value). It runs on the
     * data source whose connections already name this schema, before the schema exists: selecting a schema that is not there
     * yet is no error on PostgreSQL ({@code Connection.setSchema} is {@code SET SESSION search_path}, and a name in the search
     * path that matches no schema is skipped), and the schema is found by the connection as soon as it is created.
     */
    public void createSchemaIfMissing(String schema) {
        if (!schemaExists(schema)) {
            jdbc.sql("CREATE SCHEMA " + schema).update();
        }
    }

    /** Whether the schema is there (the JDBC metadata folds the name like the DBMS does). */
    public boolean schemaExists(String schema) {
        if (!SIMPLE_NAME.matcher(schema).matches()) {
            throw new IllegalArgumentException("schema name must be lower case letters, digits and _ (max 30): "
                    + schema);
        }
        return inspect(dataSource, metaData -> {
            String folded = fold(metaData, schema);
            try (ResultSet rs = metaData.getSchemas()) {
                while (rs.next()) {
                    if (folded.equalsIgnoreCase(rs.getString("TABLE_SCHEM"))) {
                        return true;
                    }
                }
            }
            return false;
        });
    }

    /**
     * The names of the character sets the database reports in the standard view {@code INFORMATION_SCHEMA.CHARACTER_SETS} (the
     * encoding of the database: PostgreSQL lists the one that the database has). JDBC has no call for it and the view is ANSI, so no
     * product query is asked; {@link org.pgledger.database.schema.DatabaseEncoding} judges the names.
     */
    public List<String> characterSets() {
        return jdbc.sql("SELECT character_set_name FROM information_schema.character_sets").query(String.class).list();
    }

    /** The types the driver reports ({@code getTypeInfo}), in its order: the facts that the long text type is chosen from. */
    public List<TypeInfo> typeInfo() {
        return inspect(dataSource, SchemaHistoryRepository::typeInfo);
    }

    /** The rows of {@code metaData.getTypeInfo()}: the columns {@code TYPE_NAME}, {@code DATA_TYPE}, {@code PRECISION} and {@code CREATE_PARAMS}. */
    public static List<TypeInfo> typeInfo(DatabaseMetaData metaData) throws SQLException {
        List<TypeInfo> types = new ArrayList<>();
        try (ResultSet rs = metaData.getTypeInfo()) {
            while (rs.next()) {
                types.add(new TypeInfo(rs.getString("TYPE_NAME"), rs.getInt("DATA_TYPE"), rs.getLong("PRECISION"),
                        rs.getString("CREATE_PARAMS")));
            }
        }
        return List.copyOf(types);
    }

    public boolean tableExists() {
        return inspect(dataSource, metaData -> {
            Connection connection = metaData.getConnection();
            try (ResultSet rs = metaData.getTables(connection.getCatalog(),
                    exactPattern(metaData, connection.getSchema()), exactPattern(metaData, fold(metaData, TABLE)), new String[] {"TABLE"})) {
                return rs.next();
            }
        });
    }

    /** Creates the history table in the common form of the three products (the columns, the key and the check are that form). */
    public void createTable() {
        jdbc.sql("CREATE TABLE schema_history ("
                + "version BIGINT NOT NULL, "
                + "attempt INTEGER NOT NULL, "
                + "script VARCHAR(255) NOT NULL, "
                + "digest CHAR(64) NOT NULL, "
                + "applied_at TIMESTAMP NOT NULL, "
                + "outcome VARCHAR(10) NOT NULL, "
                + "CONSTRAINT pk_schema_history PRIMARY KEY (version, attempt), "
                + "CONSTRAINT ck_schema_history_outcome CHECK (outcome IN ('SUCCEEDED', 'FAILED')))").update();
    }

    /** All attempts, by version and attempt. */
    public List<Row> rows() {
        List<Row> rows = new ArrayList<>(jdbc.sql(
                "SELECT version, attempt, script, digest, applied_at, outcome FROM schema_history ORDER BY version, attempt")
                .query((rs, n) -> new Row(rs.getInt("version"), rs.getInt("attempt"), rs.getString("script"),
                        new Sha256(rs.getString("digest")), UtcTime.read(rs, "applied_at"),
                        Outcome.valueOf(rs.getString("outcome"))))
                .list());
        return List.copyOf(rows);
    }

    /**
     * Inserts a row: the claim of an attempt (outcome FAILED, the first statement of the transaction of the attempt, which holds the
     * key of the attempt in the database against a second runner) or the record of an attempt that failed (the same row, written in
     * a transaction of its own after the attempt was rolled back). A second row with the same version and attempt is the duplicate
     * key, which the migrator reads as "another runner has this attempt".
     */
    public void append(Row row) {
        jdbc.sql("INSERT INTO schema_history (version, attempt, script, digest, applied_at, outcome) "
                + "VALUES (:version, :attempt, :script, :digest, :at, :outcome)")
                .param("version", row.version())
                .param("attempt", row.attempt())
                .param("script", row.script())
                .param("digest", row.digest().hex())
                .param("at", UtcTime.toDb(row.appliedAt()))
                .param("outcome", row.outcome().name())
                .update();
    }

    /**
     * The last statement of an attempt: turns the row that this attempt inserted to SUCCEEDED. It changes that one row (the key and
     * the outcome FAILED that it was inserted with) and no other.
     *
     * @throws org.springframework.dao.IncorrectResultSizeDataAccessException when that row is not there (the attempt failed)
     */
    public void succeed(int version, int attempt) {
        int changed = jdbc.sql("UPDATE schema_history SET outcome = 'SUCCEEDED' WHERE version = :version AND attempt = :attempt "
                + "AND outcome = 'FAILED'").param("version", version).param("attempt", attempt).update();
        if (changed != 1) {
            throw new IncorrectResultSizeDataAccessException(1, changed);
        }
    }
}
