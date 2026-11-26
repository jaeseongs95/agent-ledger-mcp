// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.migrate1x.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.agentledger.migrate1x.database.LegacyObject.Kind;
import org.agentledger.migrate1x.fixture.LegacyFixtures;
import org.agentledger.migrate1x.fixture.LegacyLedger;
import org.agentledger.testing.database.TestDatabase;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * RET-001 .. RET-006 (the repository part of {@code retire-1x}): the list of the objects of a legacy schema, what stands in
 * the way, and the drop, on the PostgreSQL of the test PC with the legacy fixture that has functions, a trigger and a view.
 * No test creates or drops a role: the owner account of the tests may not, and a role belongs to the whole cluster.
 */
class LegacySchemaRepositoryTest {

    private static final String ABSENT_ROLE = "retire_absent_role_x";

    private static LegacySchemaRepository repository(LegacyLedger legacy, List<String> roles) {
        return new LegacySchemaRepository(JdbcClient.create(legacy.admin()), legacy.admin(), legacy.schema(), roles);
    }

    private static List<String> lines(LegacyInventory inventory) {
        return inventory.objects().stream().map(LegacyObject::line).toList();
    }

    @Test
    @DisplayName("RET-001 the list holds the triggers, views, tables, functions, sequences and the schema in the order of the drop (the functions after the tables: a CHECK calls one), with the identity arguments of a function; two calls give the same list; nothing stands in the way")
    void theListIsOrderedAndStable() {
        try (LegacyLedger legacy = LegacyFixtures.withDatabaseObjects(LegacyFixtures.rich())) {
            LegacySchemaRepository repository = repository(legacy, List.of(ABSENT_ROLE));

            LegacyInventory first = repository.inventory(Set.of());
            LegacyInventory second = repository.inventory(Set.of());

            assertThat(first).isEqualTo(second);
            assertThat(first.objects()).extracting(LegacyObject::kind).isSortedAccordingTo(Comparator.naturalOrder());
            assertThat(lines(first)).contains("TRIGGER common_code.common_code_keep", "VIEW v_defect",
                    "FUNCTION _author_ok(p text)", "FUNCTION whoami(p_author text)", "FUNCTION _code_keep()",
                    "SCHEMA " + legacy.schema());
            for (String table : LegacyLedger.TABLES) {
                assertThat(lines(first)).contains("TABLE " + table);
            }
            assertThat(lines(first).getFirst()).startsWith("TRIGGER ");
            assertThat(lines(first).getLast()).isEqualTo("SCHEMA " + legacy.schema());
            assertThat(first.objects()).extracting(LegacyObject::kind).doesNotContain(Kind.ROLE, Kind.PROCEDURE);
            assertThat(first.skippedRoles()).isEmpty();
            assertThat(first.notOwned()).isEmpty();
            assertThat(first.sessions()).isEmpty();
        }
    }

    @Test
    @DisplayName("RET-002 an account that is not the owner gets the objects it cannot drop named (tables, functions and the schema)")
    void aForeignAccountSeesWhatItDoesNotOwn() {
        try (LegacyLedger legacy = LegacyFixtures.withDatabaseObjects(LegacyFixtures.rich())) {
            LegacySchemaRepository asReader = new LegacySchemaRepository(JdbcClient.create(legacy.reader()),
                    legacy.reader(), legacy.schema(), List.of(ABSENT_ROLE));

            assertThat(asReader.inventory(Set.of()).notOwned()).contains("project", "task", "whoami", legacy.schema());
        }
    }

    @Test
    @DisplayName("RET-003 a session that holds a lock on a table of the schema is named; without it the list names none")
    void aSessionThatUsesTheSchemaIsNamed() throws SQLException {
        try (LegacyLedger legacy = LegacyFixtures.rich()) {
            LegacySchemaRepository repository = repository(legacy, List.of(ABSENT_ROLE));
            try (Connection other = legacy.admin().getConnection()) {
                other.setAutoCommit(false);
                try (Statement statement = other.createStatement()) {
                    statement.execute("SELECT COUNT(*) FROM " + legacy.schema() + ".project");
                }

                assertThat(repository.inventory(Set.of()).sessions()).hasSize(1).first().asString()
                        .matches("pid \\d+ uses the schema " + legacy.schema());
                other.rollback();
            }

            assertThat(repository.inventory(Set.of()).sessions()).isEmpty();
        }
    }

    @Test
    @DisplayName("RET-004 a role stays when it is the account that runs the command or an account of the 2.0 settings, and is listed with the reason; a role that does not exist is not listed at all")
    void rolesThatMustStayAreListedWithTheReason() {
        try (LegacyLedger legacy = LegacyFixtures.rich()) {
            String running = (String) legacy.rows("SELECT CURRENT_USER AS account").getFirst().get("account");
            String application = TestDatabase.shared().settings().appUser();

            LegacyInventory inventory = repository(legacy, List.of(running, ABSENT_ROLE)).inventory(Set.of());
            assertThat(inventory.skippedRoles()).containsExactly(new LegacyRoleSkip(running,
                    LegacyRoleSkip.Reason.RUNNING_ACCOUNT));
            assertThat(inventory.objects()).extracting(LegacyObject::kind).doesNotContain(Kind.ROLE);
            assertThat(lines(inventory)).doesNotContain("ROLE " + ABSENT_ROLE);

            LegacyInventory protectedAccount = repository(legacy, List.of(application)).inventory(Set.of(application));
            assertThat(protectedAccount.skippedRoles()).extracting(LegacyRoleSkip::name).containsExactly(application);
            assertThat(protectedAccount.skippedRoles().getFirst().reason()).isIn(LegacyRoleSkip.Reason.RUNNING_ACCOUNT,
                    LegacyRoleSkip.Reason.LEDGER_2_ACCOUNT);
        }
    }

    @Test
    @DisplayName("RET-005 the drop leaves no function, trigger, view, table or schema (checked with the JDBC metadata and with the catalog), and the schema that is not the legacy one is untouched")
    void theDropRemovesEverything() throws SQLException {
        try (LegacyLedger legacy = LegacyFixtures.withDatabaseObjects(LegacyFixtures.rich());
                LegacyLedger bystander = LegacyLedger.create()) {
            LegacySchemaRepository repository = repository(legacy, List.of(ABSENT_ROLE));

            repository.drop(repository.inventory(Set.of()).objects());

            assertThat(TestDatabase.shared().exists(legacy.schema())).isFalse();
            assertThat(legacy.rows("SELECT COUNT(*) AS n FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_namespace n "
                    + "ON n.oid = p.pronamespace WHERE n.nspname = ?", legacy.schema()).getFirst().get("n"))
                    .isEqualTo(0L);
            assertThat(legacy.rows("SELECT COUNT(*) AS n FROM pg_catalog.pg_trigger t JOIN pg_catalog.pg_class c "
                    + "ON c.oid = t.tgrelid JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace "
                    + "WHERE n.nspname = ?", legacy.schema()).getFirst().get("n")).isEqualTo(0L);
            try (Connection connection = legacy.admin().getConnection()) {
                DatabaseMetaData metaData = connection.getMetaData();
                try (ResultSet tables = metaData.getTables(null, legacy.schema(), "%", null)) {
                    assertThat(tables.next()).as("a table or view of the dropped schema").isFalse();
                }
                try (ResultSet functions = metaData.getFunctions(null, legacy.schema(), "%")) {
                    assertThat(functions.next()).as("a function of the dropped schema").isFalse();
                }
            }
            assertThat(TestDatabase.shared().exists(bystander.schema())).isTrue();
            assertThat(bystander.count("_migration")).isEqualTo(LegacyLedger.ALL_STEPS.size());
            assertThat(bystander.objects()).containsAll(LegacyLedger.TABLES);
        }
    }

    @Test
    @DisplayName("RET-006 the schema must be a plain name and a role a plain identifier; the table of 2.0 is found by its name")
    void theRepositoryChecksItsNames() {
        try (LegacyLedger legacy = LegacyFixtures.rich()) {
            JdbcClient jdbc = JdbcClient.create(legacy.admin());
            assertThatThrownBy(() -> new LegacySchemaRepository(jdbc, legacy.admin(), "Ledger", List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new LegacySchemaRepository(jdbc, legacy.admin(), legacy.schema(),
                    List.of("role; DROP")))
                    .isInstanceOf(IllegalArgumentException.class);

            LegacySchemaRepository repository = repository(legacy, List.of());
            assertThat(repository.holdsTable("project")).isTrue();
            assertThat(repository.holdsTable("schema_history")).isFalse();
        }
    }
}
