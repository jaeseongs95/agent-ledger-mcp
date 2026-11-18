package org.pgledger.migrate1x.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.application.read.DetailService;
import org.pgledger.application.read.HistoryService;
import org.pgledger.application.read.HistoryService.HistoryQuery;
import org.pgledger.application.support.ConnectionLog;
import org.pgledger.application.support.Expect;
import org.pgledger.application.support.Fields;
import org.pgledger.application.support.LedgerAppContext;
import org.pgledger.application.support.Scenario;
import org.pgledger.application.admin.GrantService;
import org.pgledger.application.admin.GrantService.GrantCommand;
import org.pgledger.application.admin.CommonCodeService;
import org.pgledger.application.admin.CommonCodeService.CodeCommand;
import org.pgledger.application.admin.ProjectService;
import org.pgledger.application.admin.ProjectService.ProjectCommand;
import org.pgledger.application.write.RecordCaseCommand;
import org.pgledger.application.write.RecordCaseService;
import org.pgledger.application.write.RecordDeliverableCommand;
import org.pgledger.application.write.RecordDeliverableService;
import org.pgledger.application.write.ReviewService;
import org.pgledger.application.write.SaveCommand;
import org.pgledger.application.write.SaveService;
import org.pgledger.application.write.TransitionCommand;
import org.pgledger.application.write.TransitionService;
import org.pgledger.application.write.WhoamiService;
import org.pgledger.application.write.WhoamiService.GrantView;
import org.pgledger.application.write.WriteResult.RecordCaseResult;
import org.pgledger.application.write.WriteResult.RecordDeliverableResult;
import org.pgledger.application.write.WriteResult.SaveResult;
import org.pgledger.application.write.WriteResult.TransitionResult;
import org.pgledger.apptest.TestKeyRing;
import org.pgledger.database.repository.Counter;
import org.pgledger.database.repository.IdAllocator;
import org.pgledger.database.time.UtcTime;
import org.pgledger.domain.context.ToolScope;
import org.pgledger.domain.error.ConflictCode;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;
import org.pgledger.domain.error.LedgerException;
import org.pgledger.domain.error.LookupCode;
import org.pgledger.domain.error.TransitionCode;
import org.pgledger.domain.error.TrustCode;
import org.pgledger.domain.error.ValidationCode;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationIssue;
import org.pgledger.domain.error.ValidationReason;
import org.pgledger.domain.rule.Channel;
import org.pgledger.domain.value.ProjectCode;
import org.pgledger.migrate1x.database.LegacySourceRepository;
import org.pgledger.migrate1x.database.LegacyTable;
import org.pgledger.migrate1x.fixture.LegacyFixtures;
import org.pgledger.migrate1x.fixture.LegacyFixtures.Category;
import org.pgledger.migrate1x.fixture.LegacyLedger;
import org.pgledger.migrate1x.fixture.MisbehavingTarget;
import org.pgledger.migrate1x.support.ImportRunners;
import org.pgledger.testing.TestOwners;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * MIG-001 .. MIG-024 and MIG-027 .. MIG-033, M1X-003 (MIG-004 is replaced by it: the 1.x request receipts are not imported)
 * and M1X-050 (the vocabulary of the imported rows, MIG-003) on the PostgreSQL of the test PC: the 1.x import service in the
 * real application context (a freshly migrated schema, the real repositories and services, the real migrator) with the 1.x
 * ledger of {@link LegacyFixtures} (a schema of its own, {@code timestamptz}, {@code uuid} and {@code jsonb} columns as in the
 * real 1.x) as its source, read by the read-only account. Every test reads the tables of both sides with its own queries.
 * MIG-025 is test server stage work and is not here.
 */
class LegacyImportTest {

    private static final String SEED = LegacyFixtures.SEED;
    private static final String RELINK = LegacyFixtures.RELINK;
    /** Reads JSON text: the legacy columns are jsonb, which PostgreSQL prints in its own normalised form. */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The legacy ledger and the application context that imports it. */
    private record Rig(LegacyLedger legacy, LedgerAppContext ctx) {

        LegacyImportService service() {
            return ctx.bean(LegacyImportService.class);
        }

        ImportReport importAll() {
            return service().importLegacy(TestOwners.OWNER);
        }

        long count(String sql, Object... arguments) {
            return ctx.jdbc().sql(sql).params(Arrays.asList(arguments)).query(Long.class).single();
        }

        List<Map<String, Object>> rows(String sql) {
            return ctx.jdbc().sql(sql).query().listOfRows();
        }

        Instant instant(String sql, Object... arguments) {
            return ctx.jdbc().sql(sql).params(Arrays.asList(arguments)).query((rs, n) -> UtcTime.read(rs, "t")).list().getFirst();
        }

        /** The number of rows of every table of the new schema that is copied, plus the tables only it has. */
        long ledgerRows() {
            long rows = 0;
            for (LegacyTable table : LegacyTable.values()) {
                rows += table.targetMayHoldOtherRows() ? 0 : count("SELECT COUNT(*) FROM " + table.targetName());
            }
            return rows + count("SELECT COUNT(*) FROM spec") + count("SELECT COUNT(*) FROM issue_spec")
                    + count("SELECT COUNT(*) FROM spec_task") + count("SELECT COUNT(*) FROM session_key")
                    + count("SELECT COUNT(*) FROM session_token") + count("SELECT COUNT(*) FROM owner_audit");
        }
    }

    private static void withRig(LegacyLedger legacy, DataSource source, Consumer<Rig> body, Class<?>... configurations) {
        try (legacy) {
            ImportRunners.importing(legacy, source, ctx -> body.accept(new Rig(legacy, ctx)), configurations);
        }
    }

    private static void withRig(LegacyLedger legacy, Consumer<Rig> body, Class<?>... configurations) {
        withRig(legacy, legacy.reader(), body, configurations);
    }

    private static void withRich(Consumer<Rig> body, Class<?>... configurations) {
        withRig(LegacyFixtures.rich(), body, configurations);
    }

    private static String text(Map<String, Object> row, String column) {
        return String.valueOf(row.get(column));
    }

    /** The two texts are the same JSON value (whitespace and the order of the keys do not matter). */
    private static void assertSameJson(String actual, String expected) {
        assertThat((Object) JSON.readTree(actual)).isEqualTo(JSON.readTree(expected));
    }

    private static <T> T asTool(String tool, Supplier<T> call) {
        return ScopedValue.where(ToolScope.CURRENT, tool).where(ToolScope.ARGUMENTS_DIGEST, TestKeyRing.DIRECT_DIGEST)
                .call(call::get);
    }

    private static Map<String, String> issueFields(String project, String title) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("project_id", project);
        fields.put("title", title);
        fields.put("category", "OLDBUG");
        fields.put("severity", "P2");
        fields.put("symptom", "symptom of " + title);
        fields.put("root_cause", "cause of " + title);
        return fields;
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The self-delimiting text of text values that the digest of the import documents: {@code s<length>:<text>;}. */
    private static String serialised(List<String> values) {
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            out.append('s').append(value.length()).append(':').append(value).append(';');
        }
        return out.toString();
    }

    /** The digest of a table of text columns, written from the documentation alone: the key is the first two columns. */
    private static String documentedDigest(List<List<String>> rows) {
        List<String> lines = new ArrayList<>();
        for (List<String> row : rows) {
            lines.add(serialised(row.subList(0, 2)) + "\t" + sha256(serialised(row)) + "\n");
        }
        Collections.sort(lines);
        return sha256(String.join("", lines));
    }

    private static BigDecimal epoch(Instant instant) {
        return new BigDecimal(instant.getEpochSecond()).add(BigDecimal.valueOf(instant.getNano(), 9));
    }

    /** Everything a legacy table holds, as text, for the check that the import changed nothing. */
    private static String contents(LegacyLedger legacy) {
        StringBuilder all = new StringBuilder();
        for (String table : LegacyLedger.TABLES) {
            all.append(table).append(legacy.rows("SELECT * FROM " + legacy.schema() + "." + table + " ORDER BY 1, 2"));
        }
        return sha256(all.toString());
    }

    // ---------------------------------------------------------------------------------------------- MIG-001 .. 004

    @Test
    @DisplayName("MIG-001 every table of the 1.x ledger that is copied (twelve: the request receipts are not, M1X-003) arrives with its row count, no extra rows; the report has one line per table")
    void mig001_everyRowIsCopied() {
        withRich(rig -> {
            ImportReport report = rig.importAll();

            long total = 0;
            for (LegacyTable table : LegacyTable.values()) {
                long legacyRows = rig.legacy().count(table.sourceName());
                long expectedTargetRows = table == LegacyTable.COMMON_CODE ? legacyRows + 9 : legacyRows;
                assertThat(rig.count("SELECT COUNT(*) FROM " + table.targetName())).as(table.name())
                        .isEqualTo(expectedTargetRows);
                total += legacyRows;
            }
            assertThat(LegacyTable.values()).hasSize(12);
            assertThat(report.tables()).hasSize(12).hasSize(LegacyTable.values().length).allMatch(TableCheck::matches);
            assertThat(report.rows()).isEqualTo(total);
            assertThat(report.schema()).isEqualTo(rig.legacy().schema());
        });
    }

    @Test
    @DisplayName("MIG-002 the digest of every table is equal on both sides, follows the documented format (checked for defect_task by an independent computation), and the ids of history, fix, case and grant are preserved")
    void mig002_digestsAndIds() {
        withRich(rig -> {
            ImportReport report = rig.importAll();

            for (TableCheck check : report.tables()) {
                assertThat(check.sourceDigest()).as(check.table().name()).isEqualTo(check.targetDigest());
            }
            List<List<String>> legacyRows = rig.legacy().rows("SELECT defect_id, task_id, created_by FROM "
                    + rig.legacy().schema() + ".defect_task")
                    .stream().map(row -> List.of(text(row, "defect_id"), text(row, "task_id"), text(row, "created_by"))).toList();
            TableCheck defectTask = report.tables().stream().filter(check -> check.table() == LegacyTable.DEFECT_TASK)
                    .findFirst().orElseThrow();
            assertThat(defectTask.sourceDigest().hex()).isEqualTo(documentedDigest(legacyRows));
            assertThat(defectTask.targetDigest().hex()).isEqualTo(documentedDigest(legacyRows));
            for (LegacyTable table : List.of(LegacyTable.HISTORY, LegacyTable.DEFECT_FIX, LegacyTable.DEFECT_CASE,
                    LegacyTable.SESSION_GRANT)) {
                Set<Long> legacyIds = new HashSet<>(rig.legacy().rows("SELECT id FROM " + rig.legacy().schema() + "."
                        + table.sourceName()).stream().map(row -> ((Number) row.get("id")).longValue()).toList());
                Set<Long> ids = new HashSet<>(rig.ctx().jdbc().sql("SELECT id FROM " + table.targetName())
                        .query(Long.class).list());
                assertThat(ids).as(table.name()).isEqualTo(legacyIds);
            }
        });
    }

    private static final Map<String, String> HISTORY_ENTITY_MAP = Map.of(
            "defect", "ISSUE", "defect_fix", "DELIVERABLE", "defect_case", "ISSUE_CASE",
            "defect_task", "ISSUE_TASK", "defect_relation", "ISSUE_RELATION",
            "condition", "CRITERION", "condition_link", "CRITERION_LINK", "task", "TASK");

    private static String mappedHistoryState(String legacyState) {
        return "SOURCE_FIXED".equals(legacyState) ? "RESOLVED" : legacyState;
    }

    @Test
    @DisplayName("MIG-003 / M1X-050 every field of every history row is equal (the JSON text of changes, source_ref from legacy_ref, login, the 1.x request_id as operation_key, corrects_id), the 1.x operations and assurances arrive under the imported vocabulary (LEGACY_ becomes IMPORTED_), the entity is the 2.0 upper-case name, and before_data is NULL on every row")
    void mig003_historyFields() {
        withRich(rig -> {
            rig.importAll();

            List<Map<String, Object>> legacyRows = rig.legacy().rows("SELECT id, author_id, login, actor_assurance, entity, "
                    + "entity_id, op, from_state, to_state, changes, reason, evidence_uri, evidence_sha256, corrects_id, "
                    + "request_id, legacy_ref FROM " + rig.legacy().schema() + ".history ORDER BY id");
            List<Map<String, Object>> rows = rig.rows("SELECT id, author_id, login, actor_assurance, entity, entity_id, op, "
                    + "from_state, to_state, changes, before_data, reason, evidence_uri, evidence_sha256, corrects_id, "
                    + "operation_key, source_ref FROM history ORDER BY id");

            assertThat(rows).hasSameSizeAs(legacyRows).hasSize(10);
            for (int i = 0; i < rows.size(); i++) {
                Map<String, Object> legacy = legacyRows.get(i);
                Map<String, Object> row = rows.get(i);
                for (String column : List.of("id", "login", "entity_id", "changes",
                        "reason", "evidence_uri", "evidence_sha256", "corrects_id")) {
                    assertThat(text(row, column)).as("history %s of row %d", column, i).isEqualTo(text(legacy, column));
                }
                assertThat(text(row, "from_state")).as("history from_state of row %d", i)
                        .isEqualTo(mappedHistoryState(text(legacy, "from_state")));
                assertThat(text(row, "to_state")).as("history to_state of row %d", i)
                        .isEqualTo(mappedHistoryState(text(legacy, "to_state")));
                // the 1.x column request_id is the column operation_key of 2.0 (OPK-018 checks it on its own)
                assertThat(text(row, "operation_key")).as("history operation_key of row %d", i)
                        .isEqualTo(text(legacy, "request_id"));
                // the actor id loses the prefix agent: that 1.x put before a session id (M1X-050, A2); nothing else of it changes
                assertThat(text(row, "author_id")).as("history author_id of row %d", i)
                        .isEqualTo(withoutAgentPrefix(text(legacy, "author_id")));
                // the vocabulary of the imported rows (M1X-050): 1.x wrote LEGACY_LOGIN, LEGACY_NOTE ..., 2.0 holds IMPORTED_*
                assertThat(text(row, "actor_assurance")).as("assurance of row %d", i).isEqualTo(imported(text(legacy, "actor_assurance")));
                assertThat(text(row, "op")).as("op of row %d", i).isEqualTo(imported(text(legacy, "op")));
                assertThat(text(row, "source_ref")).as("source_ref of row %d", i).isEqualTo(text(legacy, "legacy_ref"));
                assertThat(text(row, "entity")).isEqualTo(HISTORY_ENTITY_MAP.get(text(legacy, "entity")));
                assertThat(row.get("before_data")).as("before_data of row %d", i).isNull();
            }
            assertThat(rows.stream().map(row -> text(row, "op")).toList()).contains("IMPORT", "IMPORTED_NOTE", "IMPORTED_CHANGE",
                    "CORRECT").noneMatch(op -> op.startsWith("LEGACY_"));
            assertThat(rows.stream().map(row -> text(row, "actor_assurance")).distinct().toList()).contains("IMPORTED_LOGIN",
                    "IMPORTED_UNKNOWN", "IMPORTED_SHARED").noneMatch(assurance -> assurance.startsWith("LEGACY_"));
        });
    }

    @Test
    @DisplayName("OPK-018 the 1.x history.request_id of every row is copied into history.operation_key with an equal value (the 2.0 table has no column request_id), and no 1.x receipt is imported")
    void opk018_requestIdBecomesOperationKey() {
        withRich(rig -> {
            rig.importAll();

            List<String> legacyKeys = rig.legacy().rows("SELECT id, request_id FROM " + rig.legacy().schema()
                    + ".history ORDER BY id").stream().map(row -> text(row, "request_id")).toList();
            List<String> keys = rig.rows("SELECT id, operation_key FROM history ORDER BY id").stream()
                    .map(row -> text(row, "operation_key")).toList();

            assertThat(legacyKeys).hasSize(10).doesNotContainNull();
            assertThat(keys).containsExactlyElementsOf(legacyKeys);
            assertThat(rig.ctx().jdbc().sql("SELECT * FROM history").query().rowSet().getMetaData().getColumnNames())
                    .contains("operation_key").doesNotContain("request_id");
            assertThat(rig.count("SELECT COUNT(*) FROM request_receipt")).isZero();
        });
    }

    /** The actor id as 2.0 holds it: 1.x wrote {@code agent:<vendor>:<session>} for a session, 2.0 {@code <vendor>:<session>}. */
    private static String withoutAgentPrefix(String actor) {
        return actor.startsWith("agent:") ? actor.substring("agent:".length()) : actor;
    }

    @Test
    @DisplayName("M1X-050 (A2) every actor column of every table arrives without the prefix agent: (author_id, recorded_by, owner_actor_id, created_by, granted_by), nothing else of it changes, and the digests still agree; a session that recorded the fix as agent:<id> in 1.x is the same session as <id> in 2.0: its VERIFIED is refused with SAME_SESSION")
    void m1x050_actorIdsLoseTheAgentPrefix() {
        withRich(rig -> {
            ImportReport report = rig.importAll();

            assertThat(report.tables()).allSatisfy(check -> assertThat(check.matches()).isTrue());
            for (String[] column : new String[][] { { "project", "created_by" }, { "common_code", "created_by" },
                    { "task", "owner_actor_id" }, { "task", "created_by" }, { "issue", "owner_actor_id" },
                    { "issue", "created_by" }, { "deliverable", "recorded_by" }, { "issue_case", "recorded_by" },
                    { "issue_task", "created_by" }, { "issue_relation", "created_by" }, { "milestone_criterion", "created_by" },
                    { "criterion_link", "created_by" }, { "session_grant", "author_id" }, { "session_grant", "granted_by" },
                    { "history", "author_id" } }) {
                assertThat(rig.count("SELECT COUNT(*) FROM " + column[0] + " WHERE " + column[1] + " LIKE 'agent:%'"))
                        .as("%s.%s", column[0], column[1]).isZero();
            }
            // the imported fix 9 was recorded by agent:old-1, the fix 3 by agent:claude:e2e-seed (the fixture)
            assertThat(rig.rows("SELECT id, recorded_by FROM deliverable ORDER BY id").stream()
                    .map(row -> text(row, "id") + "=" + text(row, "recorded_by")).toList())
                    .contains("3=" + SEED, "9=old-1");

            LedgerAppContext ctx = rig.ctx();
            ctx.enroll(SEED);
            ctx.bean(GrantService.class).grant(new GrantCommand(SEED, "e2e", List.of("verify"), null, null), TestOwners.OWNER);
            // E2E-D-0007 is RESOLVED and its only deliverable is the one of this session
            Expect.failure(() -> ctx.bean(ReviewService.class).review(SEED, new TransitionCommand(Scenario.uuid(), "issue", "E2E-D-0007",
                    2L, "VERIFIED", Scenario.REASON, Scenario.EVIDENCE, null, null)), TransitionCode.SAME_SESSION);
        });
    }

    /** The name 2.0 holds for a value that 1.x wrote with its LEGACY_ prefix (M1X-050); any other value is the same. */
    private static String imported(String oneXValue) {
        return oneXValue.startsWith("LEGACY_") ? "IMPORTED_" + oneXValue.substring("LEGACY_".length()) : oneXValue;
    }

    @Test
    @DisplayName("M1X-003 (replaces MIG-004; reworded for operationKey) the request receipts of 1.x are not imported, so no 1.x answer can be replayed: the old key is refused as an unknown argument by 2.0, a lost 1.x answer cannot cause a second write; the new request_receipt (keyed by operation_key) is empty after the import, the report names twelve tables and none of them is request_receipt, the table has no column origin, and the comparison of the other twelve is equal")
    void m1x003_receiptsAreNotImported() {
        withRich(rig -> {
            assertThat(rig.legacy().count("request_receipt")).as("the 1.x fixture holds receipts").isEqualTo(3);

            ImportReport report = rig.importAll();

            assertThat(rig.count("SELECT COUNT(*) FROM request_receipt")).isZero();
            assertThat(report.tables()).hasSize(12).allMatch(TableCheck::matches);
            assertThat(report.tables().stream().map(check -> check.table().targetName()).toList())
                    .doesNotContain("request_receipt");
            assertThat(report.tables().stream().map(check -> check.table().sourceName()).toList())
                    .doesNotContain("request_receipt");
            assertThat(report.detail()).doesNotContain("request_receipt");
            // the column list of the table, from the metadata of a query on it: no column origin (it held LEGACY or NATIVE)
            assertThat(rig.ctx().jdbc().sql("SELECT * FROM request_receipt").query().rowSet().getMetaData().getColumnNames())
                    .containsExactlyInAnyOrder("operation_key", "operation", "payload_sha256", "author_id", "login", "response",
                            "created_at")
                    .doesNotContain("origin", "request_id");
            // the verification does not look at the receipts either, and the 1.x receipts are still there (nothing is changed)
            assertThat(rig.service().verify().tables()).hasSize(12).allMatch(TableCheck::matches);
            assertThat(rig.legacy().count("request_receipt")).isEqualTo(3);
        });
    }

    // ---------------------------------------------------------------------------------------------- MIG-005 .. 007

    @Test
    @DisplayName("MIG-005 (changed by import: no session key is imported) live grants arrive with the active_key of the new schema (author|project|scope), the revoked one with revoked:<id>, the expired one is still live-keyed but not live; counts equal, last_used_at and session_key empty; whoami shows only the live grants once the key is registered")
    void mig005_grants() {
        withRich(rig -> {
            rig.importAll();

            assertThat(rig.count("SELECT COUNT(*) FROM session_grant")).isEqualTo(rig.legacy().count("session_grant")).isEqualTo(5);
            assertThat(rig.ctx().jdbc().sql("SELECT active_key FROM session_grant ORDER BY id").query(String.class).list())
                    .containsExactly("claude:e2e-seed|old|issue", "claude:e2e-seed|old|task", "revoked:4",
                            "gemini:expired|old|verify", "claude:e2e-seed|e2e|issue");
            assertThat(rig.ctx().jdbc().sql("SELECT grant_scope FROM session_grant ORDER BY id").query(String.class).list())
                    .containsExactly("ISSUE", "TASK", "DELIVERABLE", "VERIFY", "ISSUE");
            assertThat(rig.count("SELECT COUNT(*) FROM session_grant WHERE last_used_at IS NOT NULL")).isZero();
            assertThat(rig.count("SELECT COUNT(*) FROM session_grant WHERE granted_by <> 'user:owner'")).isZero();
            assertThat(rig.count("SELECT COUNT(*) FROM session_key")).isZero();

            rig.ctx().enroll(SEED);
            WhoamiService whoami = rig.ctx().bean(WhoamiService.class);
            assertThat(whoami.whoami(SEED, Channel.AUTHOR).grants()).extracting(GrantView::project, GrantView::scope)
                    .containsExactlyInAnyOrder(tuple("old", "issue"), tuple("old", "task"), tuple("e2e", "issue"));
            rig.ctx().enroll(RELINK);
            assertThat(whoami.whoami(RELINK, Channel.AUTHOR).grants()).isEmpty();
            rig.ctx().enroll("gemini:expired");
            assertThat(whoami.whoami("gemini:expired", Channel.AUTHOR).grants()).isEmpty();
        });
    }

    @Test
    @DisplayName("MIG-030 after the import a granted author that has no registered key is refused with ATTESTATION_SIGNATURE_INVALID (nothing written); after its key is registered (what key-register does) the same grant writes")
    void mig030_grantsNeedTheKey() {
        withRich(rig -> {
            rig.importAll();
            SaveService save = rig.ctx().bean(SaveService.class);
            String operationKey = Scenario.uuid();
            SaveCommand command = new SaveCommand(operationKey, "issue", null, 0L, Fields.of(issueFields("old", "after the import")),
                    Scenario.REASON);
            String token = TestKeyRing.token(TestKeyRing.newPair(), SEED, "ledger_issue_save", operationKey,
                    rig.ctx().clock().instant());

            LedgerException refused = Expect.failure(() -> asTool("ledger_issue_save", () -> save.save(token, command)),
                    TrustCode.ATTESTATION_SIGNATURE_INVALID);

            assertThat(refused.args()).isEmpty();
            assertThat(rig.count("SELECT COUNT(*) FROM issue WHERE title = ?", "after the import")).isZero();
            assertThat(rig.count("SELECT COUNT(*) FROM request_receipt WHERE operation_key = ?", operationKey)).isZero();

            rig.ctx().enroll(SEED);
            SaveResult saved = save.save(SEED, new SaveCommand(Scenario.uuid(), "issue", null, 0L, Fields.of(issueFields("old", "after the key")),
                    Scenario.REASON));
            assertThat(saved.author()).isEqualTo(SEED);
            assertThat(saved.id()).isEqualTo("OLD-I-0004");
        });
    }

    @Test
    @DisplayName("MIG-006 created_by is copied as it is in every table that has it (creators of defects, tasks, conditions and links, user:owner, unknown:legacy, the creator of a re-linked row)")
    void mig006_createdBy() {
        withRich(rig -> {
            rig.importAll();

            for (LegacyTable table : List.of(LegacyTable.PROJECT, LegacyTable.COMMON_CODE, LegacyTable.TASK,
                    LegacyTable.DEFECT, LegacyTable.DEFECT_TASK, LegacyTable.DEFECT_RELATION, LegacyTable.RELEASE_CONDITION,
                    LegacyTable.CONDITION_LINK)) {
                List<String> legacy = rig.legacy().rows("SELECT created_by FROM " + rig.legacy().schema() + "."
                        + table.sourceName()).stream().map(row -> text(row, "created_by")).toList();
                String sql = table == LegacyTable.COMMON_CODE
                        ? "SELECT created_by FROM common_code WHERE code_group <> 'DELIVERABLE_KIND' AND code <> 'same_work_unit'"
                        : "SELECT created_by FROM " + table.targetName();
                List<String> imported = rig.ctx().jdbc().sql(sql)
                        .query(String.class).list();
                assertThat(imported).as(table.name()).containsExactlyInAnyOrderElementsOf(legacy);
            }
            assertThat(rig.count("SELECT COUNT(*) FROM milestone_criterion WHERE id = 'OLD-C-3' AND created_by = 'unknown:legacy'"))
                    .isEqualTo(1);
            assertThat(rig.count("SELECT COUNT(*) FROM criterion_link WHERE criterion_id = 'OLD-C-2' AND "
                    + "created_by = 'codex:e2e-relink'")).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("MIG-007 the counters continue after the highest legacy numbers: project issue counter = highest <CODE>-D-NNNN, task counter = highest <CODE>-T-NNNN (0 when none), history, deliverable, case and grant counters = highest id; the next numbers issued collide with nothing")
    void mig007_counters() {
        withRich(rig -> {
            rig.importAll();

            assertThat(rig.count("SELECT next_issue_no FROM project WHERE id = 'old'")).isEqualTo(3);
            assertThat(rig.count("SELECT next_spec_no FROM project WHERE id = 'old'")).isZero();
            assertThat(rig.count("SELECT next_task_no FROM project WHERE id = 'old'")).isZero();
            assertThat(rig.count("SELECT next_issue_no FROM project WHERE id = 'e2e'")).isEqualTo(100);
            assertThat(rig.count("SELECT next_spec_no FROM project WHERE id = 'e2e'")).isZero();
            assertThat(rig.count("SELECT next_task_no FROM project WHERE id = 'e2e'")).isEqualTo(3);
            for (Map.Entry<String, Long> counter : Map.of("HISTORY", 57L, "DELIVERABLE", 9L, "ISSUE_CASE", 5L,
                    "SESSION_GRANT", 8L, "SESSION_KEY", 0L, "SESSION_TOKEN", 0L, "OWNER_AUDIT", 1L).entrySet()) {
                assertThat(rig.count("SELECT issued_no FROM id_counter WHERE name = ?", counter.getKey()))
                        .as(counter.getKey()).isEqualTo(counter.getValue());
            }
            IdAllocator ids = rig.ctx().bean(IdAllocator.class);
            assertThat(rig.ctx().tx(() -> ids.nextIssueNo(ProjectCode.of("OLD")))).isEqualTo(4);
            assertThat(rig.ctx().tx(() -> ids.nextIssueNo(ProjectCode.of("E2E")))).isEqualTo(101);
            assertThat(rig.ctx().tx(() -> ids.nextTaskNo(ProjectCode.of("E2E")))).isEqualTo(4);
            assertThat(rig.ctx().tx(() -> ids.nextTaskNo(ProjectCode.of("OLD")))).isEqualTo(1);
            assertThat(rig.ctx().tx(() -> ids.next(Counter.HISTORY))).isEqualTo(58);
            assertThat(rig.ctx().tx(() -> ids.next(Counter.DELIVERABLE))).isEqualTo(10);
        });
    }

    // ---------------------------------------------------------------------------------------------- MIG-008 .. 010

    @Test
    @DisplayName("MIG-008 the legacy ledger is untouched: the content digest of every table, the object list and the _migration rows are equal before and after; the import reads in a read-only transaction of isolation REPEATABLE READ; a write through the read-only account fails")
    void mig008_sourceUntouched() {
        LegacyLedger legacy = LegacyFixtures.rich();
        ConnectionLog log = new ConnectionLog(legacy.reader());
        String before = contents(legacy);
        List<String> objectsBefore = legacy.objects();
        withRig(legacy, log, rig -> {
            rig.importAll();
            rig.service().verify();
            assertThat(contents(legacy)).isEqualTo(before);
            assertThat(legacy.objects()).isEqualTo(objectsBefore);
            assertThat(legacy.rows("SELECT step FROM " + legacy.schema() + "._migration ORDER BY step").stream()
                    .map(row -> text(row, "step")).toList()).containsExactlyInAnyOrderElementsOf(LegacyLedger.ALL_STEPS);
            assertThat(log.calls()).contains("setReadOnly[true]", "setTransactionIsolation[4]", "commit[]");
            assertThat(log.calls().indexOf("setReadOnly[true]")).isLessThan(log.calls().indexOf("setTransactionIsolation[4]"));
            // the read-only account has SELECT only: PostgreSQL refuses the write with SQLState 42501 (insufficient privilege)
            assertThatThrownBy(() -> JdbcClient.create(legacy.reader())
                    .sql("UPDATE " + legacy.schema() + ".project SET title = 'x'").update())
                    .isInstanceOf(DataAccessException.class)
                    .rootCause().isInstanceOfSatisfying(SQLException.class,
                            cause -> assertThat(cause.getSQLState()).isEqualTo("42501"));
            assertThat(contents(legacy)).isEqualTo(before);
        });
    }

    @Test
    @DisplayName("MIG-009 a second run is refused with DUPLICATE_KEY naming the tables that hold data, writes nothing and leaves no duplicates; a new schema that already holds a project of its own is refused as well")
    void mig009_refusesANonEmptyTarget() {
        withRich(rig -> {
            ImportReport first = rig.importAll();
            long rows = rig.ledgerRows();

            LedgerException refused = Expect.failure(rig::importAll, ConflictCode.DUPLICATE_KEY);

            assertThat(refused.args().getFirst()).contains("project").contains("history");
            assertThat(rig.ledgerRows()).isEqualTo(rows);
            assertThat(first.tables()).allMatch(TableCheck::matches);
        });
        withRich(rig -> {
            rig.ctx().bean(ProjectService.class).add(new ProjectCommand("own", "OWN", "A project of its own"), TestOwners.OWNER);

            Expect.failure(rig::importAll, ConflictCode.DUPLICATE_KEY);

            assertThat(rig.count("SELECT COUNT(*) FROM project")).isEqualTo(1);
            assertThat(rig.count("SELECT COUNT(*) FROM history")).isZero();
        });
    }

    private static final String REVISION = "SELECT revision FROM code_revision WHERE id = 1";

    @Test
    @DisplayName("REH2-MIG-02 a new schema that holds a code set through the code service (a CODE_SET row in owner_audit, what migrate and setup make) is accepted: the comparison of every table is equal, and the code revision is one higher than after the code was set")
    void reh2Mig02_acceptsWhatSetupMade() {
        withRich(rig -> {
            rig.ctx().bean(CommonCodeService.class).set(new CodeCommand("ISSUE_CATEGORY", "MINE", "내 코드", "My code", null, null, 3,
                    "ACTIVE"), TestOwners.OWNER);
            assertThat(rig.count("SELECT COUNT(*) FROM owner_audit WHERE operation = 'CODE_SET'")).isEqualTo(1);
            long revision = rig.count(REVISION);

            ImportReport report = rig.importAll();

            assertThat(report.tables()).allMatch(TableCheck::matches);
            assertThat(rig.count(REVISION)).isEqualTo(revision + 1);
            assertThat(rig.count("SELECT COUNT(*) FROM owner_audit WHERE operation = 'IMPORT'")).isEqualTo(1);
            assertThat(rig.count("SELECT COUNT(*) FROM history")).isEqualTo(rig.legacy().count("history"));
        });
    }

    @Test
    @DisplayName("REH2-MIG-03 a code of the new schema that the legacy ledger also has is overwritten with the legacy values (the row of the same key), a code only the new schema has stays as it is, the comparison of common_code is equal and verify passes")
    void reh2Mig03_aSeedCodeOfTheLegacyLedger() {
        withRich(rig -> {
            CommonCodeService codes = rig.ctx().bean(CommonCodeService.class);
            codes.set(new CodeCommand("ISSUE_CATEGORY", "CON", "내 이름", "My name", "내 설명", "My description", 7, "INACTIVE"),
                    TestOwners.OWNER);
            codes.set(new CodeCommand("ISSUE_CATEGORY", "MINE", "내 코드", "My code", null, null, 3, "ACTIVE"), TestOwners.OWNER);

            ImportReport report = rig.importAll();

            Category con = LegacyFixtures.categories().stream().filter(category -> "CON".equals(category.code())).findFirst()
                    .orElseThrow();
            Map<String, Object> row = rig.rows("SELECT name_ko, name_en, description_ko, sort_order, state FROM common_code "
                    + "WHERE code_group = 'ISSUE_CATEGORY' AND code = 'CON'").getFirst();
            assertThat(text(row, "name_ko")).isEqualTo(con.name());
            assertThat(text(row, "name_en")).isEqualTo("Concurrency and atomicity");
            assertThat(text(row, "description_ko")).isEqualTo(con.definition());
            assertThat(text(row, "sort_order")).isEqualTo("0");
            assertThat(text(row, "state")).isEqualTo("ACTIVE");
            Map<String, Object> mine = rig.rows("SELECT name_ko, sort_order FROM common_code WHERE code = 'MINE'").getFirst();
            assertThat(text(mine, "name_ko")).isEqualTo("내 코드");
            assertThat(text(mine, "sort_order")).isEqualTo("3");
            assertThat(report.tables()).filteredOn(check -> check.table() == LegacyTable.COMMON_CODE).singleElement()
                    .matches(TableCheck::matches);
            assertThat(rig.service().verify().tables()).allMatch(TableCheck::matches);
        });
    }

    @Test
    @DisplayName("REH2-MIG-04 a project of its own (PROJECT_ADDED in the audit trail) and a grant on it (GRANT_ISSUED) refuse the import with DUPLICATE_KEY naming the tables that hold the rows (project; project, session_key and session_grant) and never owner_audit; nothing is written")
    void reh2Mig04_ledgerDataRefuses() {
        withRich(rig -> {
            rig.ctx().bean(ProjectService.class).add(new ProjectCommand("own", "OWN", "A project of its own"), TestOwners.OWNER);
            long rows = rig.ledgerRows();

            LedgerException project = Expect.failure(rig::importAll, ConflictCode.DUPLICATE_KEY);

            assertThat(project.args().getFirst()).contains("project").doesNotContain("session_grant").doesNotContain("owner_audit");
            assertThat(rig.ledgerRows()).isEqualTo(rows);

            // a grant needs the registered key of its author (what key-register does)
            rig.ctx().enroll(SEED);
            rig.ctx().bean(GrantService.class).grant(new GrantCommand(SEED, "own", List.of("deliverable"), null, null), TestOwners.OWNER);
            long withGrant = rig.ledgerRows();

            LedgerException grant = Expect.failure(rig::importAll, ConflictCode.DUPLICATE_KEY);

            assertThat(grant.args().getFirst()).contains("project").contains("session_key").contains("session_grant")
                    .doesNotContain("owner_audit");
            assertThat(rig.ledgerRows()).isEqualTo(withGrant);
        });
    }

    @Test
    @DisplayName("REH2-MIG-05 a row of session_key refuses the import (naming session_key), and so does an owner_audit row of the operation IMPORT alone, an earlier import of an empty legacy ledger (naming owner_audit); nothing is written")
    void reh2Mig05_aSessionKeyAndAnEarlierImportRefuse() {
        withRich(rig -> {
            rig.ctx().jdbc().sql("INSERT INTO session_key (id, author_id, public_key, fingerprint, registered_by, registered_at, "
                    + "active_key, fingerprint_key) VALUES (1, 'claude:x', 'AAAA', 'ffff', 'cli:alice', "
                    + "TIMESTAMP '2026-11-01 00:00:00', 'claude:x', 'ffff')").update();
            long rows = rig.ledgerRows();

            LedgerException key = Expect.failure(rig::importAll, ConflictCode.DUPLICATE_KEY);

            assertThat(key.args().getFirst()).isEqualTo("ledger data in session_key");
            assertThat(rig.ledgerRows()).isEqualTo(rows);
        });
        withRich(rig -> {
            rig.ctx().jdbc().sql("INSERT INTO owner_audit (id, occurred_at, actor, operation, subject) VALUES (1, "
                    + "TIMESTAMP '2026-11-01 00:00:00', 'cli:alice', 'IMPORT', 'legacy schema ledger')").update();
            long rows = rig.ledgerRows();

            LedgerException earlier = Expect.failure(rig::importAll, ConflictCode.DUPLICATE_KEY);

            assertThat(earlier.args().getFirst()).isEqualTo("ledger data in owner_audit");
            assertThat(rig.ledgerRows()).isEqualTo(rows);
            assertThat(rig.count("SELECT COUNT(*) FROM project")).isZero();
        });
    }

    @Test
    @DisplayName("MIG-010 a failure after table k leaves no imported row (one transaction): not a row in any table, the counters, the code revision and the audit trail as before; a rerun succeeds")
    void mig010_oneTransaction() {
        withRich(rig -> {
            MisbehavingTarget target = rig.ctx().bean(MisbehavingTarget.class);
            target.failAt(LegacyTable.DEFECT_RELATION);

            assertThatThrownBy(rig::importAll).isInstanceOf(IllegalStateException.class).hasMessageContaining("DEFECT_RELATION");

            assertThat(target.inserted()).as("rows were written before the failure").isPositive();
            assertThat(rig.ledgerRows()).isZero();
            assertThat(rig.count("SELECT COUNT(*) FROM common_code")).isEqualTo(18);
            assertThat(rig.count("SELECT SUM(issued_no) FROM id_counter")).isZero();
            assertThat(rig.count("SELECT revision FROM code_revision")).isZero();

            target.failAt(null);
            ImportReport report = rig.importAll();

            assertThat(report.tables()).allMatch(TableCheck::matches);
            assertThat(rig.count("SELECT COUNT(*) FROM history")).isEqualTo(10);
        }, MisbehavingTarget.Replacement.class);
    }

    // ---------------------------------------------------------------------------------------------- MIG-011 .. 017

    @Test
    @DisplayName("MIG-011 every common code is kept with state, sort order, Korean and English text; CON and EXC have their English names; the category only the old installation has falls back to the Korean text; the 14 known categories have English text that differs from the Korean; severities, deliverable kinds and relation kinds as seeded")
    void mig011_commonCodes() {
        withRich(rig -> {
            rig.importAll();

            assertThat(rig.count("SELECT COUNT(*) FROM common_code")).isEqualTo(34);
            List<Category> categories = LegacyFixtures.categories();
            assertThat(categories).hasSize(14);
            assertThat(rig.count("SELECT COUNT(*) FROM common_code WHERE code_group = 'ISSUE_CATEGORY'")).isEqualTo(15);
            for (Category category : categories) {
                Map<String, Object> row = rig.rows("SELECT name_ko, name_en, description_ko, description_en, sort_order, state "
                        + "FROM common_code WHERE code_group = 'ISSUE_CATEGORY' AND code = '" + category.code() + "'").getFirst();
                assertThat(text(row, "name_ko")).as(category.code()).isEqualTo(category.name());
                assertThat(text(row, "description_ko")).isEqualTo(category.definition());
                assertThat(text(row, "name_en")).isNotEqualTo(category.name());
                assertThat(text(row, "description_en")).isNotEqualTo(category.definition());
                assertThat(text(row, "state")).isEqualTo("ACTIVE");
                assertThat(text(row, "sort_order")).isEqualTo("0");
            }
            assertThat(rig.ctx().jdbc().sql("SELECT name_en FROM common_code WHERE code = 'CON'").query(String.class).single())
                    .isEqualTo("Concurrency and atomicity");
            assertThat(rig.ctx().jdbc().sql("SELECT name_en FROM common_code WHERE code = 'EXC'").query(String.class).single())
                    .isEqualTo("Error cause suppression or substitution");
            Map<String, Object> oldBug = rig.rows("SELECT name_ko, name_en, description_ko, description_en FROM common_code "
                    + "WHERE code = 'OLDBUG'").getFirst();
            assertThat(text(oldBug, "name_en")).isEqualTo(text(oldBug, "name_ko"));
            assertThat(text(oldBug, "description_en")).isEqualTo(text(oldBug, "description_ko"));
            assertThat(rig.ctx().jdbc().sql("SELECT state FROM common_code WHERE code = 'UNRATED'").query(String.class).single())
                    .isEqualTo("INACTIVE");
            assertThat(rig.count("SELECT COUNT(*) FROM common_code WHERE code_group = 'RELATION_KIND'")).isEqualTo(7);
            assertThat(rig.count("SELECT COUNT(*) FROM common_code WHERE code_group = 'ISSUE_SEVERITY' AND state = 'ACTIVE'"))
                    .isEqualTo(3);
            assertThat(rig.count("SELECT COUNT(*) FROM common_code WHERE code_group = 'DELIVERABLE_KIND' AND state = 'ACTIVE'"))
                    .isEqualTo(8);
        });
    }

    @Test
    @DisplayName("MIG-012 an issue imported with severity UNRATED stays UNRATED until it is written: an edit without a rating is CODE_INVALID, the same edit with P3 is accepted at version 2")
    void mig012_unrated() {
        withRich(rig -> {
            rig.importAll();
            SaveService save = rig.ctx().bean(SaveService.class);

            assertThat(rig.ctx().jdbc().sql("SELECT severity FROM issue WHERE id = 'OLD-D-0003'").query(String.class).single())
                    .isEqualTo("UNRATED");
            ValidationException refused = (ValidationException) Expect.failure(() -> save.save(SEED, new SaveCommand(Scenario.uuid(),
                    "issue", "OLD-D-0003", 1L, Fields.of("title", "unrated, edited"), Scenario.REASON)),
                    ValidationCode.CODE_INVALID);
            assertThat(refused.args()).startsWith("issue.severity", "UNRATED");

            SaveResult edited = save.save(SEED, new SaveCommand(Scenario.uuid(), "issue", "OLD-D-0003", 1L,
                    Fields.of(Map.of("title", "unrated, edited", "severity", "P3")), Scenario.REASON));
            assertThat(edited.version()).isEqualTo(2);
            assertThat(rig.ctx().jdbc().sql("SELECT severity FROM issue WHERE id = 'OLD-D-0003'").query(String.class).single())
                    .isEqualTo("P3");
        });
    }

    @Test
    @DisplayName("MIG-013 the detail of an issue shows the symptom of an issue that was merged into it before the import as a merged_symptom case with its observation text, next to the cases")
    void mig013_mergedBeforeTheImport() {
        withRich(rig -> {
            rig.importAll();

            DetailService.IssueDetail detail = rig.ctx().bean(DetailService.class).issue("OLD-D-0001");

            assertThat(detail.cases()).anySatisfy(entry -> {
                assertThat(entry.source()).isEqualTo("merged_symptom");
                assertThat(entry.issue()).isEqualTo("OLD-D-0002");
                assertThat(entry.observation()).isEqualTo("old merged behaviour");
            });
            assertThat(detail.cases()).extracting(DetailService.CaseView::caseId).contains(2L, 5L);
        });
    }

    @Test
    @DisplayName("MIG-014 links with the same target text in the task and the issue table, ids that contain |, re-linked rows: every link arrives and the link keys keep the kinds and the separator apart")
    void mig014_links() {
        withRich(rig -> {
            rig.importAll();

            assertThat(rig.count("SELECT COUNT(*) FROM criterion_link")).isEqualTo(7).isEqualTo(rig.legacy().count("condition_link"));
            assertThat(rig.ctx().jdbc().sql("SELECT link_key FROM criterion_link").query(String.class).list())
                    .containsExactlyInAnyOrder("T:7:OLD-C-1:OLD-T-1", "T:7:OLD-C-1:OLD-D-0001", "I:7:OLD-C-1:OLD-D-0001",
                            "I:7:OLD-C-2:OLD-D-0001", "T:7:OLD-C-3:OLD-T-1", "T:10:OLD-PIPE-C:A|T", "T:12:OLD-PIPE-C|A:T");
            assertThat(rig.count("SELECT COUNT(*) FROM milestone_criterion WHERE id = 'OLD-PIPE-C|A'")).isEqualTo(1);
            assertThat(rig.count("SELECT COUNT(*) FROM task WHERE id = 'A|T'")).isEqualTo(1);
            assertThat(rig.count("SELECT COUNT(*) FROM issue_task")).isEqualTo(2);
            assertThat(rig.count("SELECT COUNT(*) FROM issue_relation")).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("MIG-035 1.x stored every relation with the smaller id first and kept nowhere which issue the caller named first: caused_by and blocks are imported as stored, never guessed, and listed (count and ids) in the report, the audit row and the verification; a kind only the legacy ledger has is imported without a direction; the codes keep their direction")
    void mig035_relationsOfUnknownDirection() {
        withRig(LegacyFixtures.withDirectedRelations(LegacyFixtures.rich()), rig -> {
            ImportReport report = rig.importAll();

            assertThat(report.tables()).allMatch(TableCheck::matches);
            assertThat(rig.count("SELECT COUNT(*) FROM issue_relation")).isEqualTo(4);
            assertThat(rig.count("SELECT COUNT(*) FROM issue_relation WHERE a = 'E2E-D-0007' AND b = 'OLD-D-0001' "
                    + "AND kind = 'caused_by'")).as("imported in the order it was stored").isEqualTo(1);
            // only the relations of a kind that has a direction are listed, in stored order, sorted
            assertThat(report.relationsOfUnknownDirection())
                    .containsExactly("E2E-D-0007|OLD-D-0001|caused_by", "OLD-D-0001|OLD-D-0002|blocks");
            assertThat(report.detail()).endsWith("relations_of_unknown_direction=2");
            assertThat(rig.rows("SELECT detail FROM owner_audit WHERE operation = 'IMPORT'")).singleElement()
                    .satisfies(row -> assertThat(text(row, "detail")).endsWith("relations_of_unknown_direction=2"));
            // the legacy code rows overwrote the seed rows, but the direction of the seed stays; a kind only the legacy
            // ledger has is a new code and has no direction
            Map<String, String> directions = new java.util.HashMap<>();
            rig.rows("SELECT code, direction FROM common_code WHERE code_group = 'RELATION_KIND'")
                    .forEach(row -> directions.put(text(row, "code"), text(row, "direction")));
            assertThat(directions).containsEntry("caused_by", "GIVEN").containsEntry("blocks", "GIVEN")
                    .containsEntry("same_site", "CANONICAL").containsEntry("old_custom", "CANONICAL");
            // the verification lists the same relations and writes nothing
            assertThat(rig.service().verify().relationsOfUnknownDirection())
                    .isEqualTo(report.relationsOfUnknownDirection());
        });
        withRich(rig -> assertThat(rig.importAll().relationsOfUnknownDirection())
                .as("the relation of the rich fixture is of a kind without a direction").isEmpty());
    }

    @Test
    @DisplayName("MIG-015 4000 characters of Korean text with both kinds of quotes, backslashes and line feeds arrive unchanged (an issue symptom and a case observation)")
    void mig015_longText() {
        withRich(rig -> {
            rig.importAll();

            String expected = LegacyFixtures.koreanText(4000);
            assertThat(expected).hasSize(4000).contains("\n").contains("\"").contains("'");
            assertThat(rig.ctx().jdbc().sql("SELECT symptom FROM issue WHERE id = 'E2E-D-0100'").query(String.class).single())
                    .isEqualTo(expected);
            assertThat(rig.ctx().jdbc().sql("SELECT observation FROM issue_case WHERE id = 5").query(String.class).single())
                    .isEqualTo(expected);
        });
    }

    @Test
    @DisplayName("MIG-016 every time value is the same instant on both sides (microseconds kept, mixed offsets of the legacy values), stored as UTC")
    void mig016_instants() {
        withRich(rig -> {
            rig.importAll();

            for (String[] column : new String[][] {
                    {"history", "at", "history", "occurred_at"},
                    {"task", "created_at", "task", "created_at"},
                    {"task", "updated_at", "task", "updated_at"},
                    {"defect", "created_at", "issue", "created_at"},
                    {"defect", "updated_at", "issue", "updated_at"},
                    {"defect_fix", "ci_completed_at", "deliverable", "checked_at"},
                    {"defect_fix", "recorded_at", "deliverable", "recorded_at"},
                    {"defect_case", "recorded_at", "issue_case", "recorded_at"},
                    {"session_grant", "granted_at", "session_grant", "granted_at"},
                    {"session_grant", "expires_at", "session_grant", "expires_at"},
                    {"session_grant", "revoked_at", "session_grant", "revoked_at"}}) {
                // the request receipts of 1.x are not copied, so their times are not compared (M1X-003)
                String key = "id";
                List<Map<String, Object>> legacy = rig.legacy().rows("SELECT " + key + " AS k, EXTRACT(EPOCH FROM " + column[1]
                        + ") AS e FROM " + rig.legacy().schema() + "." + column[0] + " ORDER BY " + key);
                for (Map<String, Object> row : legacy) {
                    Instant instant = rig.instant("SELECT " + column[3] + " AS t FROM " + column[2] + " WHERE " + key + " = ?",
                            row.get("k"));
                    if (row.get("e") == null) {
                        assertThat(instant).as("%s.%s %s", column[0], column[1], row.get("k")).isNull();
                    } else {
                        assertThat(epoch(instant)).as("%s.%s %s", column[0], column[1], row.get("k"))
                                .isEqualByComparingTo(new BigDecimal(row.get("e").toString()));
                    }
                }
            }
            assertThat(rig.instant("SELECT occurred_at AS t FROM history WHERE id = 1")).isEqualTo(Instant.parse("2026-11-05T10:00:00.123456Z"));
            assertThat(rig.instant("SELECT occurred_at AS t FROM history WHERE id = 2")).isEqualTo(Instant.parse("2026-11-05T10:00:00.654321Z"));
            assertThat(rig.instant("SELECT occurred_at AS t FROM history WHERE id = 3")).isEqualTo(Instant.parse("2026-11-05T10:00:00Z"));
        });
    }

    @Test
    @DisplayName("MIG-017 a merge chain whose child has a lower id than its target imports (the target is inserted first, no DBMS-specific syntax): merged_into is intact")
    void mig017_mergeTargetAfterChild() {
        withRich(rig -> {
            rig.importAll();

            assertThat(rig.ctx().jdbc().sql("SELECT merged_into FROM issue WHERE id = 'E2E-D-0004'").query(String.class).single())
                    .isEqualTo("E2E-D-0100");
            assertThat(rig.ctx().jdbc().sql("SELECT merged_into FROM issue WHERE id = 'OLD-D-0002'").query(String.class).single())
                    .isEqualTo("OLD-D-0001");
            assertThat(rig.count("SELECT COUNT(*) FROM issue WHERE status = 'MERGED'")).isEqualTo(2);
        });
    }

    @Test
    @DisplayName("MIG-029 issues that are merged into each other in a loop cannot be ordered: they are reported (MERGE_CHAIN_LOOPS) and nothing is written")
    void mig029_mergeLoop() {
        LegacyLedger legacy = LegacyFixtures.rich();
        legacy.update("UPDATE " + legacy.schema() + ".defect SET merged_into = 'OLD-D-0002', status = 'MERGED' "
                + "WHERE id = 'OLD-D-0001'");
        withRig(legacy, rig -> {
            ValidationException refused = (ValidationException) Expect.failure(rig::importAll, ValidationCode.VALIDATION_FAILED);

            assertThat(refused.issues()).extracting(ValidationIssue::path, ValidationIssue::reason).containsExactlyInAnyOrder(
                    tuple("issue[OLD-D-0001].merged_into", ValidationReason.MERGE_CHAIN_LOOPS),
                    tuple("issue[OLD-D-0002].merged_into", ValidationReason.MERGE_CHAIN_LOOPS));
            assertThat(rig.ledgerRows()).isZero();
        });
    }

    // ---------------------------------------------------------------------------------------------- MIG-018 .. 019

    @Test
    @DisplayName("MIG-018 after the import the ledger works on the imported data: create (ids continue), edit with the imported version, record a deliverable and a case, move to RESOLVED and verify; versions continue, new history, deliverable and case ids exceed every imported id, no key collides")
    void mig018_writesContinue() {
        withRich(rig -> {
            rig.importAll();
            LedgerAppContext ctx = rig.ctx();
            String reviewer = "codex:mig-reviewer";
            ctx.enroll(reviewer);
            ctx.enroll(SEED);
            ctx.bean(GrantService.class).grant(new GrantCommand(reviewer, "old", List.of("deliverable", "verify"), null, null),
                    TestOwners.OWNER);
            ctx.bean(GrantService.class).grant(new GrantCommand(SEED, "old", List.of("deliverable"), null, null), TestOwners.OWNER);

            SaveResult created = ctx.bean(SaveService.class).save(SEED, new SaveCommand(Scenario.uuid(), "issue", null, 0L,
                    Fields.of(issueFields("old", "after the import")), Scenario.REASON));
            assertThat(created.id()).isEqualTo("OLD-I-0004");
            assertThat(created.historyId()).isGreaterThan(57);
            SaveResult task = ctx.bean(SaveService.class).save(SEED, new SaveCommand(Scenario.uuid(), "task", null, 0L,
                    Fields.of(Map.of("project_id", "old", "title", "new task", "purpose", "p", "success_condition", "s")),
                    Scenario.REASON));
            assertThat(task.id()).isEqualTo("OLD-T-0001");

            SaveResult edited = ctx.bean(SaveService.class).save(SEED, new SaveCommand(Scenario.uuid(), "issue", "OLD-D-0001", 1L,
                    Fields.of("title", "edited after the import"), Scenario.REASON));
            assertThat(edited.version()).isEqualTo(2);
            assertThat(edited.historyId()).isGreaterThan(created.historyId());

            RecordCaseResult observed = ctx.bean(RecordCaseService.class).recordCase(SEED, new RecordCaseCommand(Scenario.uuid(),
                    "OLD-D-0001", "seen again after the import", Scenario.REASON, null, null, null));
            assertThat(observed.caseId()).isEqualTo(6);

            RecordDeliverableResult fixed = ctx.bean(RecordDeliverableService.class).recordDeliverable(SEED,
                    new RecordDeliverableCommand(Scenario.uuid(), "issue", "OLD-D-0001", "code_change", "d".repeat(40),
                            Scenario.REASON, "repo/old", "fix after import", null, null, null, true));
            assertThat(fixed.deliverableId()).isEqualTo(10);
            assertThat(fixed.version()).isEqualTo(3);
            assertThat(fixed.targetState()).isEqualTo("RESOLVED");

            TransitionResult verified = ctx.bean(ReviewService.class).review(reviewer, new TransitionCommand(Scenario.uuid(), "issue",
                    "OLD-D-0001", fixed.version(), "VERIFIED", Scenario.REASON, Scenario.EVIDENCE, null, null));
            assertThat(verified.version()).isEqualTo(4);
            assertThat(verified.historyId()).isGreaterThan(fixed.historyId());

            TransitionResult moved = ctx.bean(TransitionService.class).transition(SEED, new TransitionCommand(Scenario.uuid(), "task",
                    "OLD-T-1", 1L, "ACTIVE", Scenario.REASON, null, null, null));
            assertThat(moved.version()).isEqualTo(2);
            // every history row written after the import has an id above the imported ones, the ids continue without a gap
            // or a collision, and they are exactly the ones the calls returned (the deliverable also moved the issue to
            // RESOLVED, which is a second history row of its own)
            List<Long> returned = List.of(created.historyId(), task.historyId(), edited.historyId(), observed.historyId(),
                    fixed.historyId(), fixed.transitionHistoryId(), verified.historyId(), moved.historyId());
            assertThat(rig.ctx().jdbc().sql("SELECT id FROM history WHERE id > 57 ORDER BY id").query(Long.class).list())
                    .containsExactlyInAnyOrderElementsOf(returned).containsExactly(58L, 59L, 60L, 61L, 62L, 63L, 64L, 65L);
            assertThat(rig.count("SELECT COUNT(*) FROM history")).isEqualTo(10 + returned.size());
        });
    }

    @Test
    @DisplayName("MIG-019 a row of the new schema that was changed after the import is found by the verification: INTEGRITY_VIOLATION naming the table, both counts and both digests; the verification of an untouched import passes and writes nothing")
    void mig019_verification() {
        withRich(rig -> {
            ImportReport report = rig.importAll();
            long rows = rig.ledgerRows();
            ImportReport again = rig.service().verify();
            assertThat(again.tables()).isEqualTo(report.tables());
            assertThat(rig.ledgerRows()).isEqualTo(rows);

            rig.ctx().jdbc().sql("UPDATE issue SET title = 'tampered' WHERE id = 'OLD-D-0001'").update();

            InfrastructureException failure = (InfrastructureException) Expect.failure(() -> rig.service().verify(),
                    InfrastructureCode.INTEGRITY_VIOLATION);
            TableCheck expected = report.tables().stream().filter(check -> check.table() == LegacyTable.DEFECT).findFirst()
                    .orElseThrow();
            assertThat(failure.args()).hasSize(5);
            assertThat(failure.args().get(0)).isEqualTo("issue");
            assertThat(failure.args().get(1)).isEqualTo(String.valueOf(expected.sourceRows()));
            assertThat(failure.args().get(2)).isEqualTo(String.valueOf(expected.targetRows()));
            assertThat(failure.args().get(3)).isEqualTo(expected.sourceDigest().hex());
            assertThat(failure.args().get(4)).isNotEqualTo(expected.targetDigest().hex()).hasSize(64);
        });
    }

    @Test
    @DisplayName("MIG-019 the comparison inside the import: a value that the write side changed makes the import fail with INTEGRITY_VIOLATION and roll back completely")
    void mig019_comparisonInsideTheImport() {
        withRich(rig -> {
            MisbehavingTarget target = rig.ctx().bean(MisbehavingTarget.class);
            target.tamper(true);

            InfrastructureException failure = (InfrastructureException) Expect.failure(rig::importAll,
                    InfrastructureCode.INTEGRITY_VIOLATION);

            assertThat(failure.args().get(0)).isEqualTo("issue");
            assertThat(failure.args().get(3)).isNotEqualTo(failure.args().get(4));
            assertThat(rig.ledgerRows()).isZero();
            target.tamper(false);
            assertThat(rig.importAll().tables()).allMatch(TableCheck::matches);
        }, MisbehavingTarget.Replacement.class);
    }

    // ---------------------------------------------------------------------------------------------- MIG-021 .. 024

    @Test
    @DisplayName("MIG-021 a legacy database without the installer steps defect_case, common_code or created_by, or without the schema, is refused with NOT_FOUND naming the missing steps; nothing is written")
    void mig021_incompleteSource() {
        LegacyLedger older = LegacyLedger.create(Set.of("schema", "schema_v2"));
        withRig(older, rig -> {
            LedgerException refused = Expect.failure(rig::importAll, LookupCode.NOT_FOUND);

            assertThat(refused.args().get(1)).contains("defect_case").contains("common_code").contains("created_by")
                    .doesNotContain("schema");
            assertThat(rig.ledgerRows()).isZero();
        });
        LegacyLedger none = LegacyLedger.withoutSchema();
        withRig(none, none.admin(), rig -> {
            LedgerException refused = Expect.failure(rig::importAll, LookupCode.NOT_FOUND);

            assertThat(refused.args().get(1)).contains("schema").contains("defect_case");
            assertThat(rig.ledgerRows()).isZero();
        });
    }

    @Test
    @DisplayName("MIG-022 through the history read tool the imported rows show their changes as imported and a null before_data, also when before_data is asked for")
    void mig022_readTool() {
        withRich(rig -> {
            rig.importAll();

            var page = rig.ctx().bean(HistoryService.class).history(new HistoryQuery(null, null, null, null, null, null, null,
                    null, null, null, true, null, null));

            assertThat(page.items()).hasSize(10);
            assertThat(page.items()).allSatisfy(view -> assertThat(view.beforeData()).isNull());
            // the legacy column is jsonb: the changes are the text PostgreSQL printed for it, as imported
            String changes = page.items().stream().filter(view -> view.id() == 2).findFirst().orElseThrow().changes().text();
            assertThat(changes).isEqualTo(rig.legacy().rows("SELECT changes FROM " + rig.legacy().schema()
                    + ".history WHERE id = 2").getFirst().get("changes").toString());
            assertSameJson(changes, "{\"title\":[\"old\",\"First old defect\"],\"quote\":\"\\\"x\\\"\"}");
            assertThat(page.items().stream().filter(view -> view.id() == 13).findFirst().orElseThrow().sourceRef())
                    .isEqualTo("older:1");
        });
    }

    @Test
    @DisplayName("MIG-023 (a) an empty text in a nullable legacy column is NULL in the new schema and the digests agree; (b) revised by REH3: an empty purpose or success_condition (a nullable column in 2.0) is NULL, the import succeeds and the verification passes; a NOT NULL column that identifies a row stays refused (REH3-IMP-04)")
    void mig023_emptyStrings() {
        withRich(rig -> {
            rig.importAll();

            assertThat(rig.rows("SELECT owner_actor_id, origin_ref FROM task WHERE id = 'OLD-T-1'").getFirst().values())
                    .containsOnlyNulls();
            assertThat(rig.count("SELECT COUNT(*) FROM issue_case WHERE id = 2 AND evidence IS NULL")).isEqualTo(1);
            assertThat(rig.count("SELECT COUNT(*) FROM deliverable WHERE id = 9 AND summary = 'imported 1.x commit' "
                    + "AND check_ref IS NULL")).isEqualTo(1);
            assertThat(rig.count("SELECT COUNT(*) FROM task WHERE origin_ref = '' OR owner_actor_id = ''")).isZero();
        });
        // revised by REH3 (decision 7j): the five long texts of 1.x that allowed '' are nullable, the import stores NULL
        LegacyLedger legacy = LegacyFixtures.withEmptyPurpose(LegacyFixtures.rich());
        withRig(legacy, rig -> {
            ImportReport report = rig.importAll();

            assertThat(report.tables()).allMatch(TableCheck::matches);
            assertThat(rig.count("SELECT COUNT(*) FROM task WHERE id = 'OLD-T-UNKNOWN' AND purpose IS NULL "
                    + "AND success_condition IS NULL")).isEqualTo(1);
            assertThat(rig.count("SELECT COUNT(*) FROM task WHERE purpose = '' OR success_condition = ''")).isZero();
            rig.service().verify();
        });
    }

    @Test
    @DisplayName("MIG-024 the fixture builder fills the legacy tables from category-legacy-fixture.json and the data of the old admin: the 14 canonical categories plus OLDBUG, OLD-D-0001..3, tasks, conditions, links, re-links, cases, fixes, grants, history and receipts")
    void mig024_fixture() {
        try (LegacyLedger legacy = LegacyFixtures.rich()) {
            assertThat(legacy.objects()).containsExactlyInAnyOrderElementsOf(LegacyLedger.TABLES);
            assertThat(legacy.count("common_code")).isEqualTo(25);
            assertThat(legacy.count("project")).isEqualTo(2);
            assertThat(legacy.count("task")).isEqualTo(6);
            assertThat(legacy.count("defect")).isEqualTo(6);
            assertThat(legacy.count("defect_fix")).isEqualTo(2);
            assertThat(legacy.count("defect_case")).isEqualTo(2);
            assertThat(legacy.count("condition")).isEqualTo(5);
            assertThat(legacy.count("condition_link")).isEqualTo(7);
            assertThat(legacy.count("session_grant")).isEqualTo(5);
            assertThat(legacy.count("history")).isEqualTo(10);
            assertThat(legacy.count("request_receipt")).isEqualTo(3);
            assertThat(legacy.count("_migration")).isEqualTo(5);
        }
    }

    // ---------------------------------------------------------------------------------------------- MIG-027 .. 032

    @Test
    @DisplayName("MIG-027 the timestamptz instants of the legacy columns are read as instants, whatever the zone of the legacy database session (UTC, Asia/Seoul, America/New_York): the epoch value of every imported timestamp equals the source epoch value in all three runs and the target rows are identical across the runs")
    void mig027_sessionZones() {
        List<String> snapshots = new ArrayList<>();
        for (String zone : List.of("UTC", "Asia/Seoul", "America/New_York")) {
            // one legacy fixture per zone: its pool for the zone is closed with it, so the test JVM keeps within its
            // connection budget (the data of the fixture is the same every time)
            try (LegacyLedger legacy = LegacyFixtures.rich()) {
                DataSource source = legacy.reader(zone);
                assertThat(JdbcClient.create(source).sql("SHOW TIME ZONE").query(String.class).single()).isEqualTo(zone);
                ImportRunners.importing(legacy, source, ctx -> {
                    Rig rig = new Rig(legacy, ctx);
                    rig.importAll();
                    for (Map<String, Object> row : legacy.rows("SELECT id AS k, EXTRACT(EPOCH FROM at) AS e FROM "
                            + legacy.schema() + ".history")) {
                        Instant instant = rig.instant("SELECT occurred_at AS t FROM history WHERE id = ?", row.get("k"));
                        assertThat(epoch(instant)).as("%s history %s", zone, row.get("k"))
                                .isEqualByComparingTo(new BigDecimal(row.get("e").toString()));
                    }
                    snapshots.add(rig.rows("SELECT * FROM history ORDER BY id").toString() + rig.rows(
                            "SELECT * FROM task ORDER BY id") + rig.rows("SELECT * FROM session_grant ORDER BY id"));
                });
            }
        }
        assertThat(snapshots).hasSize(3);
        assertThat(snapshots.get(1)).isEqualTo(snapshots.get(0));
        assertThat(snapshots.get(2)).isEqualTo(snapshots.get(0));
    }

    @Test
    @DisplayName("MIG-028 ids with | and other upper-case ids import unchanged; revised by REH3: a lower-case task or condition id is kept as it is (never case-folded); ids that differ only in case or are malformed are reported (REH3-IMP-02)")
    void mig028_ids() {
        withRich(rig -> {
            rig.importAll();

            assertThat(rig.count("SELECT COUNT(*) FROM milestone_criterion WHERE id IN ('OLD-PIPE-C', 'OLD-PIPE-C|A')")).isEqualTo(2);
            assertThat(rig.count("SELECT COUNT(*) FROM task WHERE id = 'A|T'")).isEqualTo(1);
        });
        // revised by REH3 (decision 7m): the ids of 1.x are kept in any case, and are never case-folded
        LegacyLedger legacy = LegacyFixtures.withLowerCaseIds(LegacyFixtures.rich());
        withRig(legacy, rig -> {
            rig.importAll();

            assertThat(rig.count("SELECT COUNT(*) FROM task WHERE id = 'old-t-lower'")).isEqualTo(1);
            assertThat(rig.count("SELECT COUNT(*) FROM milestone_criterion WHERE id = 'old-c-lower'")).isEqualTo(1);
            assertThat(rig.count("SELECT COUNT(*) FROM task WHERE id = 'OLD-T-LOWER'")).isZero();
            rig.service().verify();
        });
    }

    @Test
    @DisplayName("MIG-031 the import records one owner audit row IMPORT with the actor, the legacy schema as subject and the row counts as detail, and bumps the code revision so a running server reloads the common codes")
    void mig031_auditAndRevision() {
        withRich(rig -> {
            rig.importAll();

            List<Map<String, Object>> audit = rig.rows("SELECT actor, operation, subject, detail FROM owner_audit ORDER BY id");
            assertThat(audit).hasSize(1);
            assertThat(text(audit.getFirst(), "actor")).isEqualTo(TestOwners.OWNER.value());
            assertThat(text(audit.getFirst(), "operation")).isEqualTo("IMPORT");
            assertThat(text(audit.getFirst(), "subject")).isEqualTo("legacy schema " + rig.legacy().schema());
            assertThat(text(audit.getFirst(), "detail")).startsWith("rows=").contains("history=10").contains("project=2");
            assertThat(rig.count("SELECT revision FROM code_revision")).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("SVC-PRN-05 a legacy task whose id has a number of 20 digits has no number: the import and the verification are not stopped by a NumberFormatException, the counter ignores it and still continues after the 4 digit id")
    void prn05_taskIdWithAHugeNumber() {
        LegacyLedger legacy = LegacyFixtures.rich();
        String huge = "E2E-T-" + "9".repeat(20);
        legacy.update("UPDATE " + legacy.schema() + ".task SET id = ? WHERE id = 'E2E-T-1'", huge);
        withRig(legacy, rig -> {
            rig.importAll();

            assertThat(rig.count("SELECT COUNT(*) FROM task WHERE id = ?", huge)).isEqualTo(1);
            assertThat(rig.count("SELECT next_task_no FROM project WHERE id = 'e2e'")).isEqualTo(3);
            rig.service().verify();
        });
    }

    @Test
    @DisplayName("MIG-032 a text longer than its column in the new schema is reported (never cut): table, key, column and the limit")
    void mig032_tooLong() {
        LegacyLedger legacy = LegacyFixtures.rich();
        legacy.update("UPDATE " + legacy.schema() + ".task SET title = ? WHERE id = 'OLD-T-1'", "x".repeat(301));
        withRig(legacy, rig -> {
            ValidationException refused = (ValidationException) Expect.failure(rig::importAll, ValidationCode.VALIDATION_FAILED);

            assertThat(refused.issues()).hasSize(1);
            ValidationIssue issue = refused.issues().getFirst();
            assertThat(issue.path()).isEqualTo("task[OLD-T-1].title");
            assertThat(issue.reason()).isEqualTo(ValidationReason.MUST_BE_AT_MOST_CHARS);
            assertThat(issue.reasonArgs()).containsExactly("300");
            assertThat(rig.ledgerRows()).isZero();
        });
    }

    @Test
    @DisplayName("MIG-033 the service says whether the legacy connection is configured (sourceConfigured), and the legacy schema name must be an identifier")
    void sourceNotConfigured() {
        LegacyLedger legacy = LegacyFixtures.rich();
        withRig(legacy, rig -> assertThat(rig.service().sourceConfigured()).isTrue());
        try (LegacyLedger other = LegacyLedger.create()) {
            assertThat(new LegacySourceRepository(JdbcClient.create(other.admin()), other.admin(), other.schema()).schema())
                    .isEqualTo(other.schema());
            assertThatThrownBy(() -> new LegacySourceRepository(JdbcClient.create(other.admin()), other.admin(),
                    "Ledger; DROP"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
