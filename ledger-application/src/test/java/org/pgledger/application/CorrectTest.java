// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.application.json.LedgerJson;
import org.pgledger.application.support.Expect;
import org.pgledger.application.support.Fields;
import org.pgledger.application.support.LedgerServiceTest;
import org.pgledger.application.support.Scenario;
import org.pgledger.application.write.CorrectCommand;
import org.pgledger.application.write.CorrectService;
import org.pgledger.application.write.LinkCommand;
import org.pgledger.application.write.LinkService;
import org.pgledger.application.write.RecordCaseCommand;
import org.pgledger.application.write.RecordCaseService;
import org.pgledger.application.write.ReviewService;
import org.pgledger.application.write.SaveCommand;
import org.pgledger.application.write.SaveService;
import org.pgledger.application.write.TransitionCommand;
import org.pgledger.application.write.TransitionService;
import org.pgledger.application.write.WriteResult.CorrectResult;
import org.pgledger.application.write.WriteResult.SaveResult;
import org.pgledger.domain.error.LedgerException;
import org.pgledger.domain.error.LookupCode;
import org.pgledger.domain.error.TrustCode;
import org.pgledger.domain.error.ValidationCode;
import org.pgledger.domain.model.HistoryEntry;
import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.rule.HistoryOp;
import tools.jackson.databind.json.JsonMapper;

/** SVC-COR-01 .. SVC-COR-05, F7, F8: corrections. */
@LedgerServiceTest
class CorrectTest {

    private CorrectResult correct(Scenario s, long historyId) {
        return s.bean(CorrectService.class).correct(Scenario.AUTHOR, new CorrectCommand(Scenario.uuid(), historyId,
                Scenario.REASON));
    }

    @Test
    @DisplayName("SVC-COR-01: a correction is a new row that points at the wrong one, which stays as it was")
    void cor01_correction(Scenario s) {
        SaveResult i = s.newIssue("i");
        HistoryEntry before = s.historyRow(i.historyId());
        CorrectResult result = correct(s, i.historyId());
        assertThat(result.corrects()).isEqualTo(i.historyId());
        HistoryEntry row = s.historyRow(result.historyId());
        assertThat(row.op()).isEqualTo(HistoryOp.CORRECT);
        assertThat(row.correctsId()).isEqualTo(i.historyId());
        assertThat(row.beforeData()).isNull();
        assertThat(s.historyRow(i.historyId())).isEqualTo(before);
        Map<?, ?> keys = new JsonMapper().readValue(s.bean(LedgerJson.class).write(result).text(), Map.class);
        assertThat(keys.keySet()).isEqualTo(Set.of("status", "corrects", "history_id", "author", "login"));
    }

    @Test
    @DisplayName("SVC-COR-02: an unknown history id")
    void cor02_unknown(Scenario s) {
        Expect.failure(() -> correct(s, 999_999_999L), LookupCode.NOT_FOUND);
    }

    /** A history row of one kind of entity, made through the services. */
    record Row(HistoryEntity entity, Function<Scenario, Long> make) {
        @Override
        public String toString() {
            return entity.token();
        }
    }

    static Stream<Row> everyEntity() {
        return Stream.of(
                new Row(HistoryEntity.ISSUE, s -> s.newIssue("i").historyId()),
                new Row(HistoryEntity.SPEC, s -> s.newSpec("sp").historyId()),
                new Row(HistoryEntity.TASK, s -> s.newTask("t").historyId()),
                new Row(HistoryEntity.CRITERION, s -> s.newCriterion("1", "r", "c").historyId()),
                new Row(HistoryEntity.DELIVERABLE, s -> s.deliver(Scenario.AUTHOR, s.newIssue("i").id(),
                        Scenario.ref('a')).historyId()),
                new Row(HistoryEntity.ISSUE_CASE, s -> s.bean(RecordCaseService.class).recordCase(Scenario.AUTHOR,
                        new RecordCaseCommand(Scenario.uuid(), s.newIssue("i").id(), "seen", Scenario.REASON, null,
                                null, null)).historyId()),
                new Row(HistoryEntity.ISSUE_TASK, s -> s.bean(LinkService.class).link(Scenario.AUTHOR,
                        new LinkCommand(Scenario.uuid(), "issue_task", s.newIssue("i").id(), s.newTask("t").id(),
                                Scenario.REASON, null)).historyId()),
                new Row(HistoryEntity.ISSUE_SPEC, s -> s.bean(LinkService.class).link(Scenario.AUTHOR,
                        new LinkCommand(Scenario.uuid(), "issue_spec", s.newIssue("i").id(), s.newSpec("sp").id(),
                                Scenario.REASON, null)).historyId()),
                new Row(HistoryEntity.SPEC_TASK, s -> s.bean(LinkService.class).link(Scenario.AUTHOR,
                        new LinkCommand(Scenario.uuid(), "spec_task", s.newSpec("sp").id(), s.newTask("t").id(),
                                Scenario.REASON, null)).historyId()),
                new Row(HistoryEntity.ISSUE_RELATION, s -> s.bean(LinkService.class).link(Scenario.AUTHOR,
                        new LinkCommand(Scenario.uuid(), "blocks", s.newIssue("a").id(), s.newIssue("b").id(),
                                Scenario.REASON, null)).historyId()),
                new Row(HistoryEntity.CRITERION_LINK, s -> s.bean(LinkService.class).link(Scenario.AUTHOR,
                        new LinkCommand(Scenario.uuid(), "criterion_task", s.newCriterion("1", "r", "c").id(),
                                s.newTask("t").id(), Scenario.REASON, null)).historyId()));
    }

    @ParameterizedTest
    @MethodSource("everyEntity")
    @DisplayName("SVC-COR-03: every kind of entity can be corrected, the project is found from its id")
    void cor03_everyEntity(Row row, Scenario s) {
        long wrong = row.make().apply(s);
        CorrectResult result = correct(s, wrong);
        HistoryEntry original = s.historyRow(wrong);
        HistoryEntry correction = s.historyRow(result.historyId());
        assertThat(correction.entity()).isEqualTo(row.entity());
        assertThat(correction.entityId()).isEqualTo(original.entityId());
    }

    @Test
    @DisplayName("SVC-COR-04: a verification is corrected through the reviewer channel only")
    void cor04_verification(Scenario s) {
        SaveResult i = s.verifiedIssue("i");
        long verification = s.history(HistoryEntity.ISSUE, i.id()).stream()
                .filter(h -> h.op() == HistoryOp.TRANSITION && "VERIFIED".equals(h.toState())).findFirst()
                .orElseThrow().id();
        Expect.failure(() -> correct(s, verification), TrustCode.VERIFY_NEEDS_REVIEWER_LOGIN);
        CorrectResult result = s.bean(ReviewService.class).reviewCorrect(Scenario.REVIEWER, new CorrectCommand(
                Scenario.uuid(), verification, Scenario.REASON));
        assertThat(s.historyRow(result.historyId()).login()).isEqualTo("ledger_reviewer");
    }

    @Test
    @DisplayName("SVC-COR-05: a correction can itself be corrected (unchanged by design)")
    void cor05_correctACorrection(Scenario s) {
        SaveResult i = s.newIssue("i");
        CorrectResult first = correct(s, i.historyId());
        assertThat(correct(s, first.historyId()).corrects()).isEqualTo(first.historyId());
    }

    @Test
    @DisplayName("SVC-COR-05: a replay returns the stored correction")
    void cor05_replay(Scenario s) {
        SaveResult i = s.newIssue("i");
        CorrectCommand command = new CorrectCommand(Scenario.uuid(), i.historyId(), Scenario.REASON);
        CorrectResult first = s.bean(CorrectService.class).correct(Scenario.AUTHOR, command);
        assertThat(s.bean(CorrectService.class).correct(Scenario.AUTHOR, command)).isEqualTo(first);
    }

    @Test
    @DisplayName("SVC-COR-05: a short reason")
    void cor05_shortReason(Scenario s) {
        SaveResult i = s.newIssue("i");
        Expect.failure(() -> s.bean(CorrectService.class).correct(Scenario.AUTHOR, new CorrectCommand(Scenario.uuid(),
                i.historyId(), "short")), ValidationCode.REASON_INVALID);
    }

    @Test
    @DisplayName("REM-002 (D-0051): correcting a cross-project link, relation, criterion link, merge or chained correction requires grants on both projects")
    void rem002_crossProjectEndpointsRequireBothGrants(Scenario s) {
        String p2 = s.otherProject();
        String p1Only = s.actorWith("issue", "spec", "task");
        String both = s.actorWith("issue", "spec", "task");
        s.grant(both, p2, "issue", "spec", "task");

        SaveResult i1 = s.newIssue("i1");
        SaveResult sp1 = s.newSpec("sp1");
        SaveResult c1 = s.newCriterion("c1", "r", "code1");

        var i2Fields = s.issueFields("i2");
        i2Fields.put("project_id", p2);
        SaveResult i2 = s.bean(SaveService.class).save(both, new SaveCommand(Scenario.uuid(), "issue", null, 0L,
                Fields.of(i2Fields), Scenario.REASON));
        var sp2Fields = s.specFields("sp2");
        sp2Fields.put("project_id", p2);
        SaveResult sp2 = s.bean(SaveService.class).save(both, new SaveCommand(Scenario.uuid(), "spec", null, 0L,
                Fields.of(sp2Fields), Scenario.REASON));
        var t2Fields = s.taskFields("t2");
        t2Fields.put("project_id", p2);
        SaveResult t2 = s.bean(SaveService.class).save(both, new SaveCommand(Scenario.uuid(), "task", null, 0L,
                Fields.of(t2Fields), Scenario.REASON));

        long issueTaskRow = s.bean(LinkService.class).link(both, new LinkCommand(Scenario.uuid(), "issue_task",
                i1.id(), t2.id(), Scenario.REASON, null)).historyId();
        long issueSpecRow = s.bean(LinkService.class).link(both, new LinkCommand(Scenario.uuid(), "issue_spec",
                i1.id(), sp2.id(), Scenario.REASON, null)).historyId();
        long specTaskRow = s.bean(LinkService.class).link(both, new LinkCommand(Scenario.uuid(), "spec_task",
                sp1.id(), t2.id(), Scenario.REASON, null)).historyId();
        long relationRow = s.bean(LinkService.class).link(both, new LinkCommand(Scenario.uuid(), "blocks",
                i1.id(), i2.id(), Scenario.REASON, null)).historyId();
        long critTaskRow = s.bean(LinkService.class).link(both, new LinkCommand(Scenario.uuid(), "criterion_task",
                c1.id(), t2.id(), Scenario.REASON, null)).historyId();
        long critIssueRow = s.bean(LinkService.class).link(both, new LinkCommand(Scenario.uuid(), "criterion_issue",
                c1.id(), i2.id(), Scenario.REASON, null)).historyId();
        long critSpecRow = s.bean(LinkService.class).link(both, new LinkCommand(Scenario.uuid(), "criterion_spec",
                c1.id(), sp2.id(), Scenario.REASON, null)).historyId();
        long mergeRow = s.bean(TransitionService.class).transition(both, new TransitionCommand(Scenario.uuid(),
                "issue", i1.id(), 1L, "MERGED", Scenario.REASON, null, null, i2.id())).historyId();
        long mergedCorrectionRow = s.bean(CorrectService.class).correct(both, new CorrectCommand(Scenario.uuid(),
                mergeRow, Scenario.REASON)).historyId();

        for (long id : List.of(issueTaskRow, issueSpecRow, specTaskRow, relationRow, critTaskRow, critIssueRow,
                critSpecRow, mergeRow, mergedCorrectionRow)) {
            LedgerException refusal = Expect.failure(() -> s.bean(CorrectService.class).correct(p1Only,
                    new CorrectCommand(Scenario.uuid(), id, Scenario.REASON)), TrustCode.SESSION_NOT_GRANTED);
            assertThat(refusal.details()).containsEntry("project", List.of(p2));
            assertThat(s.bean(CorrectService.class).correct(both, new CorrectCommand(Scenario.uuid(), id,
                    Scenario.REASON)).corrects()).isEqualTo(id);
        }
    }

    @Test
    @DisplayName("REM-009: chained correction of a verification still requires reviewer channel and verify scope")
    void rem009_chainedCorrectionOfVerificationRequiresReviewerAndVerifyScope(Scenario s) {
        SaveResult i = s.verifiedIssue("i");
        long verification = s.history(HistoryEntity.ISSUE, i.id()).stream()
                .filter(h -> h.op() == HistoryOp.TRANSITION && "VERIFIED".equals(h.toState())).findFirst()
                .orElseThrow().id();
        long firstCorrection = s.bean(ReviewService.class).reviewCorrect(Scenario.REVIEWER,
                new CorrectCommand(Scenario.uuid(), verification, Scenario.REASON)).historyId();

        Expect.failure(() -> correct(s, firstCorrection), TrustCode.VERIFY_NEEDS_REVIEWER_LOGIN);
        String reviewerWithoutVerify = s.actorWith("issue");
        Expect.failure(() -> s.bean(ReviewService.class).reviewCorrect(reviewerWithoutVerify,
                new CorrectCommand(Scenario.uuid(), firstCorrection, Scenario.REASON)), TrustCode.SESSION_NOT_GRANTED);

        CorrectResult chained = s.bean(ReviewService.class).reviewCorrect(Scenario.REVIEWER,
                new CorrectCommand(Scenario.uuid(), firstCorrection, Scenario.REASON));
        assertThat(chained.corrects()).isEqualTo(firstCorrection);
    }

    @Test
    @DisplayName("F8: correcting SPEC_TASK or CRITERION_LINK whose SpecId/CriterionId and TaskId contain '|' resolves the true endpoints, even after unlink")
    void f8_compositeLinkWithPipesInIdsResolvesTrueEndpoints(Scenario s) {
        String p2 = s.otherProject();
        String p1Only = s.actorWith("issue", "spec", "task");
        String both = s.actorWith("issue", "spec", "task");
        s.grant(both, p2, "issue", "spec", "task");

        var sp1 = s.bean(SaveService.class).save(both, new SaveCommand(Scenario.uuid(), "spec", "SP|1", 0L,
                Fields.of(s.specFields("sp1")), Scenario.REASON));
        var c1 = s.bean(SaveService.class).save(both, new SaveCommand(Scenario.uuid(), "criterion", "C|1", 0L,
                Fields.of(s.criterionFields("r1", "code1")), Scenario.REASON));
        var t2Fields = s.taskFields("t2");
        t2Fields.put("project_id", p2);
        var t2 = s.bean(SaveService.class).save(both, new SaveCommand(Scenario.uuid(), "task", "T|2", 0L,
                Fields.of(t2Fields), Scenario.REASON));

        long critLinkHistoryId = s.bean(LinkService.class).link(both, new LinkCommand(Scenario.uuid(), "criterion_task",
                c1.id(), t2.id(), Scenario.REASON, null)).historyId();
        long specTaskLinkHistoryId = s.bean(LinkService.class).link(both, new LinkCommand(Scenario.uuid(), "spec_task",
                sp1.id(), t2.id(), Scenario.REASON, null)).historyId();
        long specTaskUnlinkHistoryId = s.bean(LinkService.class).link(both, new LinkCommand(Scenario.uuid(),
                "spec_task", sp1.id(), t2.id(), Scenario.REASON, true)).historyId();

        for (long id : List.of(critLinkHistoryId, specTaskLinkHistoryId, specTaskUnlinkHistoryId)) {
            LedgerException refusal = Expect.failure(() -> s.bean(CorrectService.class).correct(p1Only,
                    new CorrectCommand(Scenario.uuid(), id, Scenario.REASON)), TrustCode.SESSION_NOT_GRANTED);
            assertThat(refusal.details()).containsEntry("project", List.of(p2));
            assertThat(s.bean(CorrectService.class).correct(both, new CorrectCommand(Scenario.uuid(), id,
                    Scenario.REASON)).corrects()).isEqualTo(id);
        }
    }

    @Test
    @DisplayName("F8: unauthenticated caller probing an unknown or non-existent history row is refused by attestation before lookup")
    void f8_unauthenticatedCallerRefusedBeforeTargetLookup(Scenario s) {
        Expect.failure(() -> s.bean(CorrectService.class).correct(null, new CorrectCommand(Scenario.uuid(),
                999_999_999L, Scenario.REASON)), TrustCode.HOST_ATTESTATION_REQUIRED);
    }

    @Test
    @DisplayName("F7: correcting a free-text task or spec after it moved to another project requires a grant on its current project")
    void f7_movedFreeTextTaskAndSpecReauthorizedAgainstCurrentProject(Scenario s) {
        String p2 = s.otherProject();
        String p1Only = s.actorWith("spec", "task");
        String both = s.actorWith("spec", "task");
        s.grant(both, p2, "spec", "task");

        var freeSpec = s.bean(SaveService.class).save(both, new SaveCommand(Scenario.uuid(), "spec",
                "FREE-SPEC-" + Scenario.uuid().substring(0, 8), 0L, Fields.of(s.specFields("free spec")),
                Scenario.REASON));
        var freeTask = s.bean(SaveService.class).save(both, new SaveCommand(Scenario.uuid(), "task",
                "FREE-TASK-" + Scenario.uuid().substring(0, 8), 0L, Fields.of(s.taskFields("free task")),
                Scenario.REASON));

        s.bean(SaveService.class).save(both, new SaveCommand(Scenario.uuid(), "spec", freeSpec.id(), 1L,
                Fields.of("project_id", p2), Scenario.REASON));
        s.bean(SaveService.class).save(both, new SaveCommand(Scenario.uuid(), "task", freeTask.id(), 1L,
                Fields.of("project_id", p2), Scenario.REASON));

        for (long historyId : List.of(freeSpec.historyId(), freeTask.historyId())) {
            LedgerException refusal = Expect.failure(() -> s.bean(CorrectService.class).correct(p1Only,
                    new CorrectCommand(Scenario.uuid(), historyId, Scenario.REASON)), TrustCode.SESSION_NOT_GRANTED);
            assertThat(refusal.details()).containsEntry("project", List.of(p2));
            assertThat(s.bean(CorrectService.class).correct(both, new CorrectCommand(Scenario.uuid(), historyId,
                    Scenario.REASON)).corrects()).isEqualTo(historyId);
        }
    }
}
