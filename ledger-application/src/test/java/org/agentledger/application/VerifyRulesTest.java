// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.agentledger.application.support.Expect;
import org.agentledger.application.support.Fields;
import org.agentledger.application.support.LedgerServiceTest;
import org.agentledger.application.support.MergeFixture;
import org.agentledger.application.support.Parallel;
import org.agentledger.application.support.Scenario;
import org.agentledger.application.write.BatchOpInput;
import org.agentledger.application.write.BatchService;
import org.agentledger.application.write.RecordCaseCommand;
import org.agentledger.application.write.RecordCaseService;
import org.agentledger.application.write.RecordDeliverableCommand;
import org.agentledger.application.write.RecordDeliverableService;
import org.agentledger.application.write.ReviewService;
import org.agentledger.application.write.Rows;
import org.agentledger.application.write.SaveService;
import org.agentledger.application.write.TransitionService;
import org.agentledger.application.write.WriteResult.RecordDeliverableResult;
import org.agentledger.application.write.WriteResult.SaveResult;
import org.agentledger.application.write.WriteResult.TransitionResult;
import org.agentledger.domain.context.ActorScope;
import org.agentledger.domain.error.ConflictCode;
import org.agentledger.domain.error.LedgerException;
import org.agentledger.domain.error.TransitionCode;
import org.agentledger.domain.error.TrustCode;
import org.agentledger.domain.error.ValidationCode;
import org.agentledger.domain.rule.CriterionState;
import org.agentledger.domain.rule.IssueStatus;
import org.agentledger.domain.rule.TaskState;
import org.agentledger.domain.value.AuthorId;
import org.agentledger.domain.value.CriterionId;

/**
 * VRF-002 .. VRF-050, F6, D-0023, D-0029: verified items (issue, spec, task, criterion) refuse edits, deliverables,
 * cases, and merges until reopened by a reviewer, and independent verification blocks every contributor/amender/owner.
 */
@LedgerServiceTest
class VerifyRulesTest {

    private record State(long version, String status, String owner, long deliverables, long history,
            long historyCounter) {
    }

    private static State state(Scenario s, String issueId) {
        var issue = s.issue(issueId);
        return new State(issue.version(), issue.status().name(),
                issue.ownerActorId() == null ? null : issue.ownerActorId().value(),
                s.count("SELECT COUNT(*) FROM deliverable WHERE target_entity = 'ISSUE' AND target_id = ?", issueId),
                s.count("SELECT COUNT(*) FROM history WHERE entity_id LIKE ?", issueId + "%"),
                s.count("SELECT issued_no FROM id_counter WHERE name = 'HISTORY'"));
    }

    private static RecordDeliverableCommand deliverable(String targetEntity, String targetId, String ref,
            String checkRef, String checkResult, Boolean mark) {
        return new RecordDeliverableCommand(Scenario.uuid(), targetEntity, targetId, "code_change", ref,
                Scenario.REASON, "repo/e2e", "summary", checkRef, checkResult, null, mark);
    }

    private static RecordDeliverableCommand deliverable(String issue, String ref, String checkRef, String checkResult,
            Boolean mark) {
        return deliverable("issue", issue, ref, checkRef, checkResult, mark);
    }

    private static TransitionResult review(Scenario s, String actor, String entity, String id, long version, String to) {
        return s.bean(ReviewService.class).review(actor, s.move(entity, id, version, to, Scenario.EVIDENCE));
    }

    @Test
    @DisplayName("VRF-002: record_deliverable of a VERIFIED issue (a new deliverable, check update, no mark) is VERIFIED_ENTITY and writes nothing")
    void vrf002_recordDeliverableIsRefused(Scenario s) {
        SaveResult i = s.verifiedIssue("i");
        State before = state(s, i.id());
        assertThat(before.status()).isEqualTo("VERIFIED");
        String recorder = s.actorWith("deliverable");
        List<RecordDeliverableCommand> refused = List.of(
                deliverable(i.id(), Scenario.ref('b'), null, null, null),
                deliverable(i.id(), Scenario.ref('a'), "ci-1", "passed", null),
                deliverable(i.id(), Scenario.ref('c'), null, null, false));
        for (RecordDeliverableCommand command : refused) {
            LedgerException e = Expect.failure(() -> s.bean(RecordDeliverableService.class)
                    .recordDeliverable(recorder, command), TransitionCode.VERIFIED_ENTITY);
            assertThat(e.args()).containsExactly(i.id());
            assertThat(state(s, i.id())).isEqualTo(before);
        }
    }

    @Test
    @DisplayName("VRF-003: save of a VERIFIED issue (title, owner, unchanged values) is VERIFIED_ENTITY before NOTHING_CHANGES, a stale version is VERSION_CONFLICT first")
    void vrf003_saveIsRefused(Scenario s) {
        SaveResult i = s.verifiedIssue("i");
        State before = state(s, i.id());
        for (Map<String, String> fields : List.of(Map.of("title", "changed"),
                Map.of("owner_actor_id", "codex:someone"), Map.of("title", "i"))) {
            LedgerException e = Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR,
                    s.edit("issue", i.id(), before.version(), fields)), TransitionCode.VERIFIED_ENTITY);
            assertThat(e.args()).containsExactly(i.id());
            assertThat(state(s, i.id())).isEqualTo(before);
        }
        Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR,
                s.edit("issue", i.id(), before.version() + 40, Map.of("title", "changed"))),
                ConflictCode.VERSION_CONFLICT);
        assertThat(state(s, i.id())).isEqualTo(before);
    }

    @Test
    @DisplayName("F6: save and record_deliverable of a VERIFIED spec or task, and save of a MET/NOT_MET criterion, are refused with VERIFIED_ENTITY until reopened")
    void f6_verifiedSpecTaskAndCriterionRefuseEditsUntilReopened(Scenario s) {
        SaveResult sp = s.newSpec("sp");
        s.bean(TransitionService.class).transition(Scenario.AUTHOR, s.move("spec", sp.id(), 1, "COMPLETED",
                Scenario.EVIDENCE));
        review(s, Scenario.REVIEWER, "spec", sp.id(), 2, "VERIFIED");

        SaveResult t = s.newTask("t");
        s.bean(TransitionService.class).transition(Scenario.AUTHOR, s.move("task", t.id(), 1, "COMPLETED",
                Scenario.EVIDENCE));
        review(s, Scenario.REVIEWER, "task", t.id(), 2, "VERIFIED");

        SaveResult cMet = s.newCriterion("1", "m1", "c1");
        review(s, Scenario.REVIEWER, "criterion", cMet.id(), 1, "MET");
        SaveResult cNotMet = s.newCriterion("2", "m1", "c2");
        review(s, Scenario.REVIEWER, "criterion", cNotMet.id(), 1, "NOT_MET");

        Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR,
                s.edit("spec", sp.id(), 3, Map.of("title", "edited"))), TransitionCode.VERIFIED_ENTITY);
        Expect.failure(() -> s.bean(RecordDeliverableService.class).recordDeliverable(Scenario.AUTHOR,
                deliverable("spec", sp.id(), Scenario.ref('s'), null, null, null)), TransitionCode.VERIFIED_ENTITY);

        Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR,
                s.edit("task", t.id(), 3, Map.of("title", "edited"))), TransitionCode.VERIFIED_ENTITY);
        Expect.failure(() -> s.bean(RecordDeliverableService.class).recordDeliverable(Scenario.AUTHOR,
                deliverable("task", t.id(), Scenario.ref('t'), null, null, null)), TransitionCode.VERIFIED_ENTITY);

        Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR,
                s.edit("criterion", cMet.id(), 2, Map.of("criterion_text", "edited"))), TransitionCode.VERIFIED_ENTITY);
        Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR,
                s.edit("criterion", cNotMet.id(), 2, Map.of("criterion_text", "edited"))),
                TransitionCode.VERIFIED_ENTITY);

        review(s, Scenario.REVIEWER, "spec", sp.id(), 3, "ACTIVE");
        review(s, Scenario.REVIEWER, "task", t.id(), 3, "ACTIVE");
        review(s, Scenario.REVIEWER, "criterion", cMet.id(), 2, "UNKNOWN");

        assertThat(s.bean(SaveService.class).save(Scenario.AUTHOR,
                s.edit("spec", sp.id(), 4, Map.of("title", "reopened spec"))).version()).isEqualTo(5);
        assertThat(s.bean(SaveService.class).save(Scenario.AUTHOR,
                s.edit("task", t.id(), 4, Map.of("title", "reopened task"))).version()).isEqualTo(5);
        assertThat(s.bean(SaveService.class).save(Scenario.AUTHOR,
                s.edit("criterion", cMet.id(), 3, Map.of("criterion_text", "reopened criterion"))).version())
                .isEqualTo(4);
    }

    @Test
    @DisplayName("VRF-004: a VERIFIED issue that a reviewer reopened takes record_deliverable and save again")
    void vrf004_reopenedIssueIsChangeableAgain(Scenario s) {
        SaveResult i = s.verifiedIssue("i");
        assertThat(review(s, Scenario.REVIEWER, "issue", i.id(), 3, "OPEN").to()).isEqualTo("OPEN");
        RecordDeliverableResult delivered = s.bean(RecordDeliverableService.class).recordDeliverable(Scenario.AUTHOR,
                deliverable(i.id(), Scenario.ref('b'), null, null, null));
        assertThat(delivered.targetState()).isEqualTo("RESOLVED");
        SaveResult edited = s.bean(SaveService.class).save(Scenario.AUTHOR,
                s.edit("issue", i.id(), delivered.version(), Map.of("title", "after the reopening")));
        assertThat(edited.version()).isEqualTo(delivered.version() + 1);
        assertThat(s.issue(i.id()).title()).isEqualTo("after the reopening");
    }

    @Test
    @DisplayName("VRF-005: every status but VERIFIED takes record_deliverable and save as before, MERGED included")
    void vrf005_otherStatusesAreUnchanged(Scenario s) {
        SaveResult open = s.newIssue("open");
        SaveResult resolved = s.resolvedIssue("resolved");
        SaveResult notAnIssue = s.newIssue("not an issue");
        s.bean(TransitionService.class).transition(Scenario.AUTHOR,
                s.move("issue", notAnIssue.id(), 1, "NOT_AN_ISSUE", Scenario.EVIDENCE));
        SaveResult disputed = s.newIssue("disputed");
        s.bean(TransitionService.class).transition(Scenario.AUTHOR,
                s.move("issue", disputed.id(), 1, "DISPUTED", Scenario.EVIDENCE));
        SaveResult merged = s.newIssue("merged");
        MergeFixture.merge(s, merged.id(), 1, open.id());
        Map<SaveResult, IssueStatus> expected = Map.of(open, IssueStatus.RESOLVED,
                resolved, IssueStatus.RESOLVED, notAnIssue, IssueStatus.NOT_AN_ISSUE,
                disputed, IssueStatus.DISPUTED, merged, IssueStatus.MERGED);
        for (Map.Entry<SaveResult, IssueStatus> entry : expected.entrySet()) {
            String id = entry.getKey().id();
            s.bean(RecordDeliverableService.class).recordDeliverable(Scenario.AUTHOR,
                    deliverable(id, Scenario.ref('b'), null, null, null));
            s.bean(SaveService.class).save(Scenario.AUTHOR,
                    s.edit("issue", id, s.issue(id).version(), Map.of("title", "edited " + id)));
            assertThat(s.issue(id).title()).isEqualTo("edited " + id);
            assertThat(s.issue(id).status()).as(id).isEqualTo(entry.getValue());
        }
    }

    @Test
    @DisplayName("VRF-006: a batch with a record_deliverable of a VERIFIED issue fails as a whole and names the operation")
    void vrf006_batch(Scenario s) {
        SaveResult i = s.verifiedIssue("i");
        State before = state(s, i.id());
        long issues = s.count("SELECT COUNT(*) FROM issue WHERE project_id = ?", s.projectId());
        LedgerException e = Expect.failure(() -> s.bean(BatchService.class).batch(Scenario.AUTHOR, Scenario.uuid(),
                List.of(new BatchOpInput.Save("issue", Fields.of(s.issueFields("new")), Scenario.REASON, null, null),
                        new BatchOpInput.RecordDeliverable("issue", i.id(), "code_change", Scenario.ref('b'),
                                Scenario.REASON, "repo/e2e", "Deliverable summary", null, null, null, null, null))),
                TransitionCode.VERIFIED_ENTITY);
        assertThat(e.details()).containsEntry("op_index", List.of("2"))
                .containsEntry("op", List.of("record_deliverable"));
        assertThat(s.count("SELECT COUNT(*) FROM issue WHERE project_id = ?", s.projectId())).isEqualTo(issues);
        assertThat(state(s, i.id())).isEqualTo(before);
    }

    @Test
    @DisplayName("VRF-009: the replay of a deliverable that was recorded before the issue was verified returns the stored answer")
    void vrf009_replayComesBeforeTheStateCheck(Scenario s) {
        SaveResult i = s.newIssue("i");
        RecordDeliverableCommand command = deliverable(i.id(), Scenario.ref('a'), null, null, null);
        RecordDeliverableResult first = s.bean(RecordDeliverableService.class).recordDeliverable(Scenario.AUTHOR,
                command);
        review(s, Scenario.REVIEWER, "issue", i.id(), 2, "VERIFIED");
        State before = state(s, i.id());
        assertThat(s.bean(RecordDeliverableService.class).recordDeliverable(Scenario.AUTHOR, command)).isEqualTo(first);
        assertThat(state(s, i.id())).isEqualTo(before);
    }

    @Test
    @DisplayName("VRF-012 / D-0029: the verifier is none of the registrant, owner, case observer, deliverable recorder or deliverable check amender")
    void vrf012_everyParticipantAndAmenderIsRefused(Scenario s) {
        String owner = s.actorWith("issue", "verify");
        String recorder = s.actorWith("deliverable", "verify");
        String amender = s.actorWith("deliverable", "verify");
        String observer = s.actorWith("issue", "verify");
        String independent = s.actorWith("verify");

        var issueFields = s.issueFields("i");
        issueFields.put("owner_actor_id", owner);
        SaveResult i = s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("issue", null, issueFields));

        s.deliver(recorder, i.id(), Scenario.ref('a'));
        s.bean(RecordDeliverableService.class).recordDeliverable(amender,
                deliverable(i.id(), Scenario.ref('a'), "check-1", "passed", null));
        s.bean(RecordCaseService.class).recordCase(observer,
                new RecordCaseCommand(Scenario.uuid(), i.id(), "seen", Scenario.REASON, null, null, null));

        long version = s.issue(i.id()).version();
        for (String participant : List.of(Scenario.AUTHOR, owner, recorder, amender, observer)) {
            Expect.failure(() -> review(s, participant, "issue", i.id(), version, "VERIFIED"),
                    TransitionCode.SAME_SESSION);
        }
        assertThat(s.issue(i.id()).status()).isEqualTo(IssueStatus.RESOLVED);
        assertThat(review(s, independent, "issue", i.id(), version, "VERIFIED").to()).isEqualTo("VERIFIED");
        assertThat(s.issue(i.id()).status()).isEqualTo(IssueStatus.VERIFIED);
    }

    @Test
    @DisplayName("D-0029: spec and task deliverable amenders and owners cannot self-verify")
    void d0029_specAndTaskDeliverableAmendersCannotVerify(Scenario s) {
        String owner = s.actorWith("spec", "task", "verify");
        String recorder = s.actorWith("deliverable", "verify");
        String amender = s.actorWith("deliverable", "verify");
        String independent = s.actorWith("verify");

        var specFields = s.specFields("sp");
        specFields.put("owner_actor_id", owner);
        SaveResult sp = s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("spec", null, specFields));
        s.bean(RecordDeliverableService.class).recordDeliverable(recorder,
                deliverable("spec", sp.id(), Scenario.ref('s'), null, null, null));
        s.bean(RecordDeliverableService.class).recordDeliverable(amender,
                deliverable("spec", sp.id(), Scenario.ref('s'), "chk-1", "passed", null));
        long specVer = s.spec(sp.id()).version();
        for (String participant : List.of(Scenario.AUTHOR, owner, recorder, amender)) {
            Expect.failure(() -> review(s, participant, "spec", sp.id(), specVer, "VERIFIED"),
                    TransitionCode.SAME_SESSION);
        }
        assertThat(review(s, independent, "spec", sp.id(), specVer, "VERIFIED").to()).isEqualTo("VERIFIED");

        SaveResult t = s.newTask("t");
        s.bean(RecordDeliverableService.class).recordDeliverable(recorder,
                deliverable("task", t.id(), Scenario.ref('t'), null, null, null));
        s.bean(RecordDeliverableService.class).recordDeliverable(amender,
                deliverable("task", t.id(), Scenario.ref('t'), "chk-2", "passed", null));
        long taskVer = s.task(t.id()).version();
        for (String participant : List.of(Scenario.AUTHOR, recorder, amender)) {
            Expect.failure(() -> review(s, participant, "task", t.id(), taskVer, "VERIFIED"),
                    TransitionCode.SAME_SESSION);
        }
        assertThat(review(s, independent, "task", t.id(), taskVer, "VERIFIED").to()).isEqualTo("VERIFIED");
    }

    @Test
    @DisplayName("VRF-015: a task is verified by none of the sessions that registered it, own it or completed it at any time")
    void vrf015_taskParticipantsAreRefused(Scenario s) {
        String owner = s.actorWith("task", "verify");
        String first = s.actorWith("task", "verify");
        String second = s.actorWith("task", "verify");
        String independent = s.actorWith("verify");
        var fields = s.taskFields("t");
        fields.put("owner_actor_id", owner);
        SaveResult t = s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("task", null, fields));
        s.bean(TransitionService.class).transition(first, s.move("task", t.id(), 1, "COMPLETED", Scenario.EVIDENCE));
        s.bean(TransitionService.class).transition(Scenario.AUTHOR, s.move("task", t.id(), 2, "ACTIVE", null));
        s.bean(TransitionService.class).transition(second, s.move("task", t.id(), 3, "COMPLETED", Scenario.EVIDENCE));
        assertThat(s.task(t.id()).version()).isEqualTo(4);
        for (String participant : List.of(Scenario.AUTHOR, owner, first, second)) {
            Expect.failure(() -> review(s, participant, "task", t.id(), 4, "VERIFIED"), TransitionCode.SAME_SESSION);
        }
        assertThat(s.task(t.id()).state()).isEqualTo(TaskState.COMPLETED);
        assertThat(review(s, independent, "task", t.id(), 4, "VERIFIED").to()).isEqualTo("VERIFIED");
    }

    @Test
    @DisplayName("VRF-016: a milestone criterion is judged (MET, NOT_MET) by none of the registrant or completion reporter; leaving MET needs no such check")
    void vrf016_criteriaFollowTheSamePrinciple(Scenario s) {
        String completer = s.actorWith("task", "verify");
        SaveResult c = s.newCriterion("1", "r1", "c1");
        s.bean(TransitionService.class).transition(completer, s.move("criterion", c.id(), 1, "COMPLETED",
                Scenario.EVIDENCE));
        for (String to : List.of("MET", "NOT_MET")) {
            Expect.failure(() -> review(s, Scenario.AUTHOR, "criterion", c.id(), 2, to), TransitionCode.SAME_SESSION);
            Expect.failure(() -> review(s, completer, "criterion", c.id(), 2, to), TransitionCode.SAME_SESSION);
        }
        assertThat(s.bean(Rows.class).criterion(CriterionId.of(c.id())).state()).isEqualTo(CriterionState.COMPLETED);
        String independent = s.actorWith("verify");
        assertThat(review(s, independent, "criterion", c.id(), 2, "MET").to()).isEqualTo("MET");
        Expect.failure(() -> s.bean(ReviewService.class).review(Scenario.AUTHOR,
                s.move("criterion", c.id(), 3, "UNKNOWN", null)), TransitionCode.EVIDENCE_REQUIRED);
        assertThat(s.bean(ReviewService.class).review(Scenario.AUTHOR,
                s.move("criterion", c.id(), 3, "UNKNOWN", Scenario.EVIDENCE)).to()).isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("VRF-018: the registrant and the case sessions of an issue merged into this one count as reporters of it, the deliverables of the merged issue do not")
    void vrf018_mergedIssuesAreReported(Scenario s) {
        String registrarOfB = s.actorWith("issue", "verify");
        String observerOfB = s.actorWith("issue", "verify");
        String recorderOfA = s.actorWith("deliverable", "verify");
        String recorderOfB = s.actorWith("deliverable", "verify");
        SaveResult a = s.newIssue("a");
        var fieldsOfB = s.issueFields("b");
        SaveResult b = s.bean(SaveService.class).save(registrarOfB, s.create("issue", null, fieldsOfB));
        s.bean(RecordCaseService.class).recordCase(observerOfB,
                new RecordCaseCommand(Scenario.uuid(), b.id(), "seen", Scenario.REASON, null, null, null));
        s.deliver(recorderOfA, a.id(), Scenario.ref('a'));
        s.deliver(recorderOfB, b.id(), Scenario.ref('b'));
        MergeFixture.merge(s, b.id(), s.issue(b.id()).version(), a.id());
        long version = s.issue(a.id()).version();
        for (String refused : List.of(Scenario.AUTHOR, registrarOfB, observerOfB, recorderOfA)) {
            Expect.failure(() -> review(s, refused, "issue", a.id(), version, "VERIFIED"),
                    TransitionCode.SAME_SESSION);
        }
        assertThat(review(s, recorderOfB, "issue", a.id(), version, "VERIFIED").to()).isEqualTo("VERIFIED");
    }

    @Test
    @DisplayName("VRF-019: every record_deliverable saves the target row with its version")
    void vrf019_recordDeliverableAlwaysWritesTheIssue(Scenario s) {
        SaveResult i = s.resolvedIssue("i");
        var staleRead = s.issue(i.id());
        assertThat(staleRead.version()).isEqualTo(2);
        RecordDeliverableResult sameOwner = s.deliver(Scenario.AUTHOR, i.id(), Scenario.ref('b'));
        assertThat(sameOwner.version()).isEqualTo(3);
        assertThat(sameOwner.transitionHistoryId()).isNull();
        RecordDeliverableResult checkUpdate = s.bean(RecordDeliverableService.class).recordDeliverable(Scenario.AUTHOR,
                deliverable(i.id(), Scenario.ref('a'), "ci-1", "passed", null));
        assertThat(checkUpdate.version()).isEqualTo(4);
        assertThat(checkUpdate.transitionHistoryId()).isNull();
        assertThat(s.issue(i.id()).version()).isEqualTo(4);
        var stale = staleRead.transitioned(IssueStatus.VERIFIED, null, java.time.Instant.parse("2026-11-16T00:00:00Z"));
        Expect.failure(() -> s.ctx().tx(() -> ScopedValue.where(ActorScope.CURRENT, AuthorId.OWNER)
                .call(() -> s.bean(Rows.class).saveIssue(stale))), ConflictCode.VERSION_CONFLICT);
        assertThat(s.issue(i.id()).status()).isEqualTo(IssueStatus.RESOLVED);
    }

    @Test
    @DisplayName("VRF-049: record_deliverable runs no severity code check: an issue with retired severity UNRATED takes deliverables")
    void vrf049_unratedIssueTakesDeliverables(Scenario s) {
        SaveResult open = s.newIssue("open");
        SaveResult resolved = s.resolvedIssue("resolved");
        for (SaveResult i : List.of(open, resolved)) {
            s.ctx().jdbc().sql("UPDATE issue SET severity = 'UNRATED' WHERE id = ?").param(i.id()).update();
        }
        assertThat(s.deliver(Scenario.AUTHOR, open.id(), Scenario.ref('a')).targetState()).isEqualTo("RESOLVED");
        String other = s.actorWith("deliverable");
        assertThat(s.deliver(other, resolved.id(), Scenario.ref('b')).ownerActorId()).isEqualTo(other);
        assertThat(s.deliver(other, resolved.id(), Scenario.ref('c')).transitionHistoryId()).isNull();
        s.bean(RecordDeliverableService.class).recordDeliverable(other,
                deliverable(resolved.id(), Scenario.ref('c'), "ci-1", "passed", null));
        for (SaveResult i : List.of(open, resolved)) {
            assertThat(s.issue(i.id()).severity().value()).isEqualTo("UNRATED");
        }
        Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR,
                s.edit("issue", open.id(), s.issue(open.id()).version(), Map.of("title", "changed"))),
                ValidationCode.CODE_INVALID);
    }

    @Test
    @DisplayName("VRF-050: a state change runs no severity code check: an issue with retired severity UNRATED is moved, verified and merged")
    void vrf050_unratedIssueChangesState(Scenario s) {
        SaveResult toResolved = s.newIssue("to resolved");
        SaveResult toVerified = s.newIssue("to verified");
        SaveResult notAnIssue = s.newIssue("not an issue");
        SaveResult disputed = s.newIssue("disputed");
        SaveResult merged = s.newIssue("merged");
        SaveResult target = s.newIssue("target");
        for (SaveResult i : List.of(toResolved, toVerified, notAnIssue, disputed, merged)) {
            s.ctx().jdbc().sql("UPDATE issue SET severity = 'UNRATED' WHERE id = ?").param(i.id()).update();
        }
        TransitionService transitions = s.bean(TransitionService.class);
        s.bean(RecordDeliverableService.class).recordDeliverable(Scenario.AUTHOR,
                deliverable(toResolved.id(), Scenario.ref('a'), null, null, false));
        assertThat(transitions.transition(Scenario.AUTHOR,
                s.move("issue", toResolved.id(), s.issue(toResolved.id()).version(), "RESOLVED", null)).to())
                .isEqualTo("RESOLVED");
        assertThat(transitions.transition(Scenario.AUTHOR,
                s.move("issue", notAnIssue.id(), 1, "NOT_AN_ISSUE", Scenario.EVIDENCE)).to())
                .isEqualTo("NOT_AN_ISSUE");
        assertThat(transitions.transition(Scenario.AUTHOR,
                s.move("issue", disputed.id(), 1, "DISPUTED", Scenario.EVIDENCE)).to()).isEqualTo("DISPUTED");
        MergeFixture.merge(s, merged.id(), 1, target.id());
        s.deliver(Scenario.AUTHOR, toVerified.id(), Scenario.ref('b'));
        String independent = s.actorWith("verify");
        assertThat(review(s, independent, "issue", toVerified.id(), s.issue(toVerified.id()).version(), "VERIFIED").to())
                .isEqualTo("VERIFIED");
        for (SaveResult i : List.of(toResolved, toVerified, notAnIssue, disputed, merged)) {
            assertThat(s.issue(i.id()).severity().value()).as(i.id()).isEqualTo("UNRATED");
        }
        assertThat(s.issue(merged.id()).status()).isEqualTo(IssueStatus.MERGED);
        Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR,
                s.edit("issue", notAnIssue.id(), s.issue(notAnIssue.id()).version(), Map.of("title", "changed"))),
                ValidationCode.CODE_INVALID);
    }

    @RepeatedTest(20)
    @DisplayName("VRF-028: a record_deliverable of the owner and a verification of the same issue at the same time never both succeed")
    void vrf028_deliverableAgainstVerification(Scenario s) {
        SaveResult i = s.resolvedIssue("i");
        String independent = s.actorWith("verify");
        List<java.util.concurrent.Callable<Object>> calls = List.of(
                () -> s.deliver(Scenario.AUTHOR, i.id(), Scenario.ref('b')),
                () -> review(s, independent, "issue", i.id(), 2, "VERIFIED"));
        var outcomes = Parallel.all(calls);
        assertThat(outcomes.stream().filter(Parallel.Outcome::succeeded)).hasSizeLessThan(2);
        for (var outcome : outcomes) {
            if (!outcome.succeeded()) {
                assertThat(outcome.failure()).isInstanceOf(LedgerException.class);
                assertThat(((LedgerException) outcome.failure()).code()).isIn(ConflictCode.VERSION_CONFLICT,
                        TransitionCode.TRANSITION_INVALID, TransitionCode.VERIFIED_ENTITY);
            }
        }
        if (outcomes.get(1).succeeded()) {
            assertThat(s.count("SELECT COUNT(*) FROM deliverable WHERE target_entity = 'ISSUE' AND target_id = ?",
                    i.id())).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("REM-004 (D-0023 / BLOCKING-L1): a VERIFIED issue refuses incoming MERGED targets and record_case until a reviewer reopens it")
    void rem004_verifiedIssueRefusesMergeTargetAndRecordCase(Scenario s) {
        SaveResult target = s.resolvedIssue("verified-target");
        String reviewer = s.actorWith("verify");
        assertThat(review(s, reviewer, "issue", target.id(), 2, "VERIFIED").to()).isEqualTo("VERIFIED");

        SaveResult source = s.newIssue("source");
        Expect.failure(() -> MergeFixture.merge(s, source.id(), 1, target.id()), TransitionCode.MERGE_TARGET_INVALID);
        assertThat(s.issue(source.id()).status()).isEqualTo(IssueStatus.OPEN);

        Expect.failure(() -> s.bean(RecordCaseService.class).recordCase(Scenario.AUTHOR,
                new RecordCaseCommand(Scenario.uuid(), target.id(), "reopened case after verify",
                        Scenario.REASON, null, null, null)),
                TransitionCode.VERIFIED_ENTITY);
        assertThat(s.count("SELECT COUNT(*) FROM issue_case WHERE issue_id = ?", target.id())).isZero();

        assertThat(review(s, reviewer, "issue", target.id(), 3, "OPEN").to()).isEqualTo("OPEN");
        assertThat(MergeFixture.merge(s, source.id(), 1, target.id()).to()).isEqualTo("MERGED");
        assertThat(s.bean(RecordCaseService.class).recordCase(Scenario.AUTHOR,
                new RecordCaseCommand(Scenario.uuid(), target.id(), "case after reopen",
                        Scenario.REASON, null, null, null)).caseId())
                .isPositive();
    }

    @Test
    @DisplayName("AUDIT-F1: literal \"owner_actor_id\" in title/summary/purpose does not bypass prior-owner SAME_SESSION blocking")
    void auditF1_literalOwnerActorIdInTextDoesNotBypassPriorOwnerBlocking(Scenario s) {
        String priorIssueOwner = s.actorWith("issue", "verify");
        String priorSpecOwner = s.actorWith("spec", "verify");
        String priorTaskOwner = s.actorWith("task", "verify");
        String recorder = s.actorWith("deliverable", "issue", "spec", "task");
        String independent = s.actorWith("verify");

        var issueFields = s.issueFields("literal \"owner_actor_id\": \"fake\" in title");
        issueFields.put("owner_actor_id", priorIssueOwner);
        SaveResult issue = s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("issue", null, issueFields));
        s.deliver(recorder, issue.id(), Scenario.ref('a'));

        long issueVersion = s.issue(issue.id()).version();
        Expect.failure(() -> review(s, priorIssueOwner, "issue", issue.id(), issueVersion, "VERIFIED"),
                TransitionCode.SAME_SESSION);
        assertThat(review(s, independent, "issue", issue.id(), issueVersion, "VERIFIED").to()).isEqualTo("VERIFIED");

        SaveResult spec = s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("spec", null,
                Map.of("project_id", s.projectId(), "title", "spec title",
                        "summary", "text with \"owner_actor_id\": \"fake\"",
                        "owner_actor_id", priorSpecOwner)));
        s.bean(RecordDeliverableService.class).recordDeliverable(recorder,
                deliverable("spec", spec.id(), Scenario.ref('b'), null, null, true));
        long specVersion = s.spec(spec.id()).version();
        Expect.failure(() -> review(s, priorSpecOwner, "spec", spec.id(), specVersion, "VERIFIED"),
                TransitionCode.SAME_SESSION);
        assertThat(review(s, independent, "spec", spec.id(), specVersion, "VERIFIED").to()).isEqualTo("VERIFIED");

        SaveResult task = s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("task", null,
                Map.of("project_id", s.projectId(), "title", "task title",
                        "purpose", "text with \"owner_actor_id\": \"fake\"",
                        "success_condition", "done",
                        "owner_actor_id", priorTaskOwner)));
        s.bean(RecordDeliverableService.class).recordDeliverable(recorder,
                deliverable("task", task.id(), Scenario.ref('c'), null, null, false));
        s.bean(TransitionService.class).transition(recorder,
                s.move("task", task.id(), s.task(task.id()).version(), "COMPLETED", Scenario.EVIDENCE));
        long taskVersion = s.task(task.id()).version();
        Expect.failure(() -> review(s, priorTaskOwner, "task", task.id(), taskVersion, "VERIFIED"),
                TransitionCode.SAME_SESSION);
        assertThat(review(s, independent, "task", task.id(), taskVersion, "VERIFIED").to()).isEqualTo("VERIFIED");
    }

    @Test
    @DisplayName("AUDIT-F2: a session that edited a milestone criterion (HistoryOp.EDIT) cannot verify it to MET or NOT_MET")
    void auditF2_criterionEditAuthorCannotSelfVerify(Scenario s) {
        String editor = s.actorWith("task", "verify");
        String independent = s.actorWith("verify");

        SaveResult criterion = s.newCriterion("f2-c1", "m1", "initial criterion text");
        SaveResult edited = s.bean(SaveService.class).save(editor,
                s.edit("criterion", criterion.id(), 1, Map.of("criterion_text", "edited criterion text")));
        assertThat(edited.version()).isEqualTo(2);

        Expect.failure(() -> review(s, editor, "criterion", criterion.id(), 2, "MET"),
                TransitionCode.SAME_SESSION);
        Expect.failure(() -> review(s, editor, "criterion", criterion.id(), 2, "NOT_MET"),
                TransitionCode.SAME_SESSION);
        assertThat(s.criterion(criterion.id()).state()).isEqualTo(CriterionState.UNKNOWN);

        assertThat(review(s, independent, "criterion", criterion.id(), 2, "MET").to()).isEqualTo("MET");
        assertThat(s.criterion(criterion.id()).state()).isEqualTo(CriterionState.MET);
    }

    @Test
    @DisplayName("R2-BLOCKING-1: HistoryOp.EDIT authors on issue/spec/task and OPEN->RESOLVED transition authors on issue cannot self-verify")
    void r2Blocking1_editAuthorsAndIssueResolveReporterCannotSelfVerify(Scenario s) {
        String issueEditor = s.actorWith("issue", "verify");
        String issueResolver = s.actorWith("issue", "verify");
        String specEditor = s.actorWith("spec", "verify");
        String taskEditor = s.actorWith("task", "verify");
        String recorder = s.actorWith("deliverable", "issue", "spec", "task");
        String independent = s.actorWith("verify");

        SaveResult issue = s.newIssue("r2-b1-issue");
        s.bean(SaveService.class).save(issueEditor, s.edit("issue", issue.id(), 1, Map.of("title", "edited issue")));
        s.bean(RecordDeliverableService.class).recordDeliverable(recorder,
                deliverable("issue", issue.id(), Scenario.ref('a'), null, null, false));
        long openVersion = s.issue(issue.id()).version();
        s.bean(TransitionService.class).transition(issueResolver,
                s.move("issue", issue.id(), openVersion, "RESOLVED", null));
        long resolvedVersion = s.issue(issue.id()).version();
        Expect.failure(() -> review(s, issueEditor, "issue", issue.id(), resolvedVersion, "VERIFIED"),
                TransitionCode.SAME_SESSION);
        Expect.failure(() -> review(s, issueResolver, "issue", issue.id(), resolvedVersion, "VERIFIED"),
                TransitionCode.SAME_SESSION);
        assertThat(review(s, independent, "issue", issue.id(), resolvedVersion, "VERIFIED").to()).isEqualTo("VERIFIED");

        SaveResult spec = s.newSpec("r2-b1-spec");
        s.bean(SaveService.class).save(specEditor,
                s.edit("spec", spec.id(), 1, Map.of("acceptance_criteria", "tampered criteria")));
        s.bean(TransitionService.class).transition(recorder,
                s.move("spec", spec.id(), 2, "COMPLETED", Scenario.EVIDENCE));
        Expect.failure(() -> review(s, specEditor, "spec", spec.id(), 3, "VERIFIED"), TransitionCode.SAME_SESSION);
        assertThat(review(s, independent, "spec", spec.id(), 3, "VERIFIED").to()).isEqualTo("VERIFIED");

        SaveResult task = s.newTask("r2-b1-task");
        s.bean(SaveService.class).save(taskEditor,
                s.edit("task", task.id(), 1, Map.of("success_condition", "tampered condition")));
        s.bean(TransitionService.class).transition(recorder,
                s.move("task", task.id(), 2, "COMPLETED", Scenario.EVIDENCE));
        Expect.failure(() -> review(s, taskEditor, "task", task.id(), 3, "VERIFIED"), TransitionCode.SAME_SESSION);
        assertThat(review(s, independent, "task", task.id(), 3, "VERIFIED").to()).isEqualTo("VERIFIED");
    }

    @Test
    @DisplayName("R3-F2: merging an issue into a target in another project requires issue grant on the target project")
    void r3F2_mergeAuthorizesTargetProjectUnderLock(Scenario s) {
        String p2 = s.otherProject();
        String p2Author = s.actorWith("issue");
        s.grant(p2Author, p2, "issue");
        String p1Only = s.actorWith("issue");

        SaveResult source = s.newIssue("r3-f2-src");
        var p2Fields = s.issueFields("r3-f2-target");
        p2Fields.put("project_id", p2);
        SaveResult target = s.bean(SaveService.class).save(p2Author, s.create("issue", null, p2Fields));

        Expect.failure(() -> s.bean(TransitionService.class).transition(p1Only,
                new org.agentledger.application.write.TransitionCommand(Scenario.uuid(), "issue", source.id(), 1L,
                        "MERGED", Scenario.REASON, null, null, target.id())),
                TrustCode.SESSION_NOT_GRANTED);
        assertThat(s.issue(source.id()).status()).isEqualTo(IssueStatus.OPEN);

        assertThat(s.bean(TransitionService.class).transition(p2Author,
                new org.agentledger.application.write.TransitionCommand(Scenario.uuid(), "issue", source.id(), 1L,
                        "MERGED", Scenario.REASON, null, null, target.id())).to()).isEqualTo("MERGED");
    }

    @Test
    @DisplayName("R3-F3: spec parent must stay in the same project, be acyclic, and block orphaning project moves")
    void r3F3_specParentSameProjectAcyclicAndProjectMoveGuard(Scenario s) {
        String p2 = s.otherProject();
        String actor = s.actorWith("spec");
        s.grant(actor, p2, "spec");

        SaveResult specA = s.bean(SaveService.class).save(actor, s.create("spec",
                "FREE-A-" + Scenario.uuid().substring(0, 8), Map.of("project_id", s.projectId(), "title", "A")));
        SaveResult specB = s.bean(SaveService.class).save(actor, s.create("spec",
                "FREE-B-" + Scenario.uuid().substring(0, 8),
                Map.of("project_id", s.projectId(), "title", "B", "parent_id", specA.id())));
        SaveResult specC = s.bean(SaveService.class).save(actor, s.create("spec",
                "FREE-C-" + Scenario.uuid().substring(0, 8),
                Map.of("project_id", s.projectId(), "title", "C", "parent_id", specB.id())));
        SaveResult otherProjectSpec = s.bean(SaveService.class).save(actor, s.create("spec",
                "FREE-P2-" + Scenario.uuid().substring(0, 8), Map.of("project_id", p2, "title", "P2")));

        // Cross-project parent refused on create and edit
        Expect.failure(() -> s.bean(SaveService.class).save(actor, s.create("spec", null,
                Map.of("project_id", s.projectId(), "title", "bad", "parent_id", otherProjectSpec.id()))),
                ValidationCode.VALIDATION_FAILED);
        Expect.failure(() -> s.bean(SaveService.class).save(actor, s.edit("spec", specA.id(), 1,
                Map.of("parent_id", otherProjectSpec.id()))), ValidationCode.VALIDATION_FAILED);

        // 2-node and 3-node cycles refused
        Expect.failure(() -> s.bean(SaveService.class).save(actor, s.edit("spec", specA.id(), 1,
                Map.of("parent_id", specB.id()))), ValidationCode.VALIDATION_FAILED);
        Expect.failure(() -> s.bean(SaveService.class).save(actor, s.edit("spec", specA.id(), 1,
                Map.of("parent_id", specC.id()))), ValidationCode.VALIDATION_FAILED);

        // Moving parent spec with children is forbidden; moving child with old parent_id is refused
        Expect.failure(() -> s.bean(SaveService.class).save(actor, s.edit("spec", specA.id(), 1,
                Map.of("project_id", p2))), TransitionCode.PROJECT_MOVE_FORBIDDEN);
        Expect.failure(() -> s.bean(SaveService.class).save(actor, s.edit("spec", specC.id(), 1,
                Map.of("project_id", p2))), ValidationCode.VALIDATION_FAILED);

        // Clearing parent_id while moving succeeds
        SaveResult moved = s.bean(SaveService.class).save(actor, s.edit("spec", specC.id(), 1,
                Map.of("project_id", p2, "parent_id", "")));
        assertThat(moved.version()).isEqualTo(2);
        assertThat(s.spec(specC.id()).projectId().value()).isEqualTo(p2);
        assertThat(s.spec(specC.id()).parentId()).isNull();
    }
}
