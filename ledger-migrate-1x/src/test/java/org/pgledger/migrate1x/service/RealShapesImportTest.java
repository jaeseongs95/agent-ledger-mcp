package org.pgledger.migrate1x.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.application.admin.GrantService;
import org.pgledger.application.admin.GrantService.GrantCommand;
import org.pgledger.application.read.DetailService;
import org.pgledger.application.read.ListService;
import org.pgledger.application.read.ListService.CriteriaQuery;
import org.pgledger.application.support.Expect;
import org.pgledger.application.support.Fields;
import org.pgledger.application.support.LedgerAppContext;
import org.pgledger.application.support.Scenario;
import org.pgledger.application.write.LinkCommand;
import org.pgledger.application.write.LinkService;
import org.pgledger.application.write.SaveCommand;
import org.pgledger.application.write.SaveService;
import org.pgledger.application.write.TransitionCommand;
import org.pgledger.application.write.TransitionService;
import org.pgledger.application.write.WriteResult.SaveResult;
import org.pgledger.domain.error.LookupCode;
import org.pgledger.domain.error.ValidationCode;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationIssue;
import org.pgledger.domain.model.CriterionLink;
import org.pgledger.domain.value.AuthorId;
import org.pgledger.domain.value.CriterionId;
import org.pgledger.domain.value.IssueId;
import org.pgledger.domain.value.TaskId;
import org.pgledger.migrate1x.fixture.LegacyFixtures;
import org.pgledger.migrate1x.fixture.LegacyLedger;
import org.pgledger.migrate1x.support.ImportRunners;
import org.pgledger.testing.TestOwners;

/**
 * REH3-IMP-01 .. 04, REH3-ID-03, REH3-TXT-05 (and the import side of REH3-ID-08): the 1.x ledger with the shapes of the
 * real 1.x data (rehearsal 3) through the import service in the real application context, then the tools by the ids that
 * 1.x used.
 */
class RealShapesImportTest {

    private static void withRig(LegacyLedger legacy, Consumer<LedgerAppContext> body) {
        try (legacy) {
            ImportRunners.importing(legacy, legacy.reader(), body);
        }
    }

    private static long count(LedgerAppContext ctx, String sql, Object... arguments) {
        return ctx.jdbc().sql(sql).params(Arrays.asList(arguments)).query(Long.class).single();
    }

    private static LegacyImportService importer(LedgerAppContext ctx) {
        return ctx.bean(LegacyImportService.class);
    }

    private static ValidationException refused(LedgerAppContext ctx) {
        return (ValidationException) Expect.failure(() -> importer(ctx).importLegacy(TestOwners.OWNER),
                ValidationCode.VALIDATION_FAILED);
    }

    private static void assertNothingWritten(LedgerAppContext ctx) {
        for (String table : List.of("project", "task", "issue", "milestone_criterion", "criterion_link", "history",
                "owner_audit")) {
            assertThat(count(ctx, "SELECT COUNT(*) FROM " + table)).as(table).isZero();
        }
    }

    @Test
    @DisplayName("REH3-IMP-01 the legacy ledger of the real shapes (ids in any case with # / . : _ -, 120 characters, a code of 79, a CI reference of 533, '' in five long texts, links to tasks and defects) imports and verifies: every id byte for byte, '' as NULL, the link keys as CriterionLink derives them, the id_key in upper case")
    void imp01_realShapes() {
        withRig(LegacyFixtures.withRealShapes(LegacyFixtures.rich()), ctx -> {
            ImportReport report = importer(ctx).importLegacy(TestOwners.OWNER);

            assertThat(report.tables()).allMatch(TableCheck::matches);
            importer(ctx).verify();
            for (String id : List.of(LegacyFixtures.REAL_TASK_A01, LegacyFixtures.REAL_TASK_LOWER_SUFFIX,
                    LegacyFixtures.REAL_TASK_LONG, LegacyFixtures.REAL_TASK_CHARS, LegacyFixtures.REAL_TASK_120,
                    LegacyFixtures.REAL_TASK_EMPTY_BOTH, LegacyFixtures.REAL_TASK_EMPTY_ONE)) {
                assertThat(count(ctx, "SELECT COUNT(*) FROM task WHERE id = ?", id)).as(id).isEqualTo(1);
            }
            assertThat(LegacyFixtures.REAL_TASK_120).hasSize(120);
            for (String id : List.of(LegacyFixtures.REAL_CONDITION_LONG, LegacyFixtures.REAL_CONDITION_LOWER)) {
                assertThat(count(ctx, "SELECT COUNT(*) FROM milestone_criterion WHERE id = ?", id)).as(id).isEqualTo(1);
            }
            assertThat(count(ctx, "SELECT COUNT(*) FROM milestone_criterion WHERE code = ?", LegacyFixtures.REAL_CODE_79))
                    .isEqualTo(1);
            assertThat(count(ctx, "SELECT COUNT(*) FROM deliverable WHERE check_ref = ?", LegacyFixtures.REAL_CI_REF_533))
                    .isEqualTo(1);
            // REH3-LEN-02: an origin reference of 9408 characters (a text column of 1.x without a limit) is stored whole
            assertThat(LegacyFixtures.REAL_ORIGIN_REF_9408).hasSize(9408);
            assertThat(count(ctx, "SELECT COUNT(*) FROM task WHERE id = ? AND origin_ref = ?", LegacyFixtures.REAL_TASK_LONG,
                    LegacyFixtures.REAL_ORIGIN_REF_9408)).isEqualTo(1);
            // '' is NULL
            assertThat(count(ctx, "SELECT COUNT(*) FROM task WHERE id = ? AND purpose IS NULL AND success_condition IS NULL",
                    LegacyFixtures.REAL_TASK_EMPTY_BOTH)).isEqualTo(1);
            assertThat(count(ctx, "SELECT COUNT(*) FROM task WHERE id = ? AND purpose IS NULL AND success_condition = ?",
                    LegacyFixtures.REAL_TASK_EMPTY_ONE, "it is done")).isEqualTo(1);
            assertThat(count(ctx, "SELECT COUNT(*) FROM issue WHERE id = ? AND symptom IS NULL AND root_cause IS NULL",
                    LegacyFixtures.REAL_DEFECT)).isEqualTo(1);
            assertThat(count(ctx, "SELECT COUNT(*) FROM milestone_criterion WHERE id = ? AND criterion_text IS NULL",
                    LegacyFixtures.REAL_CONDITION_LOWER)).isEqualTo(1);
            // the link keys are the ones of a new link
            CriterionId criterion = CriterionId.of(LegacyFixtures.REAL_CONDITION_LONG);
            List<String> keys = ctx.jdbc().sql("SELECT link_key FROM criterion_link WHERE criterion_id = ? ORDER BY link_key")
                    .param(criterion.value()).query(String.class).list();
            assertThat(keys).containsExactlyInAnyOrder(
                    CriterionLink.toTask(criterion, TaskId.of(LegacyFixtures.REAL_TASK_A01), AuthorId.OWNER).linkKey(),
                    CriterionLink.toTask(criterion, TaskId.of(LegacyFixtures.REAL_TASK_CHARS), AuthorId.OWNER).linkKey(),
                    CriterionLink.toIssue(criterion, IssueId.of(LegacyFixtures.REAL_DEFECT),
                            AuthorId.OWNER).linkKey());
            // the normalised id the unique key rests on
            for (Map<String, Object> row : ctx.jdbc().sql("SELECT id, id_key FROM task").query().listOfRows()) {
                assertThat(row.get("id_key")).isEqualTo(String.valueOf(row.get("id")).toUpperCase(Locale.ROOT));
            }
            for (Map<String, Object> row : ctx.jdbc().sql("SELECT id, id_key FROM milestone_criterion").query().listOfRows()) {
                assertThat(row.get("id_key")).isEqualTo(String.valueOf(row.get("id")).toUpperCase(Locale.ROOT));
            }
        });
    }

    @Test
    @DisplayName("REH3-IMP-02 ids of one kind that differ only in case are reported (the later one of each pair, naming the other) and nothing is written; a space, 121 characters and a tab are reported as malformed")
    void imp02_caseOnlyAndMalformedIds() {
        withRig(LegacyFixtures.withCaseDuplicates(LegacyFixtures.rich()), ctx -> {
            ValidationException refused = refused(ctx);

            assertThat(refused.issues()).hasSize(2);
            assertThat(refused.issues()).allSatisfy(issue -> assertThat(issue.reason().name())
                    .isEqualTo("ID_DIFFERS_ONLY_IN_CASE"));
            List<String> paths = refused.issues().stream().map(ValidationIssue::path).toList();
            assertThat(paths).anyMatch(path -> "task[OLD-T-a].id".equals(path) || "task[OLD-T-A].id".equals(path));
            assertThat(paths).anyMatch(path -> "milestone_criterion[old-c].id".equals(path)
                    || "milestone_criterion[OLD-C].id".equals(path));
            ValidationIssue task = refused.issues().stream().filter(issue -> issue.path().startsWith("task[")).findFirst()
                    .orElseThrow();
            assertThat(task.reasonArgs()).hasSize(1);
            assertThat(task.reasonArgs().getFirst()).isIn("OLD-T-a", "OLD-T-A").isNotEqualTo(
                    task.path().substring("task[".length(), task.path().length() - "].id".length()));
            assertNothingWritten(ctx);
        });
        withRig(LegacyFixtures.withMalformedIds(LegacyFixtures.rich()), ctx -> {
            ValidationException refused = refused(ctx);

            assertThat(refused.issues()).hasSize(3);
            assertThat(refused.issues()).allSatisfy(issue -> assertThat(issue.reason().name()).isEqualTo("MUST_BE_ID"));
            assertThat(refused.issues().stream().map(ValidationIssue::path).toList()).contains("task[OLD T SPACE].id",
                    "task[" + LegacyFixtures.TASK_ID_121 + "].id", "milestone_criterion[OLD\tC].id");
            assertNothingWritten(ctx);
        });
    }

    private static void insertCondition(LegacyLedger legacy, String id, String code) {
        legacy.insert("condition", "id", id, "project_id", "old", "release", "old", "code", code, "condition_text", "text",
                "state", "UNKNOWN", "version", 1, "created_by", LegacyFixtures.SEED);
    }

    private static void insertFix(LegacyLedger legacy, long id, String commit, String ciRef) {
        legacy.insert("defect_fix", "id", id, "defect_id", "E2E-D-0007", "repo", "repo/len", "commit_sha", commit.repeat(40),
                "ci_ref", ciRef, "ci_conclusion", "success", "recorded_by", LegacyFixtures.SEED, "recorded_at",
                LegacyLedger.at("2026-11-06T02:00:00Z"), "imported", false);
    }

    @Test
    @DisplayName("REH3-IMP-03 a condition code of 200 and a CI reference of 2000 characters import; 201, 2001 and a title of 301 are reported with the limit (never cut)")
    void imp03_lengths() {
        LegacyLedger fits = LegacyFixtures.rich();
        insertCondition(fits, "OLD-C-200", "c".repeat(200));
        insertFix(fits, 31, "1", "r".repeat(2000));
        withRig(fits, ctx -> {
            assertThat(importer(ctx).importLegacy(TestOwners.OWNER).tables()).allMatch(TableCheck::matches);
            assertThat(count(ctx, "SELECT COUNT(*) FROM milestone_criterion WHERE id = 'OLD-C-200' AND code = ?",
                    "c".repeat(200))).isEqualTo(1);
            assertThat(count(ctx, "SELECT COUNT(*) FROM deliverable WHERE id = 31 AND check_ref = ?", "r".repeat(2000)))
                    .isEqualTo(1);
        });
        LegacyLedger tooLong = LegacyFixtures.rich();
        insertCondition(tooLong, "OLD-C-201", "c".repeat(201));
        insertFix(tooLong, 32, "2", "r".repeat(2001));
        tooLong.update("UPDATE " + tooLong.schema() + ".task SET title = ? WHERE id = 'OLD-T-1'", "x".repeat(301));
        withRig(tooLong, ctx -> {
            ValidationException refused = refused(ctx);

            assertThat(refused.issues()).extracting(ValidationIssue::path).containsExactlyInAnyOrder(
                    "milestone_criterion[OLD-C-201].code", "deliverable[32].check_ref", "task[OLD-T-1].title");
            assertThat(refused.issues()).allSatisfy(issue -> assertThat(issue.reason().name())
                    .isEqualTo("MUST_BE_AT_MOST_CHARS"));
            assertThat(refused.issues().stream().map(issue -> issue.reasonArgs().getFirst()).toList())
                    .containsExactlyInAnyOrder("200", "2000", "300");
            assertNothingWritten(ctx);
        });
    }

    @Test
    @DisplayName("REH3-IMP-04 a NOT NULL text that identifies a row stays refused when it is '' in 1.x (release, repo): reported, nothing written, no value invented")
    void imp04_emptyIdentifyingTexts() {
        withRig(LegacyFixtures.withEmptyIdentifyingTexts(LegacyFixtures.rich()), ctx -> {
            ValidationException refused = refused(ctx);

            assertThat(refused.issues()).extracting(ValidationIssue::path).containsExactlyInAnyOrder(
                    "milestone_criterion[OLD-C-NORELEASE].milestone_name", "deliverable[30].location");
            assertThat(refused.issues()).allSatisfy(issue -> assertThat(issue.reason().name()).isEqualTo("REQUIRED"));
            assertNothingWritten(ctx);
        });
    }

    @Test
    @DisplayName("REH3-ID-03 an imported task is read, edited, moved and linked by the id 1.x gave it (AGS:A07-a); the id in the other case is NOT_FOUND and nothing is written")
    void id03_exactIdsAfterTheImport() {
        withRig(LegacyFixtures.withRealShapes(LegacyFixtures.rich()), ctx -> {
            importer(ctx).importLegacy(TestOwners.OWNER);
            String author = "claude:reh3-author";
            ctx.enroll(author);
            ctx.bean(GrantService.class).grant(new GrantCommand(author, "ags", List.of("task"), null, null),
                    TestOwners.OWNER);
            String id = LegacyFixtures.REAL_TASK_LOWER_SUFFIX;
            String other = id.toLowerCase(Locale.ROOT);
            DetailService detail = ctx.bean(DetailService.class);
            SaveService save = ctx.bean(SaveService.class);

            assertThat(detail.task(id).id()).isEqualTo(id);
            Expect.failure(() -> detail.task(other), LookupCode.NOT_FOUND);
            SaveResult edited = save.save(author, new SaveCommand(Scenario.uuid(), "task", id, 1L, Fields.of("title", "edited"),
                    Scenario.REASON));
            assertThat(edited.id()).isEqualTo(id);
            assertThat(edited.version()).isEqualTo(2);
            Expect.failure(() -> save.save(author, new SaveCommand(Scenario.uuid(), "task", other, 1L,
                    Fields.of("title", "edited again"), Scenario.REASON)), LookupCode.NOT_FOUND);
            assertThat(ctx.bean(TransitionService.class).transition(author, new TransitionCommand(Scenario.uuid(), "task", id, 2L,
                    "ACTIVE", Scenario.REASON, null, null, null)).version()).isEqualTo(3);
            Expect.failure(() -> ctx.bean(TransitionService.class).transition(author, new TransitionCommand(Scenario.uuid(), "task",
                    other, 3L, "BLOCKED", Scenario.REASON, null, null, null)), LookupCode.NOT_FOUND);
            LinkService links = ctx.bean(LinkService.class);
            assertThat(links.link(author, new LinkCommand(Scenario.uuid(), "criterion_task", LegacyFixtures.REAL_CONDITION_LONG, id,
                    Scenario.REASON, null)).id()).isEqualTo(LegacyFixtures.REAL_CONDITION_LONG + "|" + id);
            Expect.failure(() -> links.link(author, new LinkCommand(Scenario.uuid(), "criterion_task",
                    LegacyFixtures.REAL_CONDITION_LONG, other, Scenario.REASON, null)), LookupCode.NOT_FOUND);

            assertThat(count(ctx, "SELECT COUNT(*) FROM task WHERE id = ? AND title = 'edited' AND state = 'ACTIVE'", id))
                    .isEqualTo(1);
            assertThat(count(ctx, "SELECT COUNT(*) FROM history WHERE entity = 'TASK' AND entity_id = ? AND op <> 'IMPORTED_NOTE'",
                    other)).isZero();
        });
    }

    @Test
    @DisplayName("REH3-TXT-05 a task, a defect and a condition that had '' in 1.x read as \"\" (as 1.x returned them)")
    void txt05_emptyReadsAsEmpty() {
        withRig(LegacyFixtures.withRealShapes(LegacyFixtures.rich()), ctx -> {
            importer(ctx).importLegacy(TestOwners.OWNER);
            DetailService detail = ctx.bean(DetailService.class);

            DetailService.TaskDetail task = detail.task(LegacyFixtures.REAL_TASK_EMPTY_BOTH);
            assertThat(task.purpose()).isEmpty();
            assertThat(task.successCondition()).isEmpty();
            assertThat(detail.task(LegacyFixtures.REAL_TASK_EMPTY_ONE).purpose()).isEmpty();
            assertThat(detail.task(LegacyFixtures.REAL_TASK_EMPTY_ONE).successCondition()).isEqualTo("it is done");
            DetailService.IssueDetail issue = detail.issue(LegacyFixtures.REAL_DEFECT);
            assertThat(issue.symptom()).isEmpty();
            assertThat(issue.rootCause()).isEmpty();
            var criteria = ctx.bean(ListService.class).criteria(new CriteriaQuery("ags", null, null, 50, null));
            assertThat(criteria.items()).filteredOn(item -> LegacyFixtures.REAL_CONDITION_LOWER.equals(item.id()))
                    .singleElement().satisfies(item -> assertThat(item.criterionText()).isEmpty());
        });
    }

    @Test
    @DisplayName("REH3-ID-08 / REH3-LEN-01 the schema: id 120, id_key 360 NOT NULL and unique in task and milestone_criterion, code 200, check_ref 2000, and the five long texts are nullable")
    void id08_theColumns() {
        withRig(LegacyFixtures.rich(), ctx -> {
            assertThat(size(ctx, "task", "id")).isEqualTo(120);
            assertThat(size(ctx, "task", "id_key")).isEqualTo(360);
            assertThat(size(ctx, "milestone_criterion", "id")).isEqualTo(120);
            assertThat(size(ctx, "milestone_criterion", "id_key")).isEqualTo(360);
            assertThat(size(ctx, "milestone_criterion", "code")).isEqualTo(200);
            assertThat(size(ctx, "deliverable", "check_ref")).isEqualTo(2000);
            for (String[] column : new String[][] {{"task", "purpose"}, {"task", "success_condition"},
                    {"issue", "symptom"}, {"issue", "root_cause"}, {"milestone_criterion", "criterion_text"}}) {
                assertThat(nullable(ctx, column[0], column[1])).as(column[0] + "." + column[1]).isTrue();
            }
            assertThat(nullable(ctx, "task", "id_key")).isFalse();
            assertThat(nullable(ctx, "milestone_criterion", "id_key")).isFalse();
        });
    }

    private static int size(LedgerAppContext ctx, String table, String column) {
        return ctx.jdbc().sql("SELECT character_maximum_length FROM information_schema.columns WHERE table_schema = "
                + "current_schema() AND table_name = ? AND column_name = ?").param(table).param(column)
                .query(Integer.class).single();
    }

    private static boolean nullable(LedgerAppContext ctx, String table, String column) {
        return "YES".equals(ctx.jdbc().sql("SELECT is_nullable FROM information_schema.columns WHERE table_schema = "
                + "current_schema() AND table_name = ? AND column_name = ?").param(table).param(column)
                .query(String.class).single());
    }
}
