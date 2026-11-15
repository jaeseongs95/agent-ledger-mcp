package org.pgledger.migrate1x.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.pgledger.domain.value.Sha256;
import org.pgledger.migrate1x.database.LegacyTable;
import org.pgledger.migrate1x.fixture.LegacyFixtures;
import org.pgledger.migrate1x.fixture.LegacyLedger;
import org.pgledger.migrate1x.support.EnglishMessages;
import org.pgledger.migrate1x.support.RecordingOutput;
import org.pgledger.migrate1x.support.TestLedgerDatabase;
import org.pgledger.migrate1x.support.ToolExtension;
import org.pgledger.migrate1x.support.ToolExtension.Outcome;
import org.pgledger.migrate1x.support.ToolExtension.Tool;
import org.pgledger.migrate1x.tool.ErrorCode;
import org.pgledger.migrate1x.tool.ExitStatus;
import org.pgledger.testing.database.TestDatabase;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * RET-007 .. RET-015: the command {@code retire-1x} on the real wiring (the real application context, services and
 * repositories, a migrated new schema of its own and a 1.x schema of its own with functions, a trigger and a view, in the
 * PostgreSQL of the test PC). The 1.x source is read as the owner account of the tests (the account that made the objects)
 * unless a test says otherwise. Every run names its roles ({@code ledger.import.source.roles}): the default roles of the old
 * installer are cluster wide and must never be touched by a test, and no test creates or drops a role. The request receipts
 * of 1.x are not imported (M1X-003): the comparison before a removal covers the twelve other tables, and the table of the
 * receipts is one of the objects of the 1.x schema that the removal drops.
 */
@ExtendWith({ OutputCaptureExtension.class, ToolExtension.class, EnglishMessages.class })
class Retire1xCommandTest {

    private static final String USER = "alice";
    private static final String ABSENT_ROLE = "retire_absent_role_x";
    private static final Pattern DIGEST_LINE = Pattern.compile("SHA-256 of the list: ([0-9a-f]{64})");
    private static final String FUNCTIONS = "SELECT COUNT(*) AS n FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_namespace n "
            + "ON n.oid = p.pronamespace WHERE n.nspname = ?";
    private static final String TRIGGERS = "SELECT COUNT(*) AS n FROM pg_catalog.pg_trigger t JOIN pg_catalog.pg_class c "
            + "ON c.oid = t.tgrelid JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = ? AND NOT t.tgisinternal";

    @TempDir
    Path tmp;

    private TestLedgerDatabase database;
    private LegacyLedger legacy;

    @BeforeEach
    void open() {
        database = TestLedgerDatabase.migrated();
        legacy = LegacyFixtures.withDatabaseObjects(LegacyFixtures.rich());
    }

    @AfterEach
    void close() {
        legacy.close();
        database.close();
    }

    private Outcome run(Tool tool, RecordingOutput output, String... args) {
        return runAs(tool, legacy.admin(), ABSENT_ROLE, output, args);
    }

    private Outcome runAs(Tool tool, javax.sql.DataSource source, String roles, RecordingOutput output, String... args) {
        return tool.withDatabase(database.dataSource()).withLegacySource(source, legacy.schema())
                .withArguments("--ledger.import.source.roles=" + roles).asProcessUser(USER)
                .run(tmp.resolve("secrets"), null, output, args);
    }

    private void imported(Tool tool) {
        assertThat(run(tool, new RecordingOutput(), "import-1x").exitCode()).isZero();
    }

    private static String digestOf(RecordingOutput output) {
        Matcher matcher = DIGEST_LINE.matcher(output.info());
        assertThat(matcher.find()).as("the line with the digest of the list").isTrue();
        return matcher.group(1);
    }

    private long countOf(List<Map<String, Object>> rows) {
        return ((Number) rows.getFirst().get("n")).longValue();
    }

    /** Nothing was dropped: the schema, its tables, the view, the three functions and the trigger are there.
     * Internal triggers (the ones PostgreSQL makes for foreign keys) are not counted. */
    private void assertLegacyIntact() {
        assertThat(TestDatabase.shared().exists(legacy.schema())).isTrue();
        assertThat(legacy.objects()).containsAll(LegacyLedger.TABLES).contains("v_defect");
        assertThat(countOf(legacy.rows(FUNCTIONS, legacy.schema()))).isEqualTo(3);
        assertThat(countOf(legacy.rows(TRIGGERS, legacy.schema()))).isEqualTo(1);
    }

    @Test
    @DisplayName("RET-007 retire-1x without options lists every object in the order of the drop and the SHA-256 of that list (the SHA-256 of the lines shown); two runs print the same; nothing is dropped")
    void listsTheObjects(Tool tool) {
        imported(tool);
        RecordingOutput first = new RecordingOutput();
        RecordingOutput second = new RecordingOutput();

        Outcome listed = run(tool, first, "retire-1x");
        Outcome again = run(tool, second, "retire-1x");

        assertThat(listed.exitCode()).isZero();
        assertThat(listed.failure()).isNull();
        assertThat(again.exitCode()).isZero();
        String text = first.info();
        assertThat(text).contains("TRIGGER common_code.common_code_keep", "VIEW v_defect", "TABLE project",
                "TABLE request_receipt", "FUNCTION _author_ok(p text)", "FUNCTION whoami(p_author text)",
                "FUNCTION _code_keep()", "SCHEMA " + legacy.schema()).doesNotContain("ROLE ").contains("Nothing was changed")
                .contains("--confirm=" + digestOf(first) + " --backup-tested");
        assertThat(text.indexOf("TRIGGER ")).isLessThan(text.indexOf("VIEW v_defect"));
        assertThat(text.indexOf("VIEW v_defect")).isLessThan(text.indexOf("TABLE project"));
        assertThat(text.indexOf("TABLE project")).isLessThan(text.indexOf("FUNCTION _author_ok"));
        assertThat(text.indexOf("FUNCTION _author_ok")).isLessThan(text.indexOf("SCHEMA " + legacy.schema()));
        assertThat(second.info()).isEqualTo(text);
        List<String> shown = text.lines().toList();
        List<String> objects = shown.subList(0, shown.size() - 1);
        assertThat(digestOf(first)).isEqualTo(Sha256.ofText(String.join("\n", objects) + "\n").hex());
        assertLegacyIntact();
    }

    @Test
    @DisplayName("RET-008 --confirm with a digest that is not the one of the list now is RETIRE_SOURCE_CHANGED (exit 1) naming the digest of the list now; nothing is dropped")
    void refusesAnotherList(Tool tool) {
        imported(tool);
        RecordingOutput listed = new RecordingOutput();
        run(tool, listed, "retire-1x");

        Outcome outcome = run(tool, new RecordingOutput(), "retire-1x", "--confirm=" + "0".repeat(64), "--backup-tested");

        assertThat(outcome.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
        assertThat(outcome.code()).contains(ErrorCode.RETIRE_SOURCE_CHANGED);
        assertThat(outcome.refusal().orElseThrow().arguments()).containsExactly(digestOf(listed));
        assertLegacyIntact();
    }

    @Test
    @DisplayName("RET-009 the command line: --confirm without --backup-tested is RETIRE_BACKUP_NOT_ACKNOWLEDGED, --backup-tested without --confirm, a digest that is not 64 lower-case hex characters and --backup-tested=<value> are INVALID_OPTION_VALUE, an operand is TOO_MANY_ARGUMENTS, another option is UNKNOWN_OPTION (exit 2 each); without the 1.x connection it is IMPORT_SOURCE_NOT_CONFIGURED (exit 1); nothing is dropped")
    void refusesWrongCommandLines(Tool tool) {
        String digest = "a".repeat(64);

        Outcome unacknowledged = run(tool, new RecordingOutput(), "retire-1x", "--confirm=" + digest);
        assertThat(unacknowledged.code()).contains(ErrorCode.RETIRE_BACKUP_NOT_ACKNOWLEDGED);
        assertThat(run(tool, new RecordingOutput(), "retire-1x", "--backup-tested").code())
                .contains(ErrorCode.INVALID_OPTION_VALUE);
        assertThat(run(tool, new RecordingOutput(), "retire-1x", "--confirm=abc", "--backup-tested").code())
                .contains(ErrorCode.INVALID_OPTION_VALUE);
        assertThat(run(tool, new RecordingOutput(), "retire-1x", "--confirm=" + digest.toUpperCase(java.util.Locale.ROOT),
                "--backup-tested").code()).contains(ErrorCode.INVALID_OPTION_VALUE);
        assertThat(run(tool, new RecordingOutput(), "retire-1x", "--confirm=" + digest, "--backup-tested=yes").code())
                .contains(ErrorCode.INVALID_OPTION_VALUE);
        assertThat(run(tool, new RecordingOutput(), "retire-1x", "extra").code()).contains(ErrorCode.TOO_MANY_ARGUMENTS);
        assertThat(run(tool, new RecordingOutput(), "retire-1x", "--force").code()).contains(ErrorCode.UNKNOWN_OPTION);
        for (String[] line : List.of(new String[] { "retire-1x", "--confirm=" + digest },
                new String[] { "retire-1x", "--backup-tested" }, new String[] { "retire-1x", "extra" },
                new String[] { "retire-1x", "--force" })) {
            assertThat(run(tool, new RecordingOutput(), line).exitCode()).isEqualTo(ExitStatus.USAGE.code());
        }

        Outcome unconfigured = tool.withDatabase(database.dataSource()).asProcessUser(USER).run(tmp.resolve("secrets"), null,
                new RecordingOutput(), "retire-1x");
        assertThat(unconfigured.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
        assertThat(unconfigured.code()).contains(ErrorCode.IMPORT_SOURCE_NOT_CONFIGURED);
        assertLegacyIntact();
    }

    @Test
    @DisplayName("RET-010 when the new schema does not match the 1.x ledger (a changed row) the listing and the confirmation are RETIRE_VERIFY_FAILED (exit 1) naming the table, with the rows printed; nothing is dropped")
    void refusesWhenTheMoveIsNotComplete(Tool tool, CapturedOutput captured) {
        imported(tool);
        RecordingOutput listed = new RecordingOutput();
        run(tool, listed, "retire-1x");
        database.update("UPDATE defect SET title = 'tampered' WHERE id = 'OLD-D-0001'");
        RecordingOutput failed = new RecordingOutput();

        Outcome afterwards = run(tool, failed, "retire-1x");
        Outcome confirmed = run(tool, new RecordingOutput(), "retire-1x", "--confirm=" + digestOf(listed),
                "--backup-tested");

        for (Outcome outcome : List.of(afterwards, confirmed)) {
            assertThat(outcome.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
            assertThat(outcome.code()).contains(ErrorCode.RETIRE_VERIFY_FAILED);
            assertThat(outcome.refusal().orElseThrow().arguments()).hasSize(5).first().isEqualTo("defect");
        }
        assertThat(failed.info()).contains("Table defect: ");
        assertThat(captured.getAll()).contains("differ in the table defect").contains("nothing was removed");
        assertLegacyIntact();
    }

    @Test
    @DisplayName("RET-011 --confirm=<digest of the list> --backup-tested drops the list in one transaction: exit 0, the 1.x schema is gone (no function, trigger, view, table or schema: JDBC metadata and catalog), the new schema holds the same rows; a second run is IMPORT_SOURCE_INCOMPLETE")
    void removesTheLegacyObjects(Tool tool) throws SQLException {
        imported(tool);
        RecordingOutput listed = new RecordingOutput();
        run(tool, listed, "retire-1x");
        Map<String, Long> rows = new HashMap<>();
        for (LegacyTable table : LegacyTable.values()) {
            rows.put(table.targetName(), countOf(database.rows("SELECT COUNT(*) AS n FROM " + table.targetName())));
        }
        assertThat(rows).hasSize(12).doesNotContainKey("request_receipt");
        long history = countOf(database.rows("SELECT COUNT(*) AS n FROM schema_history"));
        RecordingOutput removed = new RecordingOutput();

        Outcome outcome = run(tool, removed, "retire-1x", "--confirm=" + digestOf(listed), "--backup-tested");

        assertThat(outcome.exitCode()).isZero();
        assertThat(outcome.failure()).isNull();
        assertThat(removed.info()).contains("TRIGGER common_code.common_code_keep", "SCHEMA " + legacy.schema())
                .contains("Removed ").contains(" objects of the 1.x schema " + legacy.schema());
        assertThat(TestDatabase.shared().exists(legacy.schema())).isFalse();
        assertThat(countOf(legacy.rows(FUNCTIONS, legacy.schema()))).isZero();
        assertThat(countOf(legacy.rows(TRIGGERS, legacy.schema()))).isZero();
        try (Connection connection = legacy.admin().getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            try (ResultSet tables = metaData.getTables(null, legacy.schema(), "%", null)) {
                assertThat(tables.next()).as("a table or view of the dropped schema").isFalse();
            }
            try (ResultSet functions = metaData.getFunctions(null, legacy.schema(), "%")) {
                assertThat(functions.next()).as("a function of the dropped schema").isFalse();
            }
        }
        for (LegacyTable table : LegacyTable.values()) {
            assertThat(countOf(database.rows("SELECT COUNT(*) AS n FROM " + table.targetName()))).as(table.name())
                    .isEqualTo(rows.get(table.targetName()));
        }
        assertThat(countOf(database.rows("SELECT COUNT(*) AS n FROM request_receipt"))).as("no 1.x receipt was carried over")
                .isZero();
        assertThat(countOf(database.rows("SELECT COUNT(*) AS n FROM schema_history"))).isEqualTo(history);

        Outcome second = run(tool, new RecordingOutput(), "retire-1x");
        assertThat(second.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
        assertThat(second.code()).contains(ErrorCode.IMPORT_SOURCE_INCOMPLETE);
    }

    @Test
    @DisplayName("RET-012 a 1.x schema that holds the tables of 2.0 itself (the source names the schema of 2.0) is RETIRE_OWN_SCHEMA (exit 1); the tables of 2.0 are still there")
    void refusesTheSchemaOfTheNewLedger(Tool tool) {
        long history = countOf(database.rows("SELECT COUNT(*) AS n FROM schema_history"));

        Outcome outcome = tool.withDatabase(database.dataSource())
                .withLegacySource(database.dataSource(), database.schemaName())
                .withArguments("--ledger.import.source.roles=" + ABSENT_ROLE).asProcessUser(USER)
                .run(tmp.resolve("secrets"), null, new RecordingOutput(), "retire-1x");

        assertThat(outcome.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
        assertThat(outcome.code()).contains(ErrorCode.RETIRE_OWN_SCHEMA);
        assertThat(outcome.refusal().orElseThrow().arguments()).containsExactly(database.schemaName());
        assertThat(countOf(database.rows("SELECT COUNT(*) AS n FROM schema_history"))).isEqualTo(history);
    }

    @Test
    @DisplayName("RET-013 another session that holds a lock on a table of the 1.x schema (the 1.x service is not stopped) is RETIRE_SESSIONS_CONNECTED (exit 1) naming it; nothing is dropped; the list works again when the session has ended")
    void refusesWhileASessionUsesTheSchema(Tool tool) throws SQLException {
        imported(tool);

        try (Connection other = legacy.admin().getConnection()) {
            other.setAutoCommit(false);
            try (Statement statement = other.createStatement()) {
                statement.execute("SELECT COUNT(*) FROM " + legacy.schema() + ".project");
            }

            Outcome outcome = run(tool, new RecordingOutput(), "retire-1x");

            assertThat(outcome.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
            assertThat(outcome.code()).contains(ErrorCode.RETIRE_SESSIONS_CONNECTED);
            assertThat(outcome.refusal().orElseThrow().arguments().getFirst())
                    .matches("pid \\d+ uses the schema " + legacy.schema());
            assertLegacyIntact();
            other.rollback();
        }

        assertThat(run(tool, new RecordingOutput(), "retire-1x").exitCode()).isZero();
    }

    @Test
    @DisplayName("RET-014 an account that does not own the objects (the read-only login of the import) is RETIRE_NOT_OWNER (exit 1) naming objects and their number; nothing is dropped")
    void refusesAnAccountThatDoesNotOwnTheObjects(Tool tool) {
        imported(tool);

        Outcome outcome = runAs(tool, legacy.reader(), ABSENT_ROLE, new RecordingOutput(), "retire-1x");

        assertThat(outcome.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
        assertThat(outcome.code()).contains(ErrorCode.RETIRE_NOT_OWNER);
        List<String> arguments = outcome.refusal().orElseThrow().arguments();
        assertThat(arguments.getFirst()).contains("_author_ok");
        assertThat(Integer.parseInt(arguments.get(1))).isGreaterThan(LegacyLedger.TABLES.size());
        assertLegacyIntact();
    }

    @Test
    @DisplayName("RET-015 a role of the setting that is the running account stays: the list shows SKIPPED ROLE <account> RUNNING_ACCOUNT, no ROLE line, and after the confirmed run the account still exists")
    void keepsTheRunningAccount(Tool tool) {
        imported(tool);
        String account = (String) legacy.rows("SELECT CURRENT_USER AS account").getFirst().get("account");
        RecordingOutput listed = new RecordingOutput();

        Outcome outcome = runAs(tool, legacy.admin(), account, listed, "retire-1x");

        assertThat(outcome.exitCode()).isZero();
        assertThat(listed.info()).contains("SKIPPED ROLE " + account + " RUNNING_ACCOUNT").doesNotContain("\nROLE ");
        Outcome removed = runAs(tool, legacy.admin(), account, new RecordingOutput(), "retire-1x",
                "--confirm=" + digestOf(listed), "--backup-tested");

        assertThat(removed.exitCode()).isZero();
        assertThat(TestDatabase.shared().exists(legacy.schema())).isFalse();
        assertThat(countOf(database.rows("SELECT COUNT(*) AS n FROM pg_catalog.pg_roles WHERE rolname = ?", account)))
                .isEqualTo(1);
    }
}
