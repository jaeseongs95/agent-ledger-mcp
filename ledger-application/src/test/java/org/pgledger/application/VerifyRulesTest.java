package org.pgledger.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.application.support.Expect;
import org.pgledger.application.support.Fields;
import org.pgledger.application.support.LedgerServiceTest;
import org.pgledger.application.support.MergeFixture;
import org.pgledger.application.support.Scenario;
import org.pgledger.application.write.BatchOpInput;
import org.pgledger.application.write.BatchService;
import org.pgledger.application.write.RecordCaseCommand;
import org.pgledger.application.write.RecordCaseService;
import org.pgledger.application.write.RecordFixCommand;
import org.pgledger.application.write.RecordFixService;
import org.pgledger.application.write.ReviewService;
import org.pgledger.application.write.SaveService;
import org.pgledger.application.write.TransitionService;
import org.pgledger.application.write.WriteResult.RecordFixResult;
import org.pgledger.application.write.WriteResult.SaveResult;
import org.pgledger.application.write.WriteResult.TransitionResult;
import org.pgledger.domain.context.ActorScope;
import org.pgledger.domain.error.ConflictCode;
import org.pgledger.domain.error.LedgerException;
import org.pgledger.domain.error.TransitionCode;
import org.pgledger.domain.rule.DefectStatus;
import org.pgledger.domain.rule.TaskState;
import org.pgledger.domain.value.AuthorId;

/**
 * VRF-002 .. VRF-006, VRF-009, VRF-012, VRF-015 and VRF-016: a VERIFIED defect is changed by no record_fix and no save until a
 * reviewer reopens it (D-0023), and the verifier is none of the sessions that took part in the defect or the task (D-0029).
 */
@LedgerServiceTest
class VerifyRulesTest {

    /** What a refused write must leave as it was. */
    private record State(long version, String status, String owner, long fixes, long history, long historyCounter) {
    }

    private static State state(Scenario s, String defectId) {
        var defect = s.defect(defectId);
        return new State(defect.version(), defect.status().name(),
                defect.ownerActorId() == null ? null : defect.ownerActorId().value(),
                s.count("SELECT COUNT(*) FROM defect_fix WHERE defect_id = ?", defectId),
                s.count("SELECT COUNT(*) FROM history WHERE entity_id LIKE ?", defectId + "%"),
                s.count("SELECT issued_no FROM id_counter WHERE name = 'HISTORY'"));
    }

    private static RecordFixCommand fix(String defect, String commit, String ciRef, String conclusion, Boolean mark) {
        return new RecordFixCommand(Scenario.uuid(), defect, "repo/e2e", commit, Scenario.REASON, "main", ciRef,
                conclusion, null, mark);
    }

    private static TransitionResult review(Scenario s, String actor, String entity, String id, long version, String to) {
        return s.bean(ReviewService.class).review(actor, s.move(entity, id, version, to, Scenario.EVIDENCE));
    }

    // ------------------------------------------------------------------------------------------------ D-0023

    @Test
    @DisplayName("VRF-002: record_fix of a VERIFIED defect (a new commit, new CI data of a known one, no mark) is VERIFIED_DEFECT and writes nothing")
    void vrf002_recordFixIsRefused(Scenario s) {
        SaveResult d = s.verifiedDefect("d");
        State before = state(s, d.id());
        assertThat(before.status()).isEqualTo("VERIFIED");
        String fixer = s.actorWith("fix");
        List<RecordFixCommand> refused = List.of(fix(d.id(), Scenario.commit('b'), null, null, null),
                fix(d.id(), Scenario.commit('a'), "ci-1", "success", null),
                fix(d.id(), Scenario.commit('c'), null, null, false));
        for (RecordFixCommand command : refused) {
            LedgerException e = Expect.failure(() -> s.bean(RecordFixService.class).recordFix(fixer, command),
                    TransitionCode.VERIFIED_DEFECT);
            assertThat(e.args()).containsExactly(d.id());
            assertThat(Expect.message(e)).contains(d.id(), "reviewer", "reopen");
            assertThat(state(s, d.id())).isEqualTo(before);
        }
    }

    @Test
    @DisplayName("VRF-003: save of a VERIFIED defect (title, owner, unchanged values) is VERIFIED_DEFECT before NOTHING_CHANGES, a stale version is VERSION_CONFLICT first")
    void vrf003_saveIsRefused(Scenario s) {
        SaveResult d = s.verifiedDefect("d");
        State before = state(s, d.id());
        for (Map<String, String> fields : List.of(Map.of("title", "changed"),
                Map.of("owner_actor_id", "codex:someone"), Map.of("title", "d"))) {
            LedgerException e = Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR,
                    s.edit("defect", d.id(), before.version(), fields)), TransitionCode.VERIFIED_DEFECT);
            assertThat(e.args()).containsExactly(d.id());
            assertThat(state(s, d.id())).isEqualTo(before);
        }
        Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR,
                s.edit("defect", d.id(), before.version() + 40, Map.of("title", "changed"))),
                ConflictCode.VERSION_CONFLICT);
        assertThat(state(s, d.id())).isEqualTo(before);
    }

    @Test
    @DisplayName("VRF-004: a VERIFIED defect that a reviewer reopened takes record_fix and save again")
    void vrf004_reopenedDefectIsChangeableAgain(Scenario s) {
        SaveResult d = s.verifiedDefect("d");
        assertThat(review(s, Scenario.REVIEWER, "defect", d.id(), 3, "OPEN").to()).isEqualTo("OPEN");
        RecordFixResult fixed = s.bean(RecordFixService.class).recordFix(Scenario.AUTHOR,
                fix(d.id(), Scenario.commit('b'), null, null, null));
        assertThat(fixed.defectStatus()).isEqualTo("SOURCE_FIXED");
        SaveResult edited = s.bean(SaveService.class).save(Scenario.AUTHOR,
                s.edit("defect", d.id(), fixed.version(), Map.of("title", "after the reopening")));
        assertThat(edited.version()).isEqualTo(fixed.version() + 1);
        assertThat(s.defect(d.id()).title()).isEqualTo("after the reopening");
    }

    @Test
    @DisplayName("VRF-005: every status but VERIFIED takes record_fix and save as before, MERGED included")
    void vrf005_otherStatusesAreUnchanged(Scenario s) {
        SaveResult open = s.newDefect("open");
        SaveResult sourceFixed = s.sourceFixedDefect("fixed");
        SaveResult notDefect = s.newDefect("not a defect");
        s.bean(TransitionService.class).transition(Scenario.AUTHOR,
                s.move("defect", notDefect.id(), 1, "NOT_DEFECT", Scenario.EVIDENCE));
        SaveResult disputed = s.newDefect("disputed");
        s.bean(TransitionService.class).transition(Scenario.AUTHOR,
                s.move("defect", disputed.id(), 1, "DISPUTED", Scenario.EVIDENCE));
        SaveResult merged = s.newDefect("merged");
        MergeFixture.merge(s, merged.id(), 1, open.id());
        Map<SaveResult, DefectStatus> expected = Map.of(open, DefectStatus.SOURCE_FIXED,
                sourceFixed, DefectStatus.SOURCE_FIXED, notDefect, DefectStatus.NOT_DEFECT,
                disputed, DefectStatus.DISPUTED, merged, DefectStatus.MERGED);
        for (Map.Entry<SaveResult, DefectStatus> entry : expected.entrySet()) {
            String id = entry.getKey().id();
            s.bean(RecordFixService.class).recordFix(Scenario.AUTHOR, fix(id, Scenario.commit('b'), null, null, null));
            s.bean(SaveService.class).save(Scenario.AUTHOR,
                    s.edit("defect", id, s.defect(id).version(), Map.of("title", "edited " + id)));
            assertThat(s.defect(id).title()).isEqualTo("edited " + id);
            assertThat(s.defect(id).status()).as(id).isEqualTo(entry.getValue());
        }
    }

    @Test
    @DisplayName("VRF-006: a batch with a record_fix of a VERIFIED defect fails as a whole and names the operation")
    void vrf006_batch(Scenario s) {
        SaveResult d = s.verifiedDefect("d");
        State before = state(s, d.id());
        long defects = s.count("SELECT COUNT(*) FROM defect WHERE project_id = ?", s.projectId());
        LedgerException e = Expect.failure(() -> s.bean(BatchService.class).batch(Scenario.AUTHOR, Scenario.uuid(),
                List.of(new BatchOpInput.Save("defect", Fields.of(s.defectFields("new")), Scenario.REASON, null, null),
                        new BatchOpInput.RecordFix(d.id(), "repo/e2e", Scenario.commit('b'), Scenario.REASON, null,
                                null, null, null, null, null))),
                TransitionCode.VERIFIED_DEFECT);
        assertThat(e.details()).containsEntry("op_index", List.of("2")).containsEntry("op", List.of("record_fix"));
        assertThat(s.count("SELECT COUNT(*) FROM defect WHERE project_id = ?", s.projectId())).isEqualTo(defects);
        assertThat(state(s, d.id())).isEqualTo(before);
    }

    @Test
    @DisplayName("VRF-009: the replay of a fix that was recorded before the defect was verified returns the stored answer")
    void vrf009_replayComesBeforeTheStateCheck(Scenario s) {
        SaveResult d = s.newDefect("d");
        RecordFixCommand command = fix(d.id(), Scenario.commit('a'), null, null, null);
        RecordFixResult first = s.bean(RecordFixService.class).recordFix(Scenario.AUTHOR, command);
        review(s, Scenario.REVIEWER, "defect", d.id(), 2, "VERIFIED");
        State before = state(s, d.id());
        assertThat(s.bean(RecordFixService.class).recordFix(Scenario.AUTHOR, command)).isEqualTo(first);
        assertThat(state(s, d.id())).isEqualTo(before);
    }

    // ------------------------------------------------------------------------------------------------ D-0029

    @Test
    @DisplayName("VRF-012: the verifier is none of the session that registered the defect, recorded a case or recorded a fix")
    void vrf012_everyParticipantIsRefused(Scenario s) {
        SaveResult d = s.newDefect("d");
        String fixer = s.actorWith("fix", "verify");
        String observer = s.actorWith("defect", "verify");
        String independent = s.actorWith("verify");
        s.fix(fixer, d.id(), Scenario.commit('a'));
        s.bean(RecordCaseService.class).recordCase(observer,
                new RecordCaseCommand(Scenario.uuid(), d.id(), "seen", Scenario.REASON, null, null, null));
        long version = s.defect(d.id()).version();
        for (String participant : List.of(Scenario.AUTHOR, fixer, observer)) {
            Expect.failure(() -> review(s, participant, "defect", d.id(), version, "VERIFIED"),
                    TransitionCode.SAME_SESSION);
        }
        assertThat(s.defect(d.id()).status()).isEqualTo(DefectStatus.SOURCE_FIXED);
        assertThat(review(s, independent, "defect", d.id(), version, "VERIFIED").to()).isEqualTo("VERIFIED");
        assertThat(s.defect(d.id()).status()).isEqualTo(DefectStatus.VERIFIED);

        // a mix of the fixes of the registering session and of another one: the other one cannot verify either
        SaveResult mixed = s.newDefect("mixed");
        s.fix(Scenario.AUTHOR, mixed.id(), Scenario.commit('a'));
        s.fix(fixer, mixed.id(), Scenario.commit('b'));
        long mixedVersion = s.defect(mixed.id()).version();
        Expect.failure(() -> review(s, fixer, "defect", mixed.id(), mixedVersion, "VERIFIED"),
                TransitionCode.SAME_SESSION);
        assertThat(review(s, independent, "defect", mixed.id(), mixedVersion, "VERIFIED").to()).isEqualTo("VERIFIED");
    }

    @Test
    @DisplayName("VRF-015: a task is verified by none of the sessions that registered it, own it or reported it done at any time")
    void vrf015_taskParticipantsAreRefused(Scenario s) {
        String owner = s.actorWith("task", "verify");
        String first = s.actorWith("task", "verify");
        String second = s.actorWith("task", "verify");
        String independent = s.actorWith("verify");
        var fields = s.taskFields("t");
        fields.put("owner_actor_id", owner);
        SaveResult t = s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("task", null, fields));
        // first reports it, the task goes back to work (by the registering session), second reports it again
        s.bean(TransitionService.class).transition(first, s.move("task", t.id(), 1, "REPORTED_DONE", Scenario.EVIDENCE));
        s.bean(TransitionService.class).transition(Scenario.AUTHOR, s.move("task", t.id(), 2, "ACTIVE", null));
        s.bean(TransitionService.class).transition(second, s.move("task", t.id(), 3, "REPORTED_DONE", Scenario.EVIDENCE));
        assertThat(s.task(t.id()).version()).isEqualTo(4);
        // the registering session (it reported nothing), the owner, the earlier and the latest reporter
        for (String participant : List.of(Scenario.AUTHOR, owner, first, second)) {
            Expect.failure(() -> review(s, participant, "task", t.id(), 4, "VERIFIED"), TransitionCode.SAME_SESSION);
        }
        assertThat(s.task(t.id()).state()).isEqualTo(TaskState.REPORTED_DONE);
        assertThat(review(s, independent, "task", t.id(), 4, "VERIFIED").to()).isEqualTo("VERIFIED");
    }

    @Test
    @DisplayName("VRF-016: a release condition is judged (MET, NOT_MET) by none of the session that registered it; leaving MET needs no such check")
    void vrf016_conditionsFollowTheSamePrinciple(Scenario s) {
        SaveResult c = s.newCondition("1", "r1", "c1");
        for (String to : List.of("MET", "NOT_MET")) {
            Expect.failure(() -> review(s, Scenario.AUTHOR, "condition", c.id(), 1, to), TransitionCode.SAME_SESSION);
        }
        assertThat(s.bean(org.pgledger.application.write.Rows.class).condition(
                org.pgledger.domain.value.ConditionId.of(c.id())).state()).isEqualTo(
                        org.pgledger.domain.rule.ConditionState.UNKNOWN);
        String independent = s.actorWith("verify");
        assertThat(review(s, independent, "condition", c.id(), 1, "MET").to()).isEqualTo("MET");
        assertThat(review(s, Scenario.AUTHOR, "condition", c.id(), 2, "UNKNOWN").to()).isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("VRF-018: the registrant and the case sessions of a defect that was merged into this one count as reporters of it, the fixes of the merged defect do not")
    void vrf018_mergedDefectsAreReported(Scenario s) {
        String registrarOfB = s.actorWith("defect", "verify");
        String observerOfB = s.actorWith("defect", "verify");
        String fixerOfA = s.actorWith("fix", "verify");
        String fixerOfB = s.actorWith("fix", "verify");
        SaveResult a = s.newDefect("a");
        var fieldsOfB = s.defectFields("b");
        SaveResult b = s.bean(SaveService.class).save(registrarOfB, s.create("defect", null, fieldsOfB));
        s.bean(RecordCaseService.class).recordCase(observerOfB,
                new RecordCaseCommand(Scenario.uuid(), b.id(), "seen", Scenario.REASON, null, null, null));
        s.fix(fixerOfA, a.id(), Scenario.commit('a'));
        s.fix(fixerOfB, b.id(), Scenario.commit('b'));
        MergeFixture.merge(s, b.id(), s.defect(b.id()).version(), a.id());
        long version = s.defect(a.id()).version();
        for (String refused : List.of(Scenario.AUTHOR, registrarOfB, observerOfB, fixerOfA)) {
            Expect.failure(() -> review(s, refused, "defect", a.id(), version, "VERIFIED"),
                    TransitionCode.SAME_SESSION);
        }
        // a session whose only part is a fix of the merged defect: the detail of A does not show that fix as one of A
        assertThat(review(s, fixerOfB, "defect", a.id(), version, "VERIFIED").to()).isEqualTo("VERIFIED");
    }

    @Test
    @DisplayName("VRF-019: every record_fix saves the defect row with its version: the version moves, and a read of the row from before the fix cannot be saved")
    void vrf019_recordFixAlwaysWritesTheDefect(Scenario s) {
        SaveResult d = s.sourceFixedDefect("d");
        var staleRead = s.defect(d.id());
        assertThat(staleRead.version()).isEqualTo(2);
        // a new commit by the owner (the owner and the status stay) and new CI data of a known commit
        RecordFixResult sameOwner = s.fix(Scenario.AUTHOR, d.id(), Scenario.commit('b'));
        assertThat(sameOwner.version()).isEqualTo(3);
        assertThat(sameOwner.transitionHistoryId()).isNull();
        RecordFixResult ci = s.bean(RecordFixService.class).recordFix(Scenario.AUTHOR,
                fix(d.id(), Scenario.commit('a'), "ci-1", "success", null));
        assertThat(ci.version()).isEqualTo(4);
        assertThat(ci.transitionHistoryId()).isNull();
        assertThat(s.defect(d.id()).version()).isEqualTo(4);
        // the verification that read the defect before the fixes loses against them: its conditional save conflicts
        var stale = staleRead.transitioned(DefectStatus.VERIFIED, null, java.time.Instant.parse("2026-11-16T00:00:00Z"));
        Expect.failure(() -> s.ctx().tx(() -> ScopedValue.where(ActorScope.CURRENT, AuthorId.OWNER)
                .call(() -> s.bean(org.pgledger.application.write.Rows.class).saveDefect(stale))),
                ConflictCode.VERSION_CONFLICT);
        assertThat(s.defect(d.id()).status()).isEqualTo(DefectStatus.SOURCE_FIXED);
    }

    @Test
    @DisplayName("VRF-049: record_fix runs no code check: a defect with the retired severity UNRATED is fixed, by the owner, by another session and with new CI data")
    void vrf049_unratedDefectIsFixed(Scenario s) {
        SaveResult open = s.newDefect("open");
        SaveResult fixed = s.sourceFixedDefect("fixed");
        // SQL, because no service makes this state: UNRATED is the severity of an imported defect
        for (SaveResult d : List.of(open, fixed)) {
            s.ctx().jdbc().sql("UPDATE defect SET severity = 'UNRATED' WHERE id = ?").param(d.id()).update();
        }
        // an OPEN defect becomes SOURCE_FIXED
        assertThat(s.fix(Scenario.AUTHOR, open.id(), Scenario.commit('a')).defectStatus()).isEqualTo("SOURCE_FIXED");
        // by another session (the owner changes), by the owner (nothing but the version moves), CI data of a known commit
        String other = s.actorWith("fix");
        assertThat(s.fix(other, fixed.id(), Scenario.commit('b')).ownerActorId()).isEqualTo(other);
        assertThat(s.fix(other, fixed.id(), Scenario.commit('c')).transitionHistoryId()).isNull();
        s.bean(RecordFixService.class).recordFix(other, fix(fixed.id(), Scenario.commit('c'), "ci-1", "success", null));
        for (SaveResult d : List.of(open, fixed)) {
            assertThat(s.defect(d.id()).severity().value()).isEqualTo("UNRATED");
        }
        // a save of such a defect without a rating is still refused: UNRATED is never active
        Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR,
                s.edit("defect", open.id(), s.defect(open.id()).version(), Map.of("title", "changed"))),
                org.pgledger.domain.error.ValidationCode.CODE_INVALID);
    }

    @Test
    @DisplayName("VRF-050: a state change runs no code check: a defect with the retired severity UNRATED is moved, verified and merged; only the edit of a save forces the rating")
    void vrf050_unratedDefectChangesState(Scenario s) {
        SaveResult toFixed = s.newDefect("to fixed");
        SaveResult toVerified = s.newDefect("to verified");
        SaveResult notDefect = s.newDefect("not a defect");
        SaveResult disputed = s.newDefect("disputed");
        SaveResult merged = s.newDefect("merged");
        SaveResult target = s.newDefect("target");
        // SQL, because no service makes this state: UNRATED is the severity of an imported defect
        for (SaveResult d : List.of(toFixed, toVerified, notDefect, disputed, merged)) {
            s.ctx().jdbc().sql("UPDATE defect SET severity = 'UNRATED' WHERE id = ?").param(d.id()).update();
        }
        TransitionService transitions = s.bean(TransitionService.class);
        // a fix that leaves the defect OPEN, then the move to SOURCE_FIXED
        s.bean(RecordFixService.class).recordFix(Scenario.AUTHOR, fix(toFixed.id(), Scenario.commit('a'), null, null, false));
        assertThat(transitions.transition(Scenario.AUTHOR,
                s.move("defect", toFixed.id(), s.defect(toFixed.id()).version(), "SOURCE_FIXED", null)).to())
                .isEqualTo("SOURCE_FIXED");
        assertThat(transitions.transition(Scenario.AUTHOR,
                s.move("defect", notDefect.id(), 1, "NOT_DEFECT", Scenario.EVIDENCE)).to()).isEqualTo("NOT_DEFECT");
        assertThat(transitions.transition(Scenario.AUTHOR,
                s.move("defect", disputed.id(), 1, "DISPUTED", Scenario.EVIDENCE)).to()).isEqualTo("DISPUTED");
        MergeFixture.merge(s, merged.id(), 1, target.id());
        // verification by an independent session
        s.fix(Scenario.AUTHOR, toVerified.id(), Scenario.commit('b'));
        String independent = s.actorWith("verify");
        assertThat(review(s, independent, "defect", toVerified.id(), s.defect(toVerified.id()).version(), "VERIFIED").to())
                .isEqualTo("VERIFIED");
        for (SaveResult d : List.of(toFixed, toVerified, notDefect, disputed, merged)) {
            assertThat(s.defect(d.id()).severity().value()).as(d.id()).isEqualTo("UNRATED");
        }
        assertThat(s.defect(merged.id()).status()).isEqualTo(DefectStatus.MERGED);
        // the edit of a save still has to give the rating
        Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR,
                s.edit("defect", notDefect.id(), s.defect(notDefect.id()).version(), Map.of("title", "changed"))),
                org.pgledger.domain.error.ValidationCode.CODE_INVALID);
    }

    @org.junit.jupiter.api.RepeatedTest(20)
    @DisplayName("VRF-028: a record_fix of the owner and a verification of the same defect at the same time never both succeed, and no fix row lands on the verified defect")
    void vrf028_fixAgainstVerification(Scenario s) {
        SaveResult d = s.sourceFixedDefect("d");
        String independent = s.actorWith("verify");
        List<java.util.concurrent.Callable<Object>> calls = List.of(
                () -> s.fix(Scenario.AUTHOR, d.id(), Scenario.commit('b')),
                () -> review(s, independent, "defect", d.id(), 2, "VERIFIED"));
        var outcomes = org.pgledger.application.support.Parallel.all(calls);
        assertThat(outcomes.stream().filter(o -> o.succeeded())).hasSizeLessThan(2);
        for (var outcome : outcomes) {
            if (!outcome.succeeded()) {
                assertThat(outcome.failure()).isInstanceOf(LedgerException.class);
                assertThat(((LedgerException) outcome.failure()).code()).isIn(ConflictCode.VERSION_CONFLICT,
                        TransitionCode.TRANSITION_INVALID, TransitionCode.VERIFIED_DEFECT);
            }
        }
        if (outcomes.get(1).succeeded()) {
            assertThat(s.count("SELECT COUNT(*) FROM defect_fix WHERE defect_id = ?", d.id())).isEqualTo(1);
        }
    }
}
