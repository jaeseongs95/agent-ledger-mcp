package org.pgledger.migrate1x.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.pgledger.cli.ExitStatus;
import org.pgledger.migrate1x.database.LegacyTable;
import org.pgledger.migrate1x.fixture.LegacyFixtures;
import org.pgledger.migrate1x.fixture.LegacyFixtures.Category;
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
 * REH2-MIG-01 and REH2-MIG-06: the order the README prescribes, on the real wiring over the test PostgreSQL. The installer of
 * 2.0 runs {@code migrate} and {@code setup} (the 14 seed codes are added through the code service, which writes
 * {@code owner_audit} rows {@code CODE_SET}; the driver registration leaves a {@code DRIVER_REGISTERED} row), and then the owner
 * runs {@code import-1x}. The import has to accept what the installer made, and still refuse ledger data, a session key and an
 * earlier import. The migration tool has no {@code setup} (that command belongs to the installer, {@code ledger-cli}), so the
 * state it leaves is arranged here: the schema is migrated by the real migrator ({@link TestLedgerDatabase#migrated()}) and the
 * rows that {@code setup} adds are written with SQL exactly as the code service writes them: the 14 codes of the seed, one audit
 * row {@code CODE_SET} for each, the counter of the audit trail and the revision of the codes.
 */
@ExtendWith({ OutputCaptureExtension.class, ToolExtension.class, EnglishMessages.class })
class ImportAfterSetupTest {

    private static final String USER = "alice";
    private static final String AUDIT = "SELECT actor, operation, subject FROM owner_audit ORDER BY id";
    private static final String ARRANGED_AT = "TIMESTAMP '2026-11-01 00:00:00'";

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

    /**
     * What the installer does before the import, as {@code setup} and the driver registration leave it: the 14 categories of the
     * seed (their sort order differs from the 1.x one, so the import has something to overwrite), an audit row {@code CODE_SET}
     * for each and one {@code DRIVER_REGISTERED}, the audit counter after them and the code revision one higher.
     */
    private static void setup(TestLedgerDatabase target) {
        List<Category> seed = LegacyFixtures.categories();
        long audit = 0;
        for (Category category : seed) {
            List<String> english = LegacyFixtures.englishOf(category);
            target.update("INSERT INTO common_code (code_group, code, name_ko, name_en, description_ko, description_en, "
                    + "sort_order, state, created_by) VALUES ('ISSUE_CATEGORY', ?, ?, ?, ?, ?, ?, 'ACTIVE', ?)",
                    category.code(), category.name(), english.get(0), category.definition(), english.get(1), 10 + audit,
                    "cli:" + USER);
            audit++;
            target.update("INSERT INTO owner_audit (id, occurred_at, actor, operation, subject, detail) VALUES (?, "
                    + ARRANGED_AT + ", ?, 'CODE_SET', ?, 'created')", audit, "cli:" + USER,
                    "ISSUE_CATEGORY/" + category.code());
        }
        audit++;
        target.update("INSERT INTO owner_audit (id, occurred_at, actor, operation, subject, detail) VALUES (?, "
                + ARRANGED_AT + ", ?, 'DRIVER_REGISTERED', 'postgresql', NULL)", audit, "cli:" + USER);
        target.update("UPDATE id_counter SET issued_no = ? WHERE name = 'OWNER_AUDIT'", audit);
        target.update("UPDATE code_revision SET revision = 1 WHERE id = 1");
    }

    private void prepared() {
        setup(database);
        assertThat(count("owner_audit WHERE operation = 'CODE_SET'")).isEqualTo(seedSize());
        assertThat(count("owner_audit WHERE operation = 'DRIVER_REGISTERED'")).isEqualTo(1);
    }

    private static long seedSize() {
        return LegacyFixtures.categories().size();
    }

    private long count(String tableAndCondition) {
        return ((Number) database.rows("SELECT COUNT(*) AS n FROM " + tableAndCondition).getFirst().get("n")).longValue();
    }

    @Test
    @DisplayName("REH2-MIG-01 what setup leaves (14 seed codes with their CODE_SET rows and a DRIVER_REGISTERED row), then import-1x: exit 0, every table has the rows of the 1.x table, owner_audit has the 14 CODE_SET rows and one IMPORT row, and --verify-only afterwards exits 0 and writes nothing")
    void importsAfterSetup(Tool tool) {
        prepared();
        assertThat(seedSize()).isEqualTo(14);
        RecordingOutput output = new RecordingOutput();

        Outcome imported = run(tool, output, "import-1x");

        assertThat(imported.failure()).isNull();
        assertThat(imported.exitCode()).isZero();
        for (LegacyTable table : LegacyTable.values()) {
            if (table != LegacyTable.COMMON_CODE) {
                assertThat(count(table.targetName())).as(table.name()).isEqualTo(legacy.count(table.sourceName()));
            }
        }
        // the seed has the same 14 categories as the 1.x ledger, which has the category OLDBUG besides; the 1.x values
        // overwrite the seed rows (the sort order of the seed was 10 and more, in 1.x it is 0)
        assertThat(count("common_code WHERE code_group = 'ISSUE_CATEGORY'")).isEqualTo(15);
        assertThat(count("common_code WHERE code_group = 'ISSUE_CATEGORY' AND sort_order <> 0")).isZero();
        List<Map<String, Object>> audit = database.rows(AUDIT);
        assertThat(audit.stream().filter(row -> "CODE_SET".equals(row.get("operation")))).hasSize(14);
        assertThat(audit.stream().filter(row -> "DRIVER_REGISTERED".equals(row.get("operation")))).hasSize(1);
        assertThat(audit.stream().filter(row -> "IMPORT".equals(row.get("operation")))).hasSize(1);
        assertThat(audit.getLast()).containsEntry("operation", "IMPORT").containsEntry("actor", "cli:" + USER);
        long auditRows = count("owner_audit");

        RecordingOutput verified = new RecordingOutput();
        Outcome verify = run(tool, verified, "import-1x", "--verify-only");

        assertThat(verify.exitCode()).isZero();
        assertThat(verified.info()).contains("Verified").contains("Nothing was changed");
        assertThat(count("owner_audit")).isEqualTo(auditRows);
    }

    @Test
    @DisplayName("REH2-MIG-06 after setup and import a second import is IMPORT_TARGET_NOT_EMPTY (exit 1) naming owner_audit and project; after setup a row of project is IMPORT_TARGET_NOT_EMPTY naming project and not owner_audit; nothing is added either time")
    void stillRefusesDataAndASecondRun(Tool tool, CapturedOutput captured) {
        prepared();
        assertThat(run(tool, new RecordingOutput(), "import-1x").exitCode()).isZero();
        long history = count("history");
        long audit = count("owner_audit");

        Outcome second = run(tool, new RecordingOutput(), "import-1x");

        assertThat(second.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
        assertThat(second.code()).contains(MigrationError.IMPORT_TARGET_NOT_EMPTY);
        assertThat(second.refusal().orElseThrow().arguments().getFirst()).contains("owner_audit").contains("project");
        assertThat(captured.getAll()).contains("already holds ledger data");
        assertThat(count("history")).isEqualTo(history);
        assertThat(count("owner_audit")).isEqualTo(audit);

        try (TestLedgerDatabase other = TestLedgerDatabase.migrated()) {
            setup(other);
            other.update("INSERT INTO project (id, code, title, version, created_by) VALUES ('own', 'OWN', 'a project of its own', 1, 'owner')");
            Outcome refused = tool.withDatabase(other.dataSource())
                    .withLegacySource(legacy.reader(), legacy.schema()).asProcessUser(USER)
                    .run(tmp.resolve("secrets"), null, new RecordingOutput(), "import-1x");

            assertThat(refused.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
            assertThat(refused.code()).contains(MigrationError.IMPORT_TARGET_NOT_EMPTY);
            assertThat(refused.refusal().orElseThrow().arguments().getFirst()).contains("project").doesNotContain("owner_audit");
            assertThat(((Number) other.rows("SELECT COUNT(*) AS n FROM history").getFirst().get("n")).longValue()).isZero();
        }
    }
}
