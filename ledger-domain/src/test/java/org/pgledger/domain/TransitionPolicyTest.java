package org.pgledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.pgledger.domain.error.TransitionCode;
import org.pgledger.domain.model.Defect;
import org.pgledger.domain.rule.ConditionState;
import org.pgledger.domain.rule.DefectStatus;
import org.pgledger.domain.rule.FixHistory;
import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.rule.MergeTarget;
import org.pgledger.domain.rule.Scope;
import org.pgledger.domain.rule.TaskReports;
import org.pgledger.domain.rule.TaskState;
import org.pgledger.domain.rule.TaskTransition;
import org.pgledger.domain.value.AuthorId;
import org.pgledger.domain.value.DefectId;
import org.pgledger.domain.value.Evidence;
import org.pgledger.domain.value.SessionKey;

/** DOM-009 .. DOM-019: the transition policies are pure functions of their facts. */
class TransitionPolicyTest {

    private static final Evidence EVIDENCE = Evidence.of("https://ci.example/run/1", null);
    private static final AuthorId ME = AuthorId.of("claude:me");
    private static final AuthorId OTHER = AuthorId.of("codex:other");
    private static final DefectId SELF = DefectId.of("E2E-D-0001");
    private static final DefectId LIVE = DefectId.of("E2E-D-0002");
    private static final FixHistory OTHER_FIX = new FixHistory(List.of(OTHER.sessionKey()), List.of());
    private static final FixHistory OWN_FIX = new FixHistory(List.of(ME.sessionKey()), List.of());

    private static void defect(DefectStatus from, DefectStatus to, Evidence evidence, FixHistory fixes,
            MergeTarget target) {
        from.requireTransitionTo(to, evidence, fixes, ME.sessionKey(), SELF, target);
    }

    @Test
    void dom009_everyPairFollowsTheRules() {
        MergeTarget live = new MergeTarget(LIVE, DefectStatus.OPEN);
        for (DefectStatus from : DefectStatus.values()) {
            for (DefectStatus to : DefectStatus.values()) {
                boolean denied = from == to || (to == DefectStatus.VERIFIED && from != DefectStatus.SOURCE_FIXED);
                if (denied) {
                    org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> defect(from, to, EVIDENCE, OTHER_FIX, live))
                            .as("%s -> %s", from, to)
                            .isInstanceOf(org.pgledger.domain.error.TransitionException.class);
                } else {
                    assertThatCode(() -> defect(from, to, EVIDENCE, OTHER_FIX, live)).as("%s -> %s", from, to)
                            .doesNotThrowAnyException();
                }
            }
        }
        Expect.failsWith(TransitionCode.TRANSITION_INVALID,
                () -> defect(DefectStatus.OPEN, DefectStatus.OPEN, EVIDENCE, OTHER_FIX, live));
    }

    @Test
    void dom010_sourceFixedNeedsAFix() {
        Expect.failsWith(TransitionCode.FIX_REQUIRED,
                () -> defect(DefectStatus.OPEN, DefectStatus.SOURCE_FIXED, Evidence.NONE, FixHistory.NONE, null));
        assertThatCode(() -> defect(DefectStatus.OPEN, DefectStatus.SOURCE_FIXED, Evidence.NONE, OWN_FIX, null))
                .doesNotThrowAnyException();
    }

    @Test
    void dom011_verifiedOnlyFromSourceFixed() {
        for (DefectStatus from : List.of(DefectStatus.OPEN, DefectStatus.NOT_DEFECT, DefectStatus.DISPUTED,
                DefectStatus.MERGED)) {
            Expect.failsWith(TransitionCode.TRANSITION_INVALID,
                    () -> defect(from, DefectStatus.VERIFIED, Evidence.NONE, FixHistory.NONE, null));
        }
    }

    @Test
    void dom012_verifiedChecksEvidenceThenFixThenSession() {
        DefectStatus sf = DefectStatus.SOURCE_FIXED;
        Expect.failsWith(TransitionCode.EVIDENCE_REQUIRED,
                () -> defect(sf, DefectStatus.VERIFIED, Evidence.NONE, FixHistory.NONE, null));
        Expect.failsWith(TransitionCode.FIX_REQUIRED,
                () -> defect(sf, DefectStatus.VERIFIED, EVIDENCE, FixHistory.NONE, null));
        Expect.failsWith(TransitionCode.SAME_SESSION, () -> defect(sf, DefectStatus.VERIFIED, EVIDENCE, OWN_FIX, null));
        assertThatCode(() -> defect(sf, DefectStatus.VERIFIED, EVIDENCE, OTHER_FIX, null)).doesNotThrowAnyException();
        // VRF-010 (D-0029): a mix of own and independent fixes is refused, the verifier differs from every fixer
        FixHistory mixed = new FixHistory(List.of(ME.sessionKey(), OTHER.sessionKey()), List.of());
        Expect.failsWith(TransitionCode.SAME_SESSION, () -> defect(sf, DefectStatus.VERIFIED, EVIDENCE, mixed, null));
        FixHistory mixedTheOtherWay = new FixHistory(List.of(OTHER.sessionKey(), ME.sessionKey()), List.of());
        Expect.failsWith(TransitionCode.SAME_SESSION,
                () -> defect(sf, DefectStatus.VERIFIED, EVIDENCE, mixedTheOtherWay, null));
    }

    @Test
    void vrf011_theVerifierIsNoReporterEither() {
        DefectStatus sf = DefectStatus.SOURCE_FIXED;
        AuthorId registrar = AuthorId.of("claude:registrar");
        AuthorId observer = AuthorId.of("codex:observer");
        AuthorId fixer = AuthorId.of("codex:fixer");
        AuthorId independent = AuthorId.of("claude:independent");
        FixHistory facts = new FixHistory(List.of(fixer.sessionKey()),
                List.of(registrar.sessionKey(), observer.sessionKey()));
        for (AuthorId participant : List.of(registrar, observer, fixer)) {
            Expect.failsWith(TransitionCode.SAME_SESSION, () -> sf.requireTransitionTo(DefectStatus.VERIFIED, EVIDENCE,
                    facts, participant.sessionKey(), SELF, null));
        }
        assertThatCode(() -> sf.requireTransitionTo(DefectStatus.VERIFIED, EVIDENCE, facts,
                independent.sessionKey(), SELF, null)).doesNotThrowAnyException();
        // a defect row without a registering session adds nobody to the reporters
        FixHistory noReporter = new FixHistory(List.of(fixer.sessionKey()), List.of());
        assertThatCode(() -> sf.requireTransitionTo(DefectStatus.VERIFIED, EVIDENCE, noReporter,
                registrar.sessionKey(), SELF, null)).doesNotThrowAnyException();
        // the order of the checks is the one of DOM-012: evidence, then the fix, then the session
        Expect.failsWith(TransitionCode.EVIDENCE_REQUIRED, () -> sf.requireTransitionTo(DefectStatus.VERIFIED,
                Evidence.NONE, facts, registrar.sessionKey(), SELF, null));
        Expect.failsWith(TransitionCode.FIX_REQUIRED, () -> sf.requireTransitionTo(DefectStatus.VERIFIED, EVIDENCE,
                new FixHistory(List.of(), List.of(registrar.sessionKey())), registrar.sessionKey(), SELF, null));
        assertThat(FixHistory.NONE.hasFix()).isFalse();
        assertThat(FixHistory.NONE.involves(registrar.sessionKey())).isFalse();
        assertThat(facts.involves(observer.sessionKey())).isTrue();
    }

    @Test
    void vrf001_onlyAVerifiedDefectRefusesAContentChange() {
        for (DefectStatus status : DefectStatus.values()) {
            Defect defect = DomainFixtures.defect(status, status == DefectStatus.MERGED ? LIVE : null);
            if (status == DefectStatus.VERIFIED) {
                assertThat(Expect.failsWith(TransitionCode.VERIFIED_DEFECT, defect::requireNotVerified).args())
                        .containsExactly(defect.id().value());
            } else {
                assertThatCode(defect::requireNotVerified).as(status.name()).doesNotThrowAnyException();
            }
        }
    }

    @Test
    void dom013_leavingVerifiedNeedsEvidenceAfterTheFixCheck() {
        Expect.failsWith(TransitionCode.EVIDENCE_REQUIRED,
                () -> defect(DefectStatus.VERIFIED, DefectStatus.OPEN, Evidence.NONE, OTHER_FIX, null));
        assertThatCode(() -> defect(DefectStatus.VERIFIED, DefectStatus.OPEN, EVIDENCE, OTHER_FIX, null))
                .doesNotThrowAnyException();
        // the SOURCE_FIXED-needs-fix check comes first
        Expect.failsWith(TransitionCode.FIX_REQUIRED,
                () -> defect(DefectStatus.VERIFIED, DefectStatus.SOURCE_FIXED, Evidence.NONE, FixHistory.NONE, null));
    }

    @Test
    void dom014_notDefectAndDisputedNeedEvidence() {
        for (DefectStatus to : List.of(DefectStatus.NOT_DEFECT, DefectStatus.DISPUTED)) {
            Expect.failsWith(TransitionCode.EVIDENCE_REQUIRED,
                    () -> defect(DefectStatus.OPEN, to, Evidence.NONE, FixHistory.NONE, null));
        }
    }

    @Test
    void dom015_mergeNeedsALiveTargetAndMergedIntoFollowsTheStatus() {
        for (MergeTarget bad : new MergeTarget[] {null, new MergeTarget(SELF, DefectStatus.OPEN),
                new MergeTarget(LIVE, DefectStatus.MERGED)}) {
            Expect.failsWith(TransitionCode.MERGE_TARGET_INVALID,
                    () -> defect(DefectStatus.OPEN, DefectStatus.MERGED, Evidence.NONE, FixHistory.NONE, bad));
        }
        assertThatCode(() -> defect(DefectStatus.OPEN, DefectStatus.MERGED, Evidence.NONE, FixHistory.NONE,
                new MergeTarget(LIVE, DefectStatus.SOURCE_FIXED))).doesNotThrowAnyException();

        Instant now = Instant.parse("2026-11-05T10:00:00Z");
        Defect open = DomainFixtures.defect(DefectStatus.OPEN, null);
        Defect merged = open.transitioned(DefectStatus.MERGED, LIVE, now);
        assertThat(merged.mergedInto()).isEqualTo(LIVE);
        assertThat(merged.transitioned(DefectStatus.OPEN, null, now).mergedInto()).isNull();
        Expect.invalid(() -> open.transitioned(DefectStatus.MERGED, null, now));
        Expect.invalid(() -> merged.transitioned(DefectStatus.OPEN, LIVE, now));
    }

    @Test
    void dom016_scopeOfATransition() {
        assertThat(Scope.forTransition(HistoryEntity.DEFECT, DefectStatus.OPEN, DefectStatus.VERIFIED))
                .isEqualTo(Scope.VERIFY);
        assertThat(Scope.forTransition(HistoryEntity.DEFECT, DefectStatus.VERIFIED, DefectStatus.OPEN))
                .isEqualTo(Scope.VERIFY);
        assertThat(Scope.forTransition(HistoryEntity.DEFECT, DefectStatus.OPEN, DefectStatus.DISPUTED))
                .isEqualTo(Scope.DEFECT);
        assertThat(Scope.forTransition(HistoryEntity.TASK, TaskState.ACTIVE, TaskState.REPORTED_DONE))
                .isEqualTo(Scope.TASK);
        assertThat(Scope.forTransition(HistoryEntity.TASK, TaskState.REPORTED_DONE, TaskState.VERIFIED))
                .isEqualTo(Scope.VERIFY);
        assertThat(Scope.forTransition(HistoryEntity.CONDITION, ConditionState.UNKNOWN, ConditionState.MET))
                .isEqualTo(Scope.VERIFY);
        assertThat(Scope.forTransition(HistoryEntity.CONDITION, ConditionState.NOT_MET, ConditionState.UNKNOWN))
                .isEqualTo(Scope.VERIFY);
        Expect.invalid(() -> Scope.forTransition(HistoryEntity.DEFECT_FIX, DefectStatus.OPEN, DefectStatus.DISPUTED));
    }

    private static void task(TaskState from, TaskState to, Evidence evidence, AuthorId actor, AuthorId owner,
            TaskReports reports) {
        if (owner == null) {
            from.requireTransitionTo(to, evidence, actor.sessionKey(), reports);
        } else {
            from.requireTransitionTo(to, evidence, actor.sessionKey(), List.of(owner.sessionKey()), reports);
        }
    }

    @Test
    void dom017_aTaskWithoutOwnerAndATaskOwnedByAnotherSessionAgree() {
        TaskReports none = new TaskReports(List.of());
        // the owner overload takes a session, never an Optional; a different owner changes nothing
        assertThatCode(() -> TaskState.REPORTED_DONE.requireTransitionTo(TaskState.VERIFIED, EVIDENCE,
                OTHER.sessionKey(), List.of(ME.sessionKey()), none)).doesNotThrowAnyException();
        assertThatCode(() -> TaskState.REPORTED_DONE.requireTransitionTo(TaskState.VERIFIED, EVIDENCE,
                OTHER.sessionKey(), none)).doesNotThrowAnyException();
        assertThatCode(() -> TaskState.REPORTED_DONE.requireTransitionTo(TaskState.VERIFIED, EVIDENCE,
                OTHER.sessionKey(), List.of(), none)).doesNotThrowAnyException();
        Expect.failsWith(TransitionCode.SAME_SESSION, () -> TaskState.REPORTED_DONE.requireTransitionTo(
                TaskState.VERIFIED, EVIDENCE, OTHER.sessionKey(), List.of(OTHER.sessionKey()), none));
        // the same session key is the same session
        Expect.failsWith(TransitionCode.SAME_SESSION, () -> TaskState.REPORTED_DONE.requireTransitionTo(
                TaskState.VERIFIED, EVIDENCE, OTHER.sessionKey(), List.of(new SessionKey(OTHER.value())), none));
    }

    @Test
    void vrf014_theVerifierIsNoRegistrantAndNoReporterOfTheTaskAtAnyTime() {
        AuthorId creator = AuthorId.of("claude:creator");
        AuthorId owner = AuthorId.of("codex:owner");
        AuthorId first = AuthorId.of("claude:first");
        AuthorId second = AuthorId.of("codex:second");
        AuthorId reviewer = AuthorId.of("codex:reviewer");
        AuthorId independent = AuthorId.of("claude:independent");
        // first reports, the reviewer verifies and sends the task back (no report), second reports again
        TaskReports history = new TaskReports(List.of(
                new TaskTransition(TaskState.ACTIVE, TaskState.REPORTED_DONE, first),
                new TaskTransition(TaskState.REPORTED_DONE, TaskState.VERIFIED, reviewer),
                new TaskTransition(TaskState.VERIFIED, TaskState.REPORTED_DONE, reviewer),
                new TaskTransition(TaskState.ACTIVE, TaskState.REPORTED_DONE, second)));
        List<SessionKey> registrants = List.of(creator.sessionKey(), owner.sessionKey());
        for (AuthorId refused : List.of(creator, owner, first, second)) {
            Expect.failsWith(TransitionCode.SAME_SESSION, () -> TaskState.REPORTED_DONE.requireTransitionTo(
                    TaskState.VERIFIED, EVIDENCE, refused.sessionKey(), registrants, history));
        }
        // the reviewer only sent the task back: that is no report
        for (AuthorId allowed : List.of(reviewer, independent)) {
            assertThatCode(() -> TaskState.REPORTED_DONE.requireTransitionTo(TaskState.VERIFIED, EVIDENCE,
                    allowed.sessionKey(), registrants, history)).doesNotThrowAnyException();
        }
        // nobody is known: nothing refuses
        assertThatCode(() -> TaskState.REPORTED_DONE.requireTransitionTo(TaskState.VERIFIED, EVIDENCE,
                creator.sessionKey(), List.of(), new TaskReports(List.of()))).doesNotThrowAnyException();
        // reportedBy answers for a report at any time; the latest reporter stays the one of the last report
        assertThat(history.reportedBy(first.sessionKey())).isTrue();
        assertThat(history.reportedBy(second.sessionKey())).isTrue();
        assertThat(history.reportedBy(reviewer.sessionKey())).isFalse();
        assertThat(history.latestReporter()).contains(second.sessionKey());
    }

    @Test
    void dom018_reportsAnswerWhetherASessionReportedTheTask() {
        TaskReports history = new TaskReports(List.of(
                new TaskTransition(TaskState.ACTIVE, TaskState.REPORTED_DONE, ME),
                new TaskTransition(TaskState.REPORTED_DONE, TaskState.VERIFIED, OTHER)));
        assertThat(history.reportedBy(ME.sessionKey())).isTrue();
        assertThat(history.reportedBy(OTHER.sessionKey())).isFalse();
        assertThat(new TaskReports(List.of()).reportedBy(ME.sessionKey())).isFalse();
    }

    @Test
    void dom017_taskPolicy() {
        TaskReports none = new TaskReports(List.of());
        Expect.failsWith(TransitionCode.TRANSITION_INVALID,
                () -> task(TaskState.PLANNED, TaskState.UNKNOWN, EVIDENCE, ME, null, none));
        Expect.failsWith(TransitionCode.TRANSITION_INVALID,
                () -> task(TaskState.ACTIVE, TaskState.ACTIVE, EVIDENCE, ME, null, none));
        Expect.failsWith(TransitionCode.EVIDENCE_REQUIRED,
                () -> task(TaskState.ACTIVE, TaskState.REPORTED_DONE, Evidence.NONE, ME, null, none));
        Expect.failsWith(TransitionCode.EVIDENCE_REQUIRED,
                () -> task(TaskState.VERIFIED, TaskState.ACTIVE, Evidence.NONE, OTHER, null, none));
        assertThatCode(() -> task(TaskState.VERIFIED, TaskState.ACTIVE, EVIDENCE, OTHER, null, none))
                .doesNotThrowAnyException();
        Expect.failsWith(TransitionCode.TRANSITION_INVALID,
                () -> task(TaskState.ACTIVE, TaskState.VERIFIED, EVIDENCE, OTHER, null, none));
        Expect.failsWith(TransitionCode.EVIDENCE_REQUIRED,
                () -> task(TaskState.REPORTED_DONE, TaskState.VERIFIED, Evidence.NONE, OTHER, null, none));
        // the owner cannot verify
        Expect.failsWith(TransitionCode.SAME_SESSION,
                () -> task(TaskState.REPORTED_DONE, TaskState.VERIFIED, EVIDENCE, OTHER, OTHER, none));
        // the reporter cannot verify
        TaskReports reportedByMe = new TaskReports(
                List.of(new TaskTransition(TaskState.ACTIVE, TaskState.REPORTED_DONE, ME)));
        Expect.failsWith(TransitionCode.SAME_SESSION,
                () -> task(TaskState.REPORTED_DONE, TaskState.VERIFIED, EVIDENCE, ME, null, reportedByMe));
        assertThatCode(() -> task(TaskState.REPORTED_DONE, TaskState.VERIFIED, EVIDENCE, OTHER, null, reportedByMe))
                .doesNotThrowAnyException();
    }

    @Test
    void dom018_reporterIsTheLatestReportThatDidNotComeFromVerified() {
        TaskReports history = new TaskReports(List.of(
                new TaskTransition(TaskState.ACTIVE, TaskState.REPORTED_DONE, ME),
                new TaskTransition(TaskState.REPORTED_DONE, TaskState.VERIFIED, OTHER),
                new TaskTransition(TaskState.VERIFIED, TaskState.REPORTED_DONE, OTHER)));
        assertThat(history.latestReporter()).contains(new SessionKey("claude:me"));
        assertThat(new TaskReports(List.of()).latestReporter()).isEmpty();
    }

    @Test
    void dom019_conditionPolicy() {
        for (ConditionState to : List.of(ConditionState.MET, ConditionState.NOT_MET)) {
            Expect.failsWith(TransitionCode.EVIDENCE_REQUIRED,
                    () -> ConditionState.UNKNOWN.requireTransitionTo(to, Evidence.NONE));
            assertThatCode(() -> ConditionState.UNKNOWN.requireTransitionTo(to, EVIDENCE)).doesNotThrowAnyException();
        }
        // leaving MET or NOT_MET for UNKNOWN needs no evidence (unchanged from the old ledger by design)
        assertThatCode(() -> ConditionState.MET.requireTransitionTo(ConditionState.UNKNOWN, Evidence.NONE))
                .doesNotThrowAnyException();
        Expect.failsWith(TransitionCode.TRANSITION_INVALID,
                () -> ConditionState.MET.requireTransitionTo(ConditionState.MET, EVIDENCE));
    }
}
