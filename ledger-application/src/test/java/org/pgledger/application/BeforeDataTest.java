// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.application.history.Snapshot;
import org.pgledger.application.support.Fields;
import org.pgledger.application.support.LedgerServiceTest;
import org.pgledger.application.support.MergeFixture;
import org.pgledger.application.support.Parallel;
import org.pgledger.application.support.Parallel.Outcome;
import org.pgledger.application.support.Scenario;
import org.pgledger.application.write.CorrectCommand;
import org.pgledger.application.write.CorrectService;
import org.pgledger.application.write.LinkCommand;
import org.pgledger.application.write.LinkService;
import org.pgledger.application.write.RecordCaseCommand;
import org.pgledger.application.write.RecordCaseService;
import org.pgledger.application.write.RecordDeliverableCommand;
import org.pgledger.application.write.RecordDeliverableService;
import org.pgledger.application.write.ReviewService;
import org.pgledger.application.write.SaveCommand;
import org.pgledger.application.write.SaveService;
import org.pgledger.application.write.TransitionService;
import org.pgledger.application.write.WriteResult.RecordDeliverableResult;
import org.pgledger.application.write.WriteResult.SaveResult;
import org.pgledger.database.repository.CriterionRepository;
import org.pgledger.database.repository.DeliverableRepository;
import org.pgledger.domain.error.ConflictCode;
import org.pgledger.domain.error.LedgerException;
import org.pgledger.domain.model.HistoryEntry;
import org.pgledger.domain.rule.DeliverableTarget;
import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.rule.HistoryOp;
import org.pgledger.domain.value.CodeValue;
import org.pgledger.domain.value.CriterionId;

/** SVC-BEF-01 .. SVC-BEF-11: {@code before_data}, the full row as it was replaced (SVC-BEF-12 is in the read test). */
@LedgerServiceTest
class BeforeDataTest {

    private HistoryEntry row(Scenario s, long historyId) {
        return s.historyRow(historyId);
    }

    @Test
    @DisplayName("SVC-BEF-01: a created row, a case, a link and a new deliverable have no before_data")
    void bef01_createsHaveNone(Scenario s) {
        SaveResult i = s.newIssue("i");
        SaveResult sp = s.newSpec("sp");
        SaveResult t = s.newTask("t");
        SaveResult c = s.newCriterion("1", "r", "c");
        long caseRow = s.bean(RecordCaseService.class).recordCase(Scenario.AUTHOR, new RecordCaseCommand(Scenario.uuid(),
                i.id(), "seen", Scenario.REASON, null, null, null)).historyId();
        long linkRow = s.bean(LinkService.class).link(Scenario.AUTHOR, new LinkCommand(Scenario.uuid(), "issue_task",
                i.id(), t.id(), Scenario.REASON, null)).historyId();
        long deliverableRow = s.deliver(Scenario.AUTHOR, i.id(), Scenario.ref('a')).historyId();
        List<Long> all = List.of(i.historyId(), sp.historyId(), t.historyId(), c.historyId(), caseRow, linkRow,
                deliverableRow);
        assertThat(all).allSatisfy(id -> assertThat(row(s, id).beforeData()).isNull());
    }

    @Test
    @DisplayName("SVC-BEF-02: an issue edit keeps the whole previous row")
    void bef02_issueEdit(Scenario s) {
        SaveResult i = s.newIssue("i");
        Snapshot.IssueRow expected = Snapshot.IssueRow.of(s.issue(i.id()));
        s.ctx().clock().set(java.time.Instant.parse("2026-11-05T15:00:00Z"));
        SaveResult edited = s.bean(SaveService.class).save(Scenario.ISSUE_ONLY, s.edit("issue", i.id(), 1,
                Map.of("title", "new title")));
        Snapshot.IssueRow before = s.before(edited.historyId(), Snapshot.IssueRow.class);
        assertThat(before).isEqualTo(expected);
        assertThat(before.version()).isEqualTo(1);
        assertThat(before.createdBy()).isEqualTo(Scenario.AUTHOR);
        assertThat(s.changes(edited.historyId()).entries()).containsKey("title");
        assertThat(Snapshot.IssueRow.of(s.issue(i.id()))).isNotEqualTo(before);
    }

    @Test
    @DisplayName("SVC-BEF-03: spec, task and criterion edits keep the previous row")
    void bef03_specTaskAndCriterionEdits(Scenario s) {
        SaveResult sp = s.newSpec("sp");
        SaveResult t = s.newTask("t");
        SaveResult c = s.newCriterion("1", "r", "c");
        Snapshot.SpecRow spec = Snapshot.SpecRow.of(s.spec(sp.id()));
        Snapshot.TaskRow task = Snapshot.TaskRow.of(s.task(t.id()));
        Snapshot.CriterionRow criterion = Snapshot.CriterionRow.of(s.bean(CriterionRepository.class)
                .findById(CriterionId.of(c.id())).orElseThrow());
        SaveResult specEdit = s.bean(SaveService.class).save(Scenario.AUTHOR, s.edit("spec", sp.id(), 1,
                Map.of("title", "sp2")));
        SaveResult taskEdit = s.bean(SaveService.class).save(Scenario.AUTHOR, s.edit("task", t.id(), 1,
                Map.of("title", "t2")));
        SaveResult criterionEdit = s.bean(SaveService.class).save(Scenario.AUTHOR, s.edit("criterion", c.id(), 1,
                Map.of("criterion_text", "changed")));
        assertThat(s.before(specEdit.historyId(), Snapshot.SpecRow.class)).isEqualTo(spec);
        assertThat(s.before(taskEdit.historyId(), Snapshot.TaskRow.class)).isEqualTo(task);
        assertThat(s.before(criterionEdit.historyId(), Snapshot.CriterionRow.class)).isEqualTo(criterion);
    }

    @Test
    @DisplayName("SVC-BEF-04: transitions keep the previous state and version, leaving MERGED keeps merged_into")
    void bef04_transitions(Scenario s) {
        SaveResult i = s.newIssue("i");
        SaveResult target = s.newIssue("target");
        var merged = MergeFixture.merge(s, i.id(), 1, target.id());
        Snapshot.IssueRow beforeMerge = s.before(merged.historyId(), Snapshot.IssueRow.class);
        assertThat(beforeMerge.status()).isEqualTo("OPEN");
        assertThat(beforeMerge.version()).isEqualTo(1);
        var reopened = s.bean(TransitionService.class).transition(Scenario.AUTHOR, s.move("issue", i.id(), 2, "OPEN",
                null));
        Snapshot.IssueRow beforeReopen = s.before(reopened.historyId(), Snapshot.IssueRow.class);
        assertThat(beforeReopen.status()).isEqualTo("MERGED");
        assertThat(beforeReopen.mergedInto()).isEqualTo(target.id());
        SaveResult sp = s.newSpec("sp");
        var specActive = s.bean(TransitionService.class).transition(Scenario.AUTHOR, s.move("spec", sp.id(), 1, "ACTIVE",
                null));
        assertThat(s.before(specActive.historyId(), Snapshot.SpecRow.class).status()).isEqualTo("PLANNED");
        SaveResult t = s.newTask("t");
        var active = s.bean(TransitionService.class).transition(Scenario.AUTHOR, s.move("task", t.id(), 1, "ACTIVE",
                null));
        assertThat(s.before(active.historyId(), Snapshot.TaskRow.class).state()).isEqualTo("PLANNED");
        SaveResult c = s.newCriterion("1", "r", "c");
        var met = s.bean(ReviewService.class).review(Scenario.REVIEWER, s.move("criterion", c.id(), 1, "MET",
                Scenario.EVIDENCE));
        assertThat(s.before(met.historyId(), Snapshot.CriterionRow.class).state()).isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("SVC-BEF-05: a first deliverable: the deliverable row has none, the issue transition keeps the OPEN row")
    void bef05_firstDeliverable(Scenario s) {
        SaveResult i = s.newIssue("i");
        Snapshot.IssueRow open = Snapshot.IssueRow.of(s.issue(i.id()));
        RecordDeliverableResult deliverable = s.deliver(Scenario.AUTHOR, i.id(), Scenario.ref('a'));
        assertThat(row(s, deliverable.historyId()).beforeData()).isNull();
        assertThat(s.before(deliverable.transitionHistoryId(), Snapshot.IssueRow.class)).isEqualTo(open);
    }

    @Test
    @DisplayName("SVC-BEF-05: a new deliverable by another session: the issue EDIT keeps the issue row")
    void bef05_anotherSession(Scenario s) {
        SaveResult i = s.resolvedIssue("i");
        Snapshot.IssueRow resolved = Snapshot.IssueRow.of(s.issue(i.id()));
        RecordDeliverableResult second = s.deliver(Scenario.REVIEWER, i.id(), Scenario.ref('b'));
        assertThat(row(s, second.transitionHistoryId()).op()).isEqualTo(HistoryOp.EDIT);
        assertThat(s.before(second.transitionHistoryId(), Snapshot.IssueRow.class)).isEqualTo(resolved);
    }

    @Test
    @DisplayName("SVC-BEF-05: a check update keeps the old deliverable row")
    void bef05_checkUpdate(Scenario s) {
        SaveResult i = s.newIssue("i");
        s.bean(RecordDeliverableService.class).recordDeliverable(Scenario.AUTHOR, new RecordDeliverableCommand(
                Scenario.uuid(), "issue", i.id(), "code_change", Scenario.ref('a'), Scenario.REASON, "repo/e2e",
                "Deliverable summary", "ci-1", "failed", null, null));
        Snapshot.DeliverableRow old = Snapshot.DeliverableRow.of(s.bean(DeliverableRepository.class)
                .findByTargetEntityAndTargetIdAndKindAndDeliverableRef(DeliverableTarget.ISSUE, i.id(),
                        CodeValue.of("code_change"), Scenario.ref('a')).orElseThrow());
        RecordDeliverableResult update = s.bean(RecordDeliverableService.class).recordDeliverable(Scenario.AUTHOR,
                new RecordDeliverableCommand(Scenario.uuid(), "issue", i.id(), "code_change", Scenario.ref('a'),
                        Scenario.REASON, "repo/e2e", "Deliverable summary", "ci-1", "passed", null, null));
        assertThat(s.before(update.historyId(), Snapshot.DeliverableRow.class)).isEqualTo(old);
    }

    @Test
    @DisplayName("SVC-BEF-05: mark_done=false: the issue EDIT keeps the issue row")
    void bef05_noMark(Scenario s) {
        SaveResult i = s.newIssue("i");
        Snapshot.IssueRow open = Snapshot.IssueRow.of(s.issue(i.id()));
        RecordDeliverableResult deliverable = s.bean(RecordDeliverableService.class).recordDeliverable(Scenario.AUTHOR,
                new RecordDeliverableCommand(Scenario.uuid(), "issue", i.id(), "code_change", Scenario.ref('a'),
                        Scenario.REASON, "repo/e2e", "Deliverable summary", null, null, null, false));
        assertThat(s.before(deliverable.transitionHistoryId(), Snapshot.IssueRow.class)).isEqualTo(open);
    }

    @Test
    @DisplayName("SVC-BEF-06: an unlink keeps the removed link row")
    void bef06_unlink(Scenario s) {
        SaveResult i = s.newIssue("i");
        SaveResult t = s.newTask("t");
        var linked = s.bean(LinkService.class).link(Scenario.AUTHOR, new LinkCommand(Scenario.uuid(), "issue_task",
                i.id(), t.id(), Scenario.REASON, null));
        var unlinked = s.bean(LinkService.class).link(Scenario.AUTHOR, new LinkCommand(Scenario.uuid(), "issue_task",
                i.id(), t.id(), Scenario.REASON, true));
        assertThat(row(s, linked.historyId()).beforeData()).isNull();
        assertThat(s.before(unlinked.historyId(), Snapshot.IssueTaskRow.class)).isEqualTo(
                new Snapshot.IssueTaskRow(i.id(), t.id(), Scenario.AUTHOR));
    }

    @Test
    @DisplayName("SVC-BEF-07: a case or a correction has none, and the case counter is no modification")
    void bef07_caseCounterIsNoModification(Scenario s) {
        SaveResult i = s.newIssue("i");
        var issue = s.issue(i.id());
        long issueHistory = s.history(HistoryEntity.ISSUE, i.id()).size();
        var recorded = s.bean(RecordCaseService.class).recordCase(Scenario.AUTHOR, new RecordCaseCommand(Scenario.uuid(),
                i.id(), "seen", Scenario.REASON, null, null, null));
        var corrected = s.bean(CorrectService.class).correct(Scenario.AUTHOR, new CorrectCommand(Scenario.uuid(),
                recorded.historyId(), Scenario.REASON));
        assertThat(row(s, recorded.historyId()).beforeData()).isNull();
        assertThat(row(s, corrected.historyId()).beforeData()).isNull();
        var after = s.issue(i.id());
        assertThat(after.caseSeq()).isEqualTo(issue.caseSeq() + 1);
        assertThat(after.version()).isEqualTo(issue.version());
        assertThat(after.updatedAt()).isEqualTo(issue.updatedAt());
        assertThat(s.history(HistoryEntity.ISSUE, i.id())).hasSize((int) issueHistory);
    }

    @Test
    @DisplayName("SVC-BEF-08: a write that fails after the row update leaves no history, no receipt, no change")
    void bef08_rollback(Scenario s) {
        SaveResult i = s.newIssue("i");
        long history = s.count("SELECT COUNT(*) FROM history");
        long receipts = s.count("SELECT COUNT(*) FROM request_receipt");
        long counter = s.count("SELECT issued_no FROM id_counter WHERE name = 'HISTORY'");
        String operationKey = Scenario.uuid();
        assertThatThrownBy(() -> s.ctx().tx(() -> {
            s.bean(SaveService.class).save(Scenario.AUTHOR, new SaveCommand(operationKey, "issue", i.id(), 1L,
                    Fields.of("title", "rolled back"), Scenario.REASON));
            throw new IllegalStateException("after the row update");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(s.count("SELECT COUNT(*) FROM history")).isEqualTo(history);
        assertThat(s.count("SELECT COUNT(*) FROM request_receipt")).isEqualTo(receipts);
        assertThat(s.count("SELECT issued_no FROM id_counter WHERE name = 'HISTORY'")).isEqualTo(counter);
        assertThat(s.issue(i.id()).title()).isEqualTo("i");
        assertThat(s.issue(i.id()).version()).isEqualTo(1);
    }

    @Test
    @DisplayName("SVC-BEF-09: two edits with the same expected version: one wins with the old row, one conflicts")
    void bef09_race(Scenario s) {
        SaveResult i = s.newIssue("i");
        List<Callable<SaveResult>> calls = List.of(
                () -> s.bean(SaveService.class).save(Scenario.AUTHOR, s.edit("issue", i.id(), 1,
                        Map.of("title", "from author"))),
                () -> s.bean(SaveService.class).save(Scenario.ISSUE_ONLY, s.edit("issue", i.id(), 1,
                        Map.of("title", "from the other"))));
        List<Outcome<SaveResult>> outcomes = Parallel.all(calls);
        Outcome<SaveResult> winner = outcomes.stream().filter(Outcome::succeeded).findFirst().orElseThrow();
        assertThat(outcomes.stream().filter(o -> !o.succeeded())).hasSize(1).allSatisfy(o -> assertThat(
                ((LedgerException) o.failure()).code()).isEqualTo(ConflictCode.VERSION_CONFLICT));
        assertThat(s.before(winner.value().historyId(), Snapshot.IssueRow.class).version()).isEqualTo(1);
        assertThat(s.history(HistoryEntity.ISSUE, i.id()).stream().filter(h -> h.op() == HistoryOp.EDIT)).hasSize(1);
        assertThat(s.issue(i.id()).version()).isEqualTo(2);
    }

    @Test
    @DisplayName("SVC-BEF-10: a replay of an edit writes no second history row")
    void bef10_replay(Scenario s) {
        SaveResult i = s.newIssue("i");
        SaveCommand edit = new SaveCommand(Scenario.uuid(), "issue", i.id(), 1L, Fields.of("title", "x"),
                Scenario.REASON);
        s.bean(SaveService.class).save(Scenario.AUTHOR, edit);
        long rows = s.history(HistoryEntity.ISSUE, i.id()).size();
        s.bean(SaveService.class).save(Scenario.AUTHOR, edit);
        assertThat(s.history(HistoryEntity.ISSUE, i.id())).hasSize((int) rows);
    }

    @Test
    @DisplayName("SVC-BEF-11: 4000 Korean characters are stored in before_data exactly")
    void bef11_koreanText(Scenario s) {
        String symptom = "가나다라".repeat(1000);
        var fields = s.issueFields("i");
        fields.put("symptom", symptom);
        SaveResult i = s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("issue", null, fields));
        SaveResult edited = s.bean(SaveService.class).save(Scenario.AUTHOR, s.edit("issue", i.id(), 1,
                Map.of("title", "new")));
        assertThat(s.before(edited.historyId(), Snapshot.IssueRow.class).symptom()).isEqualTo(symptom);
    }
}
