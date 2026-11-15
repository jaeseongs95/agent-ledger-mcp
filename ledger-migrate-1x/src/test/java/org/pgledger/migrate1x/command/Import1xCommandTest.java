package org.pgledger.migrate1x.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.ExitStatus;
import org.pgledger.migrate1x.database.LegacyTable;
import org.pgledger.migrate1x.fixture.LegacyFixtures;
import org.pgledger.migrate1x.fixture.LegacyLedger;
import org.pgledger.migrate1x.support.EnglishMessages;
import org.pgledger.migrate1x.support.RecordingOutput;
import org.pgledger.migrate1x.support.TestLedgerDatabase;
import org.pgledger.migrate1x.support.ToolExtension;
import org.pgledger.migrate1x.support.ToolExtension.Outcome;
import org.pgledger.migrate1x.support.ToolExtension.Tool;
import org.pgledger.migrate1x.tool.MigrationError;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * CLI-013 and CLI-044 .. CLI-050: the command {@code import-1x} on the real wiring (the real application context, the
 * real services and repositories, a schema of its own in the PostgreSQL of the test PC that the real migrator built as the new
 * schema, and a second schema of its own in the same database that {@link LegacyFixtures} filled as the 1.x ledger, read
 * through its read-only account). The import itself is
 * MIG-001 .. MIG-032 in {@code LegacyImportTest}; these tests are about the command: its exit codes, what it prints, the
 * audit actor and that no secret is printed. The request receipts of 1.x are not imported, so the twelve other tables are
 * the ones the command names (M1X-003).
 */
@ExtendWith({ OutputCaptureExtension.class, ToolExtension.class, EnglishMessages.class })
class Import1xCommandTest {

    private static final String USER = "alice";
    private static final String AUDIT = "SELECT actor, operation, subject, detail FROM owner_audit ORDER BY id";
    private static final String SOURCE_PASSWORD_NAME = "ledger.import.source.password";
    private static final String SOURCE_PASSWORD = "Src-Pw-7c1-unique-legacy-password-é";

    @TempDir
    Path tmp;

    private TestLedgerDatabase database;
    private LegacyLedger legacy;

    @BeforeEach
    void open() {
        database = TestLedgerDatabase.migrated();
        legacy = LegacyFixtures.rich();
    }

    @AfterEach
    void close() {
        legacy.close();
        database.close();
    }

    private Outcome run(Tool tool, RecordingOutput output, String... args) {
        return tool.withDatabase(database.dataSource()).withLegacySource(legacy.reader(), legacy.schema())
                .asProcessUser(USER).run(tmp.resolve("secrets"), null, output, args);
    }

    private long count(String table) {
        return ((Number) database.rows("SELECT COUNT(*) AS n FROM " + table).getFirst().get("n")).longValue();
    }

    @Test
    @DisplayName("CLI-044 import-1x copies the 1.x ledger: exit 0, one line per table (twelve, no request_receipt) with its rows and sha256, the summary, one owner_audit row IMPORT with the actor cli:<user>; the grants have no session key")
    void importsTheLegacyLedger(Tool tool) {
        RecordingOutput output = new RecordingOutput();

        Outcome result = run(tool, output, "import-1x");

        assertThat(result.exitCode()).isZero();
        assertThat(result.failure()).isNull();
        long rows = 0;
        for (LegacyTable table : LegacyTable.values()) {
            long legacyRows = legacy.count(table.sourceName());
            rows += legacyRows;
            assertThat(output.info()).containsPattern(table.targetName() + ": " + legacyRows + " rows, sha256 [0-9a-f]{64}");
            assertThat(count(table.targetName())).as(table.name()).isEqualTo(legacyRows);
        }
        assertThat(LegacyTable.values()).hasSize(12);
        assertThat(output.info()).contains("Imported " + rows + " rows of 12 tables from the 1.x schema " + legacy.schema())
                .contains("key-register");
        // the request receipts of 1.x are not copied: no line of the report, no row (M1X-003)
        assertThat(output.info()).doesNotContain("request_receipt");
        assertThat(legacy.count("request_receipt")).isEqualTo(3);
        assertThat(count("request_receipt")).isZero();
        // the relation of the fixture has a kind without a direction, so there is nothing to check (CLI-DIR-02)
        assertThat(output.info()).doesNotContain("Check ").doesNotContain("relations_of_unknown_direction");
        List<Map<String, Object>> audit = database.rows(AUDIT);
        assertThat(audit).hasSize(1);
        assertThat(audit.getFirst().get("actor")).isEqualTo("cli:" + USER);
        assertThat(audit.getFirst().get("operation")).isEqualTo("IMPORT");
        assertThat(audit.getFirst().get("subject")).isEqualTo("legacy schema " + legacy.schema());
        assertThat(count("session_key")).isZero();
    }

    @Test
    @DisplayName("CLI-DIR-02 import-1x prints the relations of a directed kind (count, then one id per line) that 1.x stored with the smaller id first, imported as stored; none are printed when there are none; --verify-only prints them too")
    void listsRelationsOfUnknownDirection(Tool tool) {
        LegacyFixtures.withDirectedRelations(legacy);
        RecordingOutput output = new RecordingOutput();

        Outcome result = run(tool, output, "import-1x");

        assertThat(result.exitCode()).isZero();
        assertThat(output.info()).contains("Check 2 relations of a kind that has a direction")
                .contains("relation E2E-D-0007|OLD-D-0001|caused_by").contains("relation OLD-D-0001|OLD-D-0002|blocks")
                .doesNotContain("old_custom").doesNotContain("same_site");
        assertThat(database.rows(AUDIT).getFirst().get("detail").toString()).endsWith("relations_of_unknown_direction=2");
        RecordingOutput verified = new RecordingOutput();
        assertThat(run(tool, verified, "import-1x", "--verify-only").exitCode()).isZero();
        assertThat(verified.info()).contains("Check 2 relations of a kind that has a direction");
    }

    @Test
    @DisplayName("CLI-045 a second import is IMPORT_TARGET_NOT_EMPTY (exit 1) naming the tables; nothing is added, there is still one audit row")
    void refusesASecondRun(Tool tool, CapturedOutput captured) {
        assertThat(run(tool, new RecordingOutput(), "import-1x").exitCode()).isZero();
        long history = count("history");

        Outcome second = run(tool, new RecordingOutput(), "import-1x");

        assertThat(second.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
        assertThat(second.code()).contains(MigrationError.IMPORT_TARGET_NOT_EMPTY);
        assertThat(second.refusal().orElseThrow().arguments().getFirst()).contains("project").contains("history");
        assertThat(captured.getAll()).contains("already holds ledger data");
        assertThat(count("history")).isEqualTo(history);
        assertThat(database.rows(AUDIT)).hasSize(1);
    }

    @Test
    @DisplayName("CLI-046 a 1.x value that cannot be imported is IMPORT_DATA_REFUSED (exit 1): every offending value is printed as table[key].column with its reason, nothing is written (revised by REH3: an id of 1.x in any case and an empty purpose are no refusal; a malformed id, a NOT NULL text that identifies a row and ids that differ only in case are)")
    void listsWhatCannotBeImported(Tool tool, CapturedOutput captured) {
        LegacyFixtures.withEmptyPurpose(legacy);
        LegacyFixtures.withLowerCaseIds(legacy);
        LegacyFixtures.withMalformedIds(legacy);
        LegacyFixtures.withEmptyIdentifyingTexts(legacy);
        RecordingOutput output = new RecordingOutput();

        Outcome result = run(tool, output, "import-1x");

        assertThat(result.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
        assertThat(result.code()).contains(MigrationError.IMPORT_DATA_REFUSED);
        assertThat(output.info()).contains("task[OLD T SPACE].id: MUST_BE_ID 120")
                .contains("task[" + LegacyFixtures.TASK_ID_121 + "].id: MUST_BE_ID 120")
                .contains("release_condition[OLD-C-NORELEASE].release_name: REQUIRED")
                .contains("defect_fix[30].repo: REQUIRED")
                .doesNotContain("OLD-T-UNKNOWN").doesNotContain("old-t-lower").doesNotContain("old-c-lower");
        assertThat(result.refusal().orElseThrow().arguments()).containsExactly("5");
        assertThat(captured.getAll()).contains("cannot be imported as they are");
        assertThat(count("project")).isZero();
        assertThat(count("history")).isZero();
        assertThat(database.rows(AUDIT)).isEmpty();
    }

    @Test
    @DisplayName("CLI-047 a 1.x database without installer steps is IMPORT_SOURCE_INCOMPLETE (exit 1) naming them, a command without the 1.x connection is IMPORT_SOURCE_NOT_CONFIGURED (exit 1); nothing is written")
    void refusesAnIncompleteOrMissingSource(Tool tool) {
        try (LegacyLedger older = LegacyLedger.create(Set.of("schema"))) {
            Outcome incomplete = tool.withDatabase(database.dataSource()).withLegacySource(older.reader(), older.schema())
                    .asProcessUser(USER).run(tmp.resolve("secrets"), null, new RecordingOutput(), "import-1x");

            assertThat(incomplete.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
            assertThat(incomplete.code()).contains(MigrationError.IMPORT_SOURCE_INCOMPLETE);
            assertThat(incomplete.refusal().orElseThrow().arguments().getFirst()).contains("defect_case")
                    .contains("common_code").contains("created_by");
        }
        Outcome unconfigured = tool.withDatabase(database.dataSource()).asProcessUser(USER).run(tmp.resolve("secrets"), null,
                new RecordingOutput(), "import-1x");

        assertThat(unconfigured.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
        assertThat(unconfigured.code()).contains(MigrationError.IMPORT_SOURCE_NOT_CONFIGURED);
        assertThat(count("project")).isZero();
        assertThat(database.rows(AUDIT)).isEmpty();
    }

    @Test
    @DisplayName("CLI-048 --verify-only repeats the comparison and writes nothing: exit 0 after an import; after a row of the new schema was changed it is IMPORT_VERIFY_FAILED (exit 1) naming the table, both row counts and both digests")
    void verifiesAnImport(Tool tool, CapturedOutput captured) {
        assertThat(run(tool, new RecordingOutput(), "import-1x").exitCode()).isZero();
        RecordingOutput verified = new RecordingOutput();

        Outcome ok = run(tool, verified, "import-1x", "--verify-only");

        assertThat(ok.exitCode()).isZero();
        assertThat(verified.info()).contains("Verified").contains("of 12 tables").contains("Nothing was changed");
        assertThat(database.rows(AUDIT)).hasSize(1);

        database.update("UPDATE defect SET title = 'tampered' WHERE id = 'OLD-D-0001'");
        RecordingOutput failed = new RecordingOutput();
        Outcome tampered = run(tool, failed, "import-1x", "--verify-only");

        assertThat(tampered.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
        assertThat(tampered.code()).contains(MigrationError.IMPORT_VERIFY_FAILED);
        List<String> arguments = tampered.refusal().orElseThrow().arguments();
        assertThat(arguments).hasSize(5);
        assertThat(arguments.get(0)).isEqualTo("defect");
        assertThat(arguments.get(1)).isEqualTo(arguments.get(2));
        assertThat(arguments.get(3)).hasSize(64).isNotEqualTo(arguments.get(4));
        assertThat(failed.info()).contains("Table defect: " + arguments.get(1) + " 1.x rows but " + arguments.get(2)
                + " rows in the new schema; sha256 " + arguments.get(3) + " against " + arguments.get(4));
        assertThat(captured.getAll()).contains("differ in the table defect");
        assertThat(database.rows(AUDIT)).hasSize(1);
    }

    @Test
    @DisplayName("CLI-049 import-1x takes no operand and only the flag --verify-only: a surplus operand is TOO_MANY_ARGUMENTS, --verify-only=<value> is INVALID_OPTION_VALUE, another option is UNKNOWN_OPTION (exit 2 each); nothing is written")
    void refusesWrongCommandLines(Tool tool) {
        assertThat(run(tool, new RecordingOutput(), "import-1x", "extra").code()).contains(ErrorCode.TOO_MANY_ARGUMENTS);
        assertThat(run(tool, new RecordingOutput(), "import-1x", "--verify-only=yes").code())
                .contains(ErrorCode.INVALID_OPTION_VALUE);
        assertThat(run(tool, new RecordingOutput(), "import-1x", "--force").code()).contains(ErrorCode.UNKNOWN_OPTION);
        // SEC-025: --stdin gives the password of --owner-account; without the account it is refused
        assertThat(run(tool, new RecordingOutput(), "import-1x", "--stdin").code()).contains(ErrorCode.INVALID_OPTION_VALUE);
        for (String line : List.of("extra", "--verify-only=yes", "--force", "--stdin")) {
            assertThat(run(tool, new RecordingOutput(), "import-1x", line).exitCode()).isEqualTo(ExitStatus.USAGE.code());
        }
        assertThat(count("project")).isZero();
    }

    @Test
    @DisplayName("CLI-050 (MIG-020) the password of the 1.x connection is the secret ledger.import.source.password: the secret store is part of the environment, and neither the output nor the log shows it, in a successful import, in a refusal and with --debug")
    void theSourcePasswordIsASecret(Tool tool, CapturedOutput captured) throws IOException {
        // the secret store of the tool has no command to set a secret (the installer of 2.0 and ledger-cli do): the file of the
        // secret is what the configtree reads
        Path secrets = Files.createDirectories(tmp.resolve("secrets"));
        Files.writeString(secrets.resolve(SOURCE_PASSWORD_NAME), SOURCE_PASSWORD, StandardCharsets.UTF_8);
        RecordingOutput output = new RecordingOutput();

        Outcome imported = run(tool, output, "import-1x");
        Outcome refused = run(tool, output, "import-1x");
        Outcome debug = run(tool, output, "import-1x", "--verify-only", "--debug");

        assertThat(imported.exitCode()).isZero();
        assertThat(refused.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
        assertThat(debug.exitCode()).isZero();
        assertThat(imported.context().getEnvironment().getProperty(SOURCE_PASSWORD_NAME)).isEqualTo(SOURCE_PASSWORD);
        assertThat(captured.getAll()).doesNotContain(SOURCE_PASSWORD);
        assertThat(output.info()).doesNotContain(SOURCE_PASSWORD);
    }
}
