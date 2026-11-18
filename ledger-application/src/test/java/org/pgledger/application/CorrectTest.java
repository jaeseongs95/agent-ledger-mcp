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

/** SVC-COR-01 .. SVC-COR-05: corrections. */
@LedgerServiceTest
class CorrectTest {

    private CorrectResult correct(Scenario s, long historyId) {
        return s.bean(CorrectService.class).correct(Scenario.AUTHOR, new CorrectCommand(Scenario.uuid(), historyId,
                Scenario.REASON));
    }

    @Test
    @DisplayName("SVC-COR-01: a correction is a new row that points at the wrong one, which stays as it was")
    void cor01_correction(Scenario s) {
        SaveResult d = s.newDefect("d");
        HistoryEntry before = s.historyRow(d.historyId());
        CorrectResult result = correct(s, d.historyId());
        assertThat(result.corrects()).isEqualTo(d.historyId());
        HistoryEntry row = s.historyRow(result.historyId());
        assertThat(row.op()).isEqualTo(HistoryOp.CORRECT);
        assertThat(row.correctsId()).isEqualTo(d.historyId());
        assertThat(row.beforeData()).isNull();
        assertThat(s.historyRow(d.historyId())).isEqualTo(before);
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
                new Row(HistoryEntity.DEFECT, s -> s.newDefect("d").historyId()),
                new Row(HistoryEntity.DEFECT_FIX, s -> s.fix(Scenario.AUTHOR, s.newDefect("d").id(),
                        Scenario.commit('a')).historyId()),
                new Row(HistoryEntity.DEFECT_CASE, s -> s.bean(RecordCaseService.class).recordCase(Scenario.AUTHOR,
                        new RecordCaseCommand(Scenario.uuid(), s.newDefect("d").id(), "seen", Scenario.REASON, null, null, null))
                        .historyId()),
                new Row(HistoryEntity.DEFECT_TASK, s -> s.bean(LinkService.class).link(Scenario.AUTHOR,
                        new LinkCommand(Scenario.uuid(), "defect_task", s.newDefect("d").id(), s.newTask("t").id(),
                                Scenario.REASON, null)).historyId()),
                new Row(HistoryEntity.DEFECT_RELATION, s -> s.bean(LinkService.class).link(Scenario.AUTHOR,
                        new LinkCommand(Scenario.uuid(), "blocks", s.newDefect("a").id(), s.newDefect("b").id(),
                                Scenario.REASON, null)).historyId()),
                new Row(HistoryEntity.TASK, s -> s.newTask("t").historyId()),
                new Row(HistoryEntity.CONDITION, s -> s.newCondition("1", "r", "c").historyId()),
                new Row(HistoryEntity.CONDITION_LINK, s -> s.bean(LinkService.class).link(Scenario.AUTHOR,
                        new LinkCommand(Scenario.uuid(), "condition_task", s.newCondition("1", "r", "c").id(),
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
        SaveResult d = s.verifiedDefect("d");
        long verification = s.history(HistoryEntity.DEFECT, d.id()).stream()
                .filter(h -> h.op() == HistoryOp.TRANSITION && "VERIFIED".equals(h.toState())).findFirst()
                .orElseThrow().id();
        Expect.failure(() -> correct(s, verification), TrustCode.VERIFY_NEEDS_REVIEWER_LOGIN);
        CorrectResult result = s.bean(ReviewService.class).reviewCorrect(Scenario.REVIEWER, new CorrectCommand(Scenario.uuid(),
                verification, Scenario.REASON));
        assertThat(s.historyRow(result.historyId()).login()).isEqualTo("ledger_reviewer");
    }

    @Test
    @DisplayName("SVC-COR-05: a correction can itself be corrected (unchanged by design)")
    void cor05_correctACorrection(Scenario s) {
        SaveResult d = s.newDefect("d");
        CorrectResult first = correct(s, d.historyId());
        assertThat(correct(s, first.historyId()).corrects()).isEqualTo(first.historyId());
    }

    @Test
    @DisplayName("SVC-COR-05: a replay returns the stored correction")
    void cor05_replay(Scenario s) {
        SaveResult d = s.newDefect("d");
        CorrectCommand command = new CorrectCommand(Scenario.uuid(), d.historyId(), Scenario.REASON);
        CorrectResult first = s.bean(CorrectService.class).correct(Scenario.AUTHOR, command);
        assertThat(s.bean(CorrectService.class).correct(Scenario.AUTHOR, command)).isEqualTo(first);
    }

    @Test
    @DisplayName("SVC-COR-05: a short reason")
    void cor05_shortReason(Scenario s) {
        SaveResult d = s.newDefect("d");
        Expect.failure(() -> s.bean(CorrectService.class).correct(Scenario.AUTHOR, new CorrectCommand(Scenario.uuid(),
                d.historyId(), "short")), ValidationCode.REASON_INVALID);
    }

    @Test
    @DisplayName("REM-002 (D-0051): correcting a cross-project link, relation, condition link, merge or chained correction requires grants on both projects")
    void rem002_crossProjectEndpointsRequireBothGrants(Scenario s) {
        String p2 = s.otherProject();
        String p1Only = s.actorWith("defect", "task");
        String both = s.actorWith("defect", "task");
        s.grant(both, p2, "defect", "task");

        SaveResult d1 = s.newDefect("d1");
        SaveResult c1 = s.newCondition("c1", "r", "code1");

        var d2Fields = s.defectFields("d2");
        d2Fields.put("project_id", p2);
        SaveResult d2 = s.bean(SaveService.class).save(both, new SaveCommand(Scenario.uuid(), "defect", null, 0L,
                Fields.of(d2Fields), Scenario.REASON));
        var t2Fields = s.taskFields("t2");
        t2Fields.put("project_id", p2);
        SaveResult t2 = s.bean(SaveService.class).save(both, new SaveCommand(Scenario.uuid(), "task", null, 0L,
                Fields.of(t2Fields), Scenario.REASON));

        long defectTaskRow = s.bean(LinkService.class).link(both, new LinkCommand(Scenario.uuid(), "defect_task",
                d1.id(), t2.id(), Scenario.REASON, null)).historyId();
        long relationRow = s.bean(LinkService.class).link(both, new LinkCommand(Scenario.uuid(), "blocks",
                d1.id(), d2.id(), Scenario.REASON, null)).historyId();
        long condTaskRow = s.bean(LinkService.class).link(both, new LinkCommand(Scenario.uuid(), "condition_task",
                c1.id(), t2.id(), Scenario.REASON, null)).historyId();
        long condDefectRow = s.bean(LinkService.class).link(both, new LinkCommand(Scenario.uuid(), "condition_defect",
                c1.id(), d2.id(), Scenario.REASON, null)).historyId();
        long mergeRow = s.bean(TransitionService.class).transition(both, new TransitionCommand(Scenario.uuid(),
                "defect", d1.id(), 1L, "MERGED", Scenario.REASON, null, null, d2.id())).historyId();
        long mergedCorrectionRow = s.bean(CorrectService.class).correct(both, new CorrectCommand(Scenario.uuid(),
                mergeRow, Scenario.REASON)).historyId();

        for (long id : List.of(defectTaskRow, relationRow, condTaskRow, condDefectRow, mergeRow, mergedCorrectionRow)) {
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
        SaveResult d = s.verifiedDefect("d");
        long verification = s.history(HistoryEntity.DEFECT, d.id()).stream()
                .filter(h -> h.op() == HistoryOp.TRANSITION && "VERIFIED".equals(h.toState())).findFirst()
                .orElseThrow().id();
        long firstCorrection = s.bean(ReviewService.class).reviewCorrect(Scenario.REVIEWER,
                new CorrectCommand(Scenario.uuid(), verification, Scenario.REASON)).historyId();

        Expect.failure(() -> correct(s, firstCorrection), TrustCode.VERIFY_NEEDS_REVIEWER_LOGIN);
        String reviewerWithoutVerify = s.actorWith("defect");
        Expect.failure(() -> s.bean(ReviewService.class).reviewCorrect(reviewerWithoutVerify,
                new CorrectCommand(Scenario.uuid(), firstCorrection, Scenario.REASON)), TrustCode.SESSION_NOT_GRANTED);

        CorrectResult chained = s.bean(ReviewService.class).reviewCorrect(Scenario.REVIEWER,
                new CorrectCommand(Scenario.uuid(), firstCorrection, Scenario.REASON));
        assertThat(chained.corrects()).isEqualTo(firstCorrection);
    }

    @Test
    @DisplayName("REM-009: correcting a CONDITION_LINK whose ConditionId and TaskId contain '|' resolves the true endpoints")
    void rem009_conditionLinkWithPipesInIdsResolvesTrueEndpoints(Scenario s) {
        String p2 = s.otherProject();
        String p1Only = s.actorWith("defect", "task");
        String both = s.actorWith("defect", "task");
        s.grant(both, p2, "defect", "task");

        var c1 = s.bean(SaveService.class).save(both, new SaveCommand(Scenario.uuid(), "condition", "C|1", 0L,
                Fields.of(s.conditionFields("r1", "code1")), Scenario.REASON));
        var t2Fields = s.taskFields("t2");
        t2Fields.put("project_id", p2);
        var t2 = s.bean(SaveService.class).save(both, new SaveCommand(Scenario.uuid(), "task", "T|2", 0L,
                Fields.of(t2Fields), Scenario.REASON));

        long linkHistoryId = s.bean(LinkService.class).link(both, new LinkCommand(Scenario.uuid(), "condition_task",
                c1.id(), t2.id(), Scenario.REASON, null)).historyId();

        LedgerException refusal = Expect.failure(() -> s.bean(CorrectService.class).correct(p1Only,
                new CorrectCommand(Scenario.uuid(), linkHistoryId, Scenario.REASON)), TrustCode.SESSION_NOT_GRANTED);
        assertThat(refusal.details()).containsEntry("project", List.of(p2));
        assertThat(s.bean(CorrectService.class).correct(both, new CorrectCommand(Scenario.uuid(), linkHistoryId,
                Scenario.REASON)).corrects()).isEqualTo(linkHistoryId);
    }
}
