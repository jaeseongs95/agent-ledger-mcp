package org.pgledger.cli;

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
import org.pgledger.cli.support.CliExtension;
import org.pgledger.cli.support.CliExtension.Cli;
import org.pgledger.cli.support.CliExtension.Outcome;
import org.pgledger.cli.support.EnglishMessages;
import org.pgledger.cli.support.RecordingOutput;
import org.pgledger.cli.support.TestLedgerDatabase;
import org.pgledger.database.repository.LegacyTable;
import org.pgledger.domain.value.Sha256;
import org.pgledger.testing.database.TestDatabase;
import org.pgledger.testing.legacy.LegacyFixtures;
import org.pgledger.testing.legacy.LegacyLedger;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * RET-007 .. RET-015: the command {@code retire-legacy} on the real wiring (the real application context, services and
 * repositories, a migrated new schema of its own and a legacy schema of its own with functions, a trigger and a view, in the
 * PostgreSQL of the test PC). The legacy source is read as the owner account of the tests (the account that made the objects)
 * unless a test says otherwise. Every run names its roles ({@code ledger.import.source.roles}): the default roles of the old
 * installer are cluster wide and must never be touched by a test, and no test creates or drops a role.
 */
@ExtendWith({ OutputCaptureExtension.class, CliExtension.class, EnglishMessages.class })
class RetireLegacyCommandTest {

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

    private Outcome run(Cli cli, RecordingOutput output, String... args) {
        return runAs(cli, legacy.admin(), ABSENT_ROLE, output, args);
    }

    private Outcome runAs(Cli cli, javax.sql.DataSource source, String roles, RecordingOutput output, String... args) {
        return cli.withDatabase(database.dataSource()).withLegacySource(source, legacy.schema())
                .withArguments("--ledger.import.source.roles=" + roles).asProcessUser(USER)
                .run(tmp.resolve("secrets"), null, output, args);
    }

    private void imported(Cli cli) {
        assertThat(run(cli, new RecordingOutput(), "import-legacy").exitCode()).isZero();
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
    @DisplayName("RET-007 retire-legacy without options lists every object in the order of the drop and the SHA-256 of that list (the SHA-256 of the lines shown); two runs print the same; nothing is dropped")
    void listsTheObjects(Cli cli) {
        imported(cli);
        RecordingOutput first = new RecordingOutput();
        RecordingOutput second = new RecordingOutput();

        Outcome listed = run(cli, first, "retire-legacy");
        Outcome again = run(cli, second, "retire-legacy");

        assertThat(listed.exitCode()).isZero();
        assertThat(listed.failure()).isNull();
        assertThat(again.exitCode()).isZero();
        String text = first.info();
        assertThat(text).contains("TRIGGER common_code.common_code_keep", "VIEW v_defect", "TABLE project",
                "FUNCTION _author_ok(p text)", "FUNCTION whoami(p_author text)", "FUNCTION _code_keep()",
                "SCHEMA " + legacy.schema()).doesNotContain("ROLE ").contains("Nothing was changed")
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
    void refusesAnotherList(Cli cli) {
        imported(cli);
        RecordingOutput listed = new RecordingOutput();
        run(cli, listed, "retire-legacy");

        Outcome outcome = run(cli, new RecordingOutput(), "retire-legacy", "--confirm=" + "0".repeat(64), "--backup-tested");

        assertThat(outcome.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
        assertThat(outcome.code()).contains(ErrorCode.RETIRE_SOURCE_CHANGED);
        assertThat(outcome.refusal().orElseThrow().arguments()).containsExactly(digestOf(listed));
        assertLegacyIntact();
    }

    @Test
    @DisplayName("RET-009 the command line: --confirm without --backup-tested is RETIRE_BACKUP_NOT_ACKNOWLEDGED, --backup-tested without --confirm, a digest that is not 64 lower-case hex characters and --backup-tested=<value> are INVALID_OPTION_VALUE, an operand is TOO_MANY_ARGUMENTS, another option is UNKNOWN_OPTION (exit 2 each); without the legacy connection it is IMPORT_SOURCE_NOT_CONFIGURED (exit 1); nothing is dropped")
    void refusesWrongCommandLines(Cli cli) {
        String digest = "a".repeat(64);

        Outcome unacknowledged = run(cli, new RecordingOutput(), "retire-legacy", "--confirm=" + digest);
        assertThat(unacknowledged.code()).contains(ErrorCode.RETIRE_BACKUP_NOT_ACKNOWLEDGED);
        assertThat(run(cli, new RecordingOutput(), "retire-legacy", "--backup-tested").code())
                .contains(ErrorCode.INVALID_OPTION_VALUE);
        assertThat(run(cli, new RecordingOutput(), "retire-legacy", "--confirm=abc", "--backup-tested").code())
                .contains(ErrorCode.INVALID_OPTION_VALUE);
        assertThat(run(cli, new RecordingOutput(), "retire-legacy", "--confirm=" + digest.toUpperCase(java.util.Locale.ROOT),
                "--backup-tested").code()).contains(ErrorCode.INVALID_OPTION_VALUE);
        assertThat(run(cli, new RecordingOutput(), "retire-legacy", "--confirm=" + digest, "--backup-tested=yes").code())
                .contains(ErrorCode.INVALID_OPTION_VALUE);
        assertThat(run(cli, new RecordingOutput(), "retire-legacy", "extra").code()).contains(ErrorCode.TOO_MANY_ARGUMENTS);
        assertThat(run(cli, new RecordingOutput(), "retire-legacy", "--force").code()).contains(ErrorCode.UNKNOWN_OPTION);
        for (String[] line : List.of(new String[] { "retire-legacy", "--confirm=" + digest },
                new String[] { "retire-legacy", "--backup-tested" }, new String[] { "retire-legacy", "extra" },
                new String[] { "retire-legacy", "--force" })) {
            assertThat(run(cli, new RecordingOutput(), line).exitCode()).isEqualTo(ExitStatus.USAGE.code());
        }

        Outcome unconfigured = cli.withDatabase(database.dataSource()).asProcessUser(USER).run(tmp.resolve("secrets"), null,
                new RecordingOutput(), "retire-legacy");
        assertThat(unconfigured.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
        assertThat(unconfigured.code()).contains(ErrorCode.IMPORT_SOURCE_NOT_CONFIGURED);
        assertLegacyIntact();
    }

    @Test
    @DisplayName("RET-010 when the new schema does not match the legacy ledger (a changed row) the listing and the confirmation are RETIRE_VERIFY_FAILED (exit 1) naming the table, with the rows printed; nothing is dropped")
    void refusesWhenTheMoveIsNotComplete(Cli cli, CapturedOutput captured) {
        imported(cli);
        RecordingOutput listed = new RecordingOutput();
        run(cli, listed, "retire-legacy");
        database.update("UPDATE defect SET title = 'tampered' WHERE id = 'OLD-D-0001'");
        RecordingOutput failed = new RecordingOutput();

        Outcome afterwards = run(cli, failed, "retire-legacy");
        Outcome confirmed = run(cli, new RecordingOutput(), "retire-legacy", "--confirm=" + digestOf(listed),
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
    @DisplayName("RET-011 --confirm=<digest of the list> --backup-tested drops the list in one transaction: exit 0, the legacy schema is gone (no function, trigger, view, table or schema: JDBC metadata and catalog), the new schema holds the same rows; a second run is IMPORT_SOURCE_INCOMPLETE")
    void removesTheLegacyObjects(Cli cli) throws SQLException {
        imported(cli);
        RecordingOutput listed = new RecordingOutput();
        run(cli, listed, "retire-legacy");
        Map<String, Long> rows = new HashMap<>();
        for (LegacyTable table : LegacyTable.values()) {
            rows.put(table.targetName(), countOf(database.rows("SELECT COUNT(*) AS n FROM " + table.targetName())));
        }
        long history = countOf(database.rows("SELECT COUNT(*) AS n FROM schema_history"));
        RecordingOutput removed = new RecordingOutput();

        Outcome outcome = run(cli, removed, "retire-legacy", "--confirm=" + digestOf(listed), "--backup-tested");

        assertThat(outcome.exitCode()).isZero();
        assertThat(outcome.failure()).isNull();
        assertThat(removed.info()).contains("TRIGGER common_code.common_code_keep", "SCHEMA " + legacy.schema())
                .contains("Removed ").contains(" objects of the legacy schema " + legacy.schema());
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
        assertThat(countOf(database.rows("SELECT COUNT(*) AS n FROM schema_history"))).isEqualTo(history);

        Outcome second = run(cli, new RecordingOutput(), "retire-legacy");
        assertThat(second.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
        assertThat(second.code()).contains(ErrorCode.IMPORT_SOURCE_INCOMPLETE);
    }

    @Test
    @DisplayName("RET-012 a legacy schema that holds the tables of 2.0 itself (the source names the schema of 2.0) is RETIRE_OWN_SCHEMA (exit 1); the tables of 2.0 are still there")
    void refusesTheSchemaOfTheNewLedger(Cli cli) {
        long history = countOf(database.rows("SELECT COUNT(*) AS n FROM schema_history"));

        Outcome outcome = cli.withDatabase(database.dataSource())
                .withLegacySource(database.dataSource(), database.schemaName())
                .withArguments("--ledger.import.source.roles=" + ABSENT_ROLE).asProcessUser(USER)
                .run(tmp.resolve("secrets"), null, new RecordingOutput(), "retire-legacy");

        assertThat(outcome.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
        assertThat(outcome.code()).contains(ErrorCode.RETIRE_OWN_SCHEMA);
        assertThat(outcome.refusal().orElseThrow().arguments()).containsExactly(database.schemaName());
        assertThat(countOf(database.rows("SELECT COUNT(*) AS n FROM schema_history"))).isEqualTo(history);
    }

    @Test
    @DisplayName("RET-013 another session that holds a lock on a table of the legacy schema (the 1.x service is not stopped) is RETIRE_SESSIONS_CONNECTED (exit 1) naming it; nothing is dropped; the list works again when the session has ended")
    void refusesWhileASessionUsesTheSchema(Cli cli) throws SQLException {
        imported(cli);

        try (Connection other = legacy.admin().getConnection()) {
            other.setAutoCommit(false);
            try (Statement statement = other.createStatement()) {
                statement.execute("SELECT COUNT(*) FROM " + legacy.schema() + ".project");
            }

            Outcome outcome = run(cli, new RecordingOutput(), "retire-legacy");

            assertThat(outcome.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
            assertThat(outcome.code()).contains(ErrorCode.RETIRE_SESSIONS_CONNECTED);
            assertThat(outcome.refusal().orElseThrow().arguments().getFirst())
                    .matches("pid \\d+ uses the schema " + legacy.schema());
            assertLegacyIntact();
            other.rollback();
        }

        assertThat(run(cli, new RecordingOutput(), "retire-legacy").exitCode()).isZero();
    }

    @Test
    @DisplayName("RET-014 an account that does not own the objects (the read-only login of the import) is RETIRE_NOT_OWNER (exit 1) naming objects and their number; nothing is dropped")
    void refusesAnAccountThatDoesNotOwnTheObjects(Cli cli) {
        imported(cli);

        Outcome outcome = runAs(cli, legacy.reader(), ABSENT_ROLE, new RecordingOutput(), "retire-legacy");

        assertThat(outcome.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
        assertThat(outcome.code()).contains(ErrorCode.RETIRE_NOT_OWNER);
        List<String> arguments = outcome.refusal().orElseThrow().arguments();
        assertThat(arguments.getFirst()).contains("_author_ok");
        assertThat(Integer.parseInt(arguments.get(1))).isGreaterThan(LegacyLedger.TABLES.size());
        assertLegacyIntact();
    }

    @Test
    @DisplayName("RET-015 a role of the setting that is the running account stays: the list shows SKIPPED ROLE <account> RUNNING_ACCOUNT, no ROLE line, and after the confirmed run the account still exists")
    void keepsTheRunningAccount(Cli cli) {
        imported(cli);
        String account = (String) legacy.rows("SELECT CURRENT_USER AS account").getFirst().get("account");
        RecordingOutput listed = new RecordingOutput();

        Outcome outcome = runAs(cli, legacy.admin(), account, listed, "retire-legacy");

        assertThat(outcome.exitCode()).isZero();
        assertThat(listed.info()).contains("SKIPPED ROLE " + account + " RUNNING_ACCOUNT").doesNotContain("\nROLE ");
        Outcome removed = runAs(cli, legacy.admin(), account, new RecordingOutput(), "retire-legacy",
                "--confirm=" + digestOf(listed), "--backup-tested");

        assertThat(removed.exitCode()).isZero();
        assertThat(TestDatabase.shared().exists(legacy.schema())).isFalse();
        assertThat(countOf(database.rows("SELECT COUNT(*) AS n FROM pg_catalog.pg_roles WHERE rolname = ?", account)))
                .isEqualTo(1);
    }
}
