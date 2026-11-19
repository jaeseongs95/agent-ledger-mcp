// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.application;

import static org.assertj.core.api.Assertions.assertThat;
import org.pgledger.testing.TestOwners;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.pgledger.application.admin.CommonCodeService;
import org.pgledger.application.admin.CommonCodeService.CodeCommand;
import org.pgledger.application.admin.GrantService;
import org.pgledger.application.admin.GrantService.GrantCommand;
import org.pgledger.application.admin.GrantService.RevokeCommand;
import org.pgledger.application.admin.SessionKeyService;
import org.pgledger.application.support.Expect;
import org.pgledger.application.support.Fields;
import org.pgledger.application.support.LedgerServiceTest;
import org.pgledger.application.support.MergeFixture;
import org.pgledger.application.support.Parallel;
import org.pgledger.application.support.Parallel.Outcome;
import org.pgledger.application.support.Scenario;
import org.pgledger.application.write.BatchOpInput;
import org.pgledger.application.write.BatchService;
import org.pgledger.application.write.CorrectCommand;
import org.pgledger.application.write.CorrectService;
import org.pgledger.application.write.LinkCommand;
import org.pgledger.application.write.LinkService;
import org.pgledger.application.write.RecordCaseCommand;
import org.pgledger.application.write.RecordCaseService;
import org.pgledger.application.write.RecordDeliverableCommand;
import org.pgledger.application.write.RecordDeliverableService;
import org.pgledger.application.write.ReviewService;
import org.pgledger.application.write.SaveService;
import org.pgledger.application.write.TransitionService;
import org.pgledger.application.write.WriteResult;
import org.pgledger.application.write.WriteResult.SaveResult;
import org.pgledger.database.repository.IssueCaseRepository;
import org.pgledger.domain.error.ConflictCode;
import org.pgledger.domain.error.ErrorCode;
import org.pgledger.domain.error.LedgerException;
import org.pgledger.domain.error.TransitionCode;
import org.pgledger.domain.error.TrustCode;
import org.pgledger.domain.error.ValidationCode;
import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.rule.HistoryOp;
import org.pgledger.domain.rule.IssueStatus;
import org.pgledger.domain.value.IssueId;
import org.pgledger.testing.database.LockWaits;

/** SVC-RACE-01 .. SVC-RACE-11, SVC-GRT-06: real threads and latches, committed on the PostgreSQL of the test PC. */
@LedgerServiceTest
class RaceTest {

    private static Optional<ErrorCode> code(Outcome<?> outcome) {
        return Optional.ofNullable(outcome.failure()).map(e -> ((LedgerException) e).code());
    }

    private static List<Optional<ErrorCode>> codes(List<? extends Outcome<?>> outcomes) {
        return outcomes.stream().map(RaceTest::code).toList();
    }

    private <T> Future<T> holdingTransaction(Scenario s, Supplier<T> work, CountDownLatch finished,
            CountDownLatch release) {
        return Parallel.start(() -> s.ctx().tx(() -> {
            try {
                T result = work.get();
                finished.countDown();
                release.await(60, TimeUnit.SECONDS);
                return result;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while the transaction was held open", e);
            } finally {
                finished.countDown();
            }
        }));
    }

    @Test
    @DisplayName("SVC-RACE-01 (a): a merge that is not committed yet makes the case wait, then the case is refused")
    void race01_mergeFirst(Scenario s) throws Exception {
        SaveResult source = s.newIssue("source");
        SaveResult target = s.newIssue("target");
        String caseAuthor = s.actorWith("issue");
        CountDownLatch merged = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger holderPid = new AtomicInteger();
        Future<?> merge = holdingTransaction(s, () -> {
            holderPid.set(LockWaits.backendPid(s.ctx().jdbc()));
            return MergeFixture.merge(s, source.id(), 1, target.id());
        }, merged, release);
        assertThat(merged.await(60, TimeUnit.SECONDS)).isTrue();
        Future<Optional<ErrorCode>> recording = Parallel.start(() -> Expect.outcome(() -> s.bean(
                RecordCaseService.class).recordCase(caseAuthor, new RecordCaseCommand(Scenario.uuid(), source.id(),
                        "seen", Scenario.REASON, null, null, null))));
        LockWaits.awaitWaitingFor(s.ctx().jdbc(), holderPid.get());
        release.countDown();
        merge.get(60, TimeUnit.SECONDS);
        assertThat(recording.get(60, TimeUnit.SECONDS)).contains(TransitionCode.MERGED_ISSUE);
        assertThat(s.bean(IssueCaseRepository.class).findByIssueIdOrderById(IssueId.of(source.id()))).isEmpty();
        assertThat(s.issue(source.id()).status()).isEqualTo(IssueStatus.MERGED);
    }

    @Test
    @DisplayName("SVC-RACE-01 (b): a case that is not committed yet makes the merge wait, the case then belongs to the target")
    void race01_caseFirst(Scenario s) throws Exception {
        SaveResult source = s.newIssue("source");
        SaveResult target = s.newIssue("target");
        String caseAuthor = s.actorWith("issue");
        CountDownLatch recorded = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger holderPid = new AtomicInteger();
        Future<WriteResult> recording = holdingTransaction(s, () -> {
            holderPid.set(LockWaits.backendPid(s.ctx().jdbc()));
            return s.bean(RecordCaseService.class).recordCase(
                    caseAuthor, new RecordCaseCommand(Scenario.uuid(), source.id(), "seen", Scenario.REASON, null, null,
                            null));
        }, recorded, release);
        assertThat(recorded.await(60, TimeUnit.SECONDS)).isTrue();
        Future<WriteResult> merging = Parallel.start(() -> MergeFixture.merge(s, source.id(), 1, target.id()));
        LockWaits.awaitWaitingFor(s.ctx().jdbc(), holderPid.get());
        release.countDown();
        var caseResult = (WriteResult.RecordCaseResult) recording.get(60, TimeUnit.SECONDS);
        var mergeResult = (WriteResult.TransitionResult) merging.get(60, TimeUnit.SECONDS);
        assertThat(s.issue(source.id()).status()).isEqualTo(IssueStatus.MERGED);
        assertThat(s.bean(IssueCaseRepository.class).findByIssueIdOrderById(IssueId.of(source.id()))).hasSize(1);
        assertThat(caseResult.historyId()).as("the case was recorded before the merge").isLessThan(
                mergeResult.historyId());
        assertThat(s.history(HistoryEntity.ISSUE, source.id()).stream().filter(h -> h.op() == HistoryOp.TRANSITION)
                .mapToLong(h -> h.id()).max().orElseThrow()).isEqualTo(mergeResult.historyId());
    }

    @Test
    @DisplayName("SVC-RACE-09: 20 sequential and 20 parallel cases do not touch the version, a save still succeeds")
    void race09_caseCounterDoesNotBumpTheVersion(Scenario s) {
        SaveResult d = s.newIssue("i");
        s.bean(SaveService.class).save(Scenario.AUTHOR, s.edit("issue", d.id(), 1, Map.of("title", "two")));
        s.bean(SaveService.class).save(Scenario.AUTHOR, s.edit("issue", d.id(), 2, Map.of("title", "three")));
        long issueHistory = s.history(HistoryEntity.ISSUE, d.id()).size();
        String actor = s.actorWith("issue");
        IntStream.range(0, 20).forEach(i -> s.bean(RecordCaseService.class).recordCase(actor, new RecordCaseCommand(
                Scenario.uuid(), d.id(), "seq " + i, Scenario.REASON, null, null, null)));
        assertThat(s.issue(d.id()).caseSeq()).isEqualTo(20);
        assertThat(s.issue(d.id()).version()).isEqualTo(3);
        List<Callable<WriteResult>> calls = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            int n = i;
            calls.add(() -> s.bean(RecordCaseService.class).recordCase(actor, new RecordCaseCommand(Scenario.uuid(),
                    d.id(), "par " + n, Scenario.REASON, null, null, null)));
        }
        calls.add(() -> s.bean(SaveService.class).save(Scenario.ISSUE_ONLY, s.edit("issue", d.id(), 3,
                Map.of("title", "four"))));
        List<Outcome<WriteResult>> outcomes = Parallel.all(calls, 21);
        assertThat(outcomes).allSatisfy(o -> assertThat(o.failure()).isNull());
        assertThat(s.issue(d.id()).caseSeq()).isEqualTo(40);
        assertThat(s.issue(d.id()).version()).as("only the save bumped the version").isEqualTo(4);
        assertThat(s.bean(IssueCaseRepository.class).findByIssueIdOrderById(IssueId.of(d.id()))).hasSize(40);
        assertThat(s.history(HistoryEntity.ISSUE, d.id())).hasSize((int) issueHistory + 1);
    }

    @Test
    @DisplayName("SVC-RACE-02: two transitions with the same expected version: one commits, one is a version conflict")
    void race02_transitions(Scenario s) {
        SaveResult i = s.newIssue("i");
        List<Callable<WriteResult>> calls = List.of(
                () -> s.bean(TransitionService.class).transition(Scenario.AUTHOR, s.move("issue", i.id(), 1,
                        "DISPUTED", Scenario.EVIDENCE)),
                () -> s.bean(TransitionService.class).transition(Scenario.ISSUE_ONLY, s.move("issue", i.id(), 1,
                        "NOT_AN_ISSUE", Scenario.EVIDENCE)));
        List<Outcome<WriteResult>> outcomes = Parallel.all(calls);
        assertThat(outcomes.stream().filter(Outcome::succeeded)).hasSize(1);
        assertThat(codes(outcomes)).containsExactlyInAnyOrder(Optional.empty(),
                Optional.of(ConflictCode.VERSION_CONFLICT));
        assertThat(s.issue(i.id()).version()).isEqualTo(2);
    }

    @Test
    @DisplayName("SVC-RACE-03: two edits with the same expected version: one commits, one is a version conflict")
    void race03_edits(Scenario s) {
        SaveResult i = s.newIssue("i");
        List<Callable<WriteResult>> calls = List.of(
                () -> s.bean(SaveService.class).save(Scenario.AUTHOR, s.edit("issue", i.id(), 1,
                        Map.of("title", "a"))),
                () -> s.bean(SaveService.class).save(Scenario.ISSUE_ONLY, s.edit("issue", i.id(), 1,
                        Map.of("title", "b"))));
        List<Outcome<WriteResult>> outcomes = Parallel.all(calls);
        assertThat(codes(outcomes)).containsExactlyInAnyOrder(Optional.empty(),
                Optional.of(ConflictCode.VERSION_CONFLICT));
    }

    @Test
    @DisplayName("SVC-RACE-04: two deliverables of one OPEN issue: one RESOLVED transition, a deliverable row for each success")
    void race04_twoDeliverables(Scenario s) {
        SaveResult i = s.newIssue("i");
        List<Callable<WriteResult>> calls = List.of(
                () -> s.deliver(Scenario.AUTHOR, i.id(), Scenario.ref('a')),
                () -> s.deliver(Scenario.REVIEWER, i.id(), Scenario.ref('b')));
        List<Outcome<WriteResult>> outcomes = Parallel.all(calls);
        assertThat(codes(outcomes)).allSatisfy(c -> assertThat(c).isIn(Optional.empty(),
                Optional.of(ConflictCode.VERSION_CONFLICT)));
        assertThat(s.issue(i.id()).status()).isEqualTo(IssueStatus.RESOLVED);
        assertThat(s.history(HistoryEntity.ISSUE, i.id()).stream().filter(h -> h.op() == HistoryOp.TRANSITION
                && "RESOLVED".equals(h.toState()) && "OPEN".equals(h.fromState()))).hasSize(1);
        assertThat(s.count("SELECT COUNT(*) FROM deliverable WHERE target_entity = 'ISSUE' AND target_id = ?", i.id()))
                .isEqualTo(outcomes.stream().filter(Outcome::succeeded).count());
    }

    @RepeatedTest(30)
    @DisplayName("SVC-RACE-11 (principle 4.4): a record_deliverable and a transition of the same OPEN issue at the same time, with no lock statement: both write the issue row before any counter, so neither waits for the other while holding one (no deadlock)")
    void race11_deliverableAgainstTransition(Scenario s) {
        SaveResult i = s.newIssue("i");
        List<Callable<WriteResult>> calls = List.of(
                () -> s.deliver(Scenario.REVIEWER, i.id(), Scenario.ref('c')),
                () -> s.bean(TransitionService.class).transition(Scenario.AUTHOR, s.move("issue", i.id(), 1,
                        "DISPUTED", Scenario.EVIDENCE)));
        List<Outcome<WriteResult>> outcomes = Parallel.all(calls);
        assertThat(codes(outcomes)).allSatisfy(c -> assertThat(c).isIn(Optional.empty(),
                Optional.of(ConflictCode.VERSION_CONFLICT), Optional.of(TransitionCode.TRANSITION_INVALID)));
        assertThat(outcomes.stream().filter(Outcome::succeeded)).isNotEmpty();
        assertThat(s.count("SELECT COUNT(*) FROM deliverable WHERE target_entity = 'ISSUE' AND target_id = ?", i.id()))
                .isEqualTo(outcomes.get(0).succeeded() ? 1 : 0);
    }

    @Test
    @DisplayName("SVC-RACE-05: two reviewers verify the same issue: exactly one VERIFIED")
    void race05_twoVerifications(Scenario s) {
        SaveResult i = s.resolvedIssue("i");
        String one = s.actorWith("verify");
        String two = s.actorWith("verify");
        List<Callable<WriteResult>> calls = List.of(
                () -> s.bean(ReviewService.class).review(one, s.move("issue", i.id(), 2, "VERIFIED",
                        Scenario.EVIDENCE)),
                () -> s.bean(ReviewService.class).review(two, s.move("issue", i.id(), 2, "VERIFIED",
                        Scenario.EVIDENCE)));
        List<Outcome<WriteResult>> outcomes = Parallel.all(calls);
        assertThat(outcomes.stream().filter(Outcome::succeeded)).hasSize(1);
        assertThat(outcomes.stream().filter(o -> !o.succeeded()).map(o -> ((LedgerException) o.failure()).code()))
                .allSatisfy(c -> assertThat(c).isIn(ConflictCode.VERSION_CONFLICT, TransitionCode.TRANSITION_INVALID));
        assertThat(s.issue(i.id()).status()).isEqualTo(IssueStatus.VERIFIED);
    }

    @Test
    @DisplayName("SVC-RACE-06: two identical links: one is made, the other changes nothing, no duplicate-key error")
    void race06_identicalLinks(Scenario s) {
        SaveResult i = s.newIssue("i");
        SaveResult t = s.newTask("t");
        List<Callable<WriteResult>> calls = List.of(
                () -> s.bean(LinkService.class).link(Scenario.AUTHOR, new LinkCommand(Scenario.uuid(), "issue_task",
                        i.id(), t.id(), Scenario.REASON, null)),
                () -> s.bean(LinkService.class).link(Scenario.ISSUE_ONLY, new LinkCommand(Scenario.uuid(), "issue_task",
                        i.id(), t.id(), Scenario.REASON, null)));
        List<Outcome<WriteResult>> outcomes = Parallel.all(calls);
        assertThat(codes(outcomes)).containsExactlyInAnyOrder(Optional.empty(),
                Optional.of(TransitionCode.NOTHING_CHANGES));
        assertThat(s.count("SELECT COUNT(*) FROM issue_task WHERE issue_id = ?", i.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("SVC-RACE-07: a revocation waits for a write that holds the grant, afterwards nothing is accepted")
    void race07_revokeWaitsForTheWrite(Scenario s) throws Exception {
        String actor = s.actorWith("issue");
        CountDownLatch written = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger holderPid = new AtomicInteger();
        Future<SaveResult> writing = holdingTransaction(s, () -> {
            holderPid.set(LockWaits.backendPid(s.ctx().jdbc()));
            return s.bean(SaveService.class).save(actor, s.create("issue", null, s.issueFields("held")));
        }, written, release);
        assertThat(written.await(60, TimeUnit.SECONDS)).isTrue();
        Future<Integer> revoking = Parallel.start(() -> s.bean(GrantService.class).revoke(new RevokeCommand(actor,
                null, null), TestOwners.OWNER));
        LockWaits.awaitWaitingFor(s.ctx().jdbc(), holderPid.get());
        release.countDown();
        assertThat(writing.get(60, TimeUnit.SECONDS).version()).isEqualTo(1);
        assertThat(revoking.get(60, TimeUnit.SECONDS)).isEqualTo(1);
        Expect.failure(() -> s.bean(SaveService.class).save(actor, s.create("issue", null, s.issueFields("late"))),
                TrustCode.SESSION_NOT_GRANTED);
    }

    @RepeatedTest(200)
    @DisplayName("SVC-RACE-07: a write against a revocation: a refused write left nothing, none after the revoke")
    void race07_writeAgainstRevoke(Scenario s) {
        String actor = s.actorWith("issue");
        List<Callable<Object>> calls = List.of(
                () -> s.bean(SaveService.class).save(actor, s.create("issue", null, s.issueFields("round"))),
                () -> s.bean(GrantService.class).revoke(new RevokeCommand(actor, null, null), TestOwners.OWNER));
        List<Outcome<Object>> outcomes = Parallel.all(calls);
        assertThat(outcomes.get(1).failure()).isNull();
        Optional<ErrorCode> write = code(outcomes.get(0));
        long written = s.count("SELECT COUNT(*) FROM history WHERE author_id = ? AND op = 'CREATE'", actor);
        assertThat(write.map(ErrorCode::token).orElse("written") + ":" + written)
                .isIn("written:1", "SESSION_NOT_GRANTED:0");
        assertThat(Expect.outcome(() -> s.bean(SaveService.class).save(actor, s.create("issue", null,
                s.issueFields("after"))))).contains(TrustCode.SESSION_NOT_GRANTED);
    }

    @Test
    @DisplayName("SVC-RACE-10 (principle 4.4): the key of the author is read, not locked: closing it does not wait for a write that has verified it; afterwards nothing is accepted")
    void race10_keyCloseDoesNotWaitForTheWrite(Scenario s) throws Exception {
        String actor = s.actorWith("issue");
        CountDownLatch written = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<SaveResult> writing = holdingTransaction(s, () -> s.bean(SaveService.class).save(actor,
                s.create("issue", null, s.issueFields("held"))), written, release);
        assertThat(written.await(60, TimeUnit.SECONDS)).isTrue();
        Future<Boolean> closing = Parallel.start(() -> s.bean(SessionKeyService.class).revoke(actor,
                TestOwners.OWNER));
        assertThat(closing.get(30, TimeUnit.SECONDS)).as("the close did not wait for the open write").isTrue();
        release.countDown();
        assertThat(writing.get(60, TimeUnit.SECONDS).version()).isEqualTo(1);
        Expect.failure(() -> s.bean(SaveService.class).save(actor, s.create("issue", null, s.issueFields("late"))),
                TrustCode.ATTESTATION_SIGNATURE_INVALID);
    }

    @RepeatedTest(100)
    @DisplayName("SVC-RACE-10: a write against the close of its key: a refused write left nothing, none after the close")
    void race10_writeAgainstKeyClose(Scenario s) {
        String actor = s.actorWith("issue");
        List<Callable<Object>> calls = List.of(
                () -> s.bean(SaveService.class).save(actor, s.create("issue", null, s.issueFields("round"))),
                () -> s.bean(SessionKeyService.class).revoke(actor, TestOwners.OWNER));
        List<Outcome<Object>> outcomes = Parallel.all(calls);
        assertThat(outcomes.get(1).failure()).isNull();
        Optional<ErrorCode> write = code(outcomes.get(0));
        long written = s.count("SELECT COUNT(*) FROM history WHERE author_id = ? AND op = 'CREATE'", actor);
        assertThat(write.map(ErrorCode::token).orElse("written") + ":" + written)
                .isIn("written:1", "ATTESTATION_SIGNATURE_INVALID:0");
    }

    @Test
    @DisplayName("SVC-RACE-08: a code added while saves use it: each save sees the old or the new set, nothing else")
    void race08_codeSetVersusSave(Scenario s) {
        String category = "RACE_" + s.code();
        List<Callable<Object>> calls = new ArrayList<>();
        calls.add(() -> s.bean(CommonCodeService.class).set(new CodeCommand("ISSUE_CATEGORY", category, "경쟁",
                "Race", null, null, 80, null), TestOwners.OWNER));
        for (int i = 0; i < 20; i++) {
            calls.add(() -> {
                var fields = s.issueFields("race");
                fields.put("category", category);
                return s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("issue", null, fields));
            });
        }
        List<Outcome<Object>> outcomes = Parallel.all(calls, 21);
        assertThat(codes(outcomes)).allSatisfy(c -> assertThat(c).isIn(Optional.empty(),
                Optional.of(ValidationCode.CODE_INVALID)));
        assertThat(outcomes.get(0).failure()).isNull();
    }

    @Test
    @DisplayName("SVC-GRT-06 (principle 4.4): two grants of the same key at the same time leave exactly one live grant")
    void grt06_parallelGrants(Scenario s) {
        assertThat(((org.springframework.aop.framework.Advised) s.bean(GrantService.class)).getAdvisors())
                .extracting(advisor -> advisor.getAdvice().getClass().getName())
                .noneMatch(name -> name.contains("Retry"));
        String author = "claude:grt06";
        s.ctx().enroll(author);
        List<Callable<Object>> calls = IntStream.range(0, 2).<Callable<Object>>mapToObj(i -> () -> s.bean(
                GrantService.class).grant(new GrantCommand(author, s.projectId(), List.of("issue"), null, "n" + i),
                        TestOwners.OWNER))
                .toList();
        List<Outcome<Object>> outcomes = Parallel.all(calls);
        assertThat(codes(outcomes)).allSatisfy(c -> assertThat(c).isIn(Optional.empty(),
                Optional.of(ConflictCode.DUPLICATE_KEY)));
        long succeeded = outcomes.stream().filter(Outcome::succeeded).count();
        assertThat(succeeded).as("at least the call that won").isPositive();
        assertThat(s.count("SELECT COUNT(*) FROM session_grant WHERE author_id = ? AND revoked_at IS NULL",
                author)).isEqualTo(1);
        assertThat(s.count("SELECT COUNT(*) FROM session_grant WHERE author_id = ?", author)).isEqualTo(succeeded);
    }

    @Test
    @DisplayName("REM-001 (D-0050): linking a free-text task across a concurrent uncommitted move to another project re-authorizes after the move and fails SESSION_NOT_GRANTED")
    void rem001_linkWaitsAndRechecksMovedProject(Scenario s) throws Exception {
        String p2 = s.otherProject();
        String mover = s.actorWith("issue", "task");
        s.grant(mover, p2, "issue", "task");
        String p1Only = s.actorWith("issue", "task");
        SaveResult i = s.newIssue("i1");
        SaveResult t = s.bean(SaveService.class).save(mover,
                s.create("task", "FREE-R1-" + Scenario.uuid().substring(0, 8), s.taskFields("t1")));
        SaveResult c = s.newCriterion("c1", "r", "code");
        CountDownLatch moved = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger holderPid = new AtomicInteger();
        Future<?> saving = holdingTransaction(s, () -> {
            holderPid.set(s.ctx().jdbc().sql("SELECT pg_backend_pid()").query(Integer.class).single());
            return s.bean(SaveService.class).save(mover, s.edit("task", t.id(), 1, Map.of("project_id", p2)));
        }, moved, release);
        assertThat(moved.await(60, TimeUnit.SECONDS)).isTrue();
        Future<Optional<ErrorCode>> linking = Parallel.start(() -> Expect.outcome(() -> s.bean(LinkService.class)
                .link(p1Only, new LinkCommand(Scenario.uuid(), "issue_task", i.id(), t.id(), Scenario.REASON, null))));
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(15))
                .pollInterval(java.time.Duration.ofMillis(10))
                .until(() -> linking.isDone()
                        || LockWaits.waitingFor(s.ctx().jdbc(), holderPid.get()) > 0);
        release.countDown();
        saving.get(60, TimeUnit.SECONDS);
        assertThat(linking.get(60, TimeUnit.SECONDS)).contains(TrustCode.SESSION_NOT_GRANTED);
        assertThat(s.count("SELECT COUNT(*) FROM issue_task WHERE issue_id = ? AND task_id = ?", i.id(), t.id()))
                .isZero();
        Expect.failure(() -> s.bean(LinkService.class).link(p1Only, new LinkCommand(Scenario.uuid(), "criterion_task",
                c.id(), t.id(), Scenario.REASON, null)), TrustCode.SESSION_NOT_GRANTED);
        assertThat(s.count("SELECT COUNT(*) FROM criterion_link WHERE criterion_id = ?", c.id())).isZero();
    }

    @RepeatedTest(5)
    @DisplayName("REM-005 (D-0024 / BLOCKING-L2): concurrent mutual merges A->B and B->A never both succeed and never create a cycle")
    void rem005_mutualMergeSerializesWithoutCycle(Scenario s) {
        SaveResult a = s.newIssue("cycle-a");
        SaveResult b = s.newIssue("cycle-b");
        var outcomes = Parallel.all(List.of(
                () -> MergeFixture.merge(s, a.id(), 1, b.id()),
                () -> MergeFixture.merge(s, b.id(), 1, a.id())));
        assertThat(outcomes.stream().filter(Outcome::succeeded)).hasSizeLessThan(2);
        for (var outcome : outcomes) {
            if (!outcome.succeeded()) {
                assertThat(outcome.failure()).isInstanceOf(LedgerException.class);
                assertThat(((LedgerException) outcome.failure()).code()).isIn(
                        TransitionCode.MERGE_TARGET_INVALID, ConflictCode.VERSION_CONFLICT);
            }
        }
        var rowA = s.issue(a.id());
        var rowB = s.issue(b.id());
        assertThat(rowA.status() == IssueStatus.MERGED && rowB.status() == IssueStatus.MERGED).isFalse();
    }

    @Test
    @DisplayName("REM-005 (D-0024 / BLOCKING-L2): an uncommitted A->B merge holds B so a concurrent B->A waits and fails MERGE_TARGET_INVALID")
    void rem005_uncommittedMutualMergeIsSerialized(Scenario s) throws Exception {
        SaveResult a = s.newIssue("skew-a");
        SaveResult b = s.newIssue("skew-b");
        CountDownLatch merged = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger holderPid = new AtomicInteger();
        Future<?> first = holdingTransaction(s, () -> {
            holderPid.set(s.ctx().jdbc().sql("SELECT pg_backend_pid()").query(Integer.class).single());
            return MergeFixture.merge(s, a.id(), 1, b.id());
        }, merged, release);
        assertThat(merged.await(60, TimeUnit.SECONDS)).isTrue();

        Future<Optional<ErrorCode>> second = Parallel.start(() -> Expect.outcome(() ->
                MergeFixture.merge(s, b.id(), 1, a.id())));
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(15))
                .pollInterval(java.time.Duration.ofMillis(10))
                .until(() -> second.isDone()
                        || LockWaits.waitingFor(s.ctx().jdbc(), holderPid.get()) > 0);
        release.countDown();
        first.get(60, TimeUnit.SECONDS);
        assertThat(second.get(60, TimeUnit.SECONDS)).contains(TransitionCode.MERGE_TARGET_INVALID);
        assertThat(s.issue(a.id()).status()).isEqualTo(IssueStatus.MERGED);
        assertThat(s.issue(b.id()).status()).isEqualTo(IssueStatus.OPEN);
        assertThat(s.issue(b.id()).mergedInto()).isNull();
    }

    @Test
    @DisplayName("REM-005 (D-0024 / BLOCKING-L2): merging A into B across a concurrent B->VERIFIED transition cannot leave A merged into a VERIFIED issue without B seeing A")
    void rem005_mergeIntoConcurrentVerify(Scenario s) throws Exception {
        SaveResult a = s.newIssue("src");
        s.bean(RecordCaseService.class).recordCase(Scenario.AUTHOR, new RecordCaseCommand(Scenario.uuid(), a.id(),
                "case on src", Scenario.REASON, null, null, null));
        SaveResult b = s.resolvedIssue("target");
        String reviewer = s.actorWith("verify");

        CountDownLatch pinned = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger holderPid = new AtomicInteger();
        Future<?> verifying = holdingTransaction(s, () -> {
            holderPid.set(s.ctx().jdbc().sql("SELECT pg_backend_pid()").query(Integer.class).single());
            return s.bean(ReviewService.class).review(reviewer, s.move("issue", b.id(), 2L, "VERIFIED",
                    Scenario.EVIDENCE));
        }, pinned, release);
        assertThat(pinned.await(60, TimeUnit.SECONDS)).isTrue();

        Future<Optional<ErrorCode>> merging = Parallel.start(() -> Expect.outcome(() ->
                MergeFixture.merge(s, a.id(), 1, b.id())));
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(15))
                .pollInterval(java.time.Duration.ofMillis(10))
                .until(() -> merging.isDone()
                        || LockWaits.waitingFor(s.ctx().jdbc(), holderPid.get()) > 0);
        release.countDown();
        verifying.get(60, TimeUnit.SECONDS);
        assertThat(merging.get(60, TimeUnit.SECONDS)).contains(TransitionCode.MERGE_TARGET_INVALID);
        assertThat(s.issue(a.id()).status()).isEqualTo(IssueStatus.OPEN);
    }

    @Test
    @DisplayName("REM-009: criterion_task pins task before milestone_criterion so concurrent task move blocks without AB-BA deadlock")
    void rem009_criterionTaskPinsTaskBeforeCriterionConcurrentWithMove(Scenario s) throws Exception {
        String p2 = s.otherProject();
        String mover = s.actorWith("task");
        s.grant(mover, p2, "task");
        String p1Only = s.actorWith("task");
        SaveResult c = s.newCriterion("c1", "r1", "code1");
        SaveResult t = s.bean(SaveService.class).save(mover,
                s.create("task", "FREE-R9-" + Scenario.uuid().substring(0, 8), s.taskFields("t1")));

        CountDownLatch moved = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger holderPid = new AtomicInteger();
        Future<?> saving = holdingTransaction(s, () -> {
            holderPid.set(s.ctx().jdbc().sql("SELECT pg_backend_pid()").query(Integer.class).single());
            return s.bean(SaveService.class).save(mover, s.edit("task", t.id(), 1, Map.of("project_id", p2)));
        }, moved, release);
        assertThat(moved.await(60, TimeUnit.SECONDS)).isTrue();

        Future<Optional<ErrorCode>> linking = Parallel.start(() -> Expect.outcome(() -> s.bean(LinkService.class)
                .link(p1Only, new LinkCommand(Scenario.uuid(), "criterion_task", c.id(), t.id(), Scenario.REASON, null))));
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(15))
                .pollInterval(java.time.Duration.ofMillis(10))
                .until(() -> linking.isDone()
                        || LockWaits.waitingFor(s.ctx().jdbc(), holderPid.get()) > 0);
        release.countDown();
        saving.get(60, TimeUnit.SECONDS);
        assertThat(linking.get(60, TimeUnit.SECONDS)).contains(TrustCode.SESSION_NOT_GRANTED);
        assertThat(s.count("SELECT COUNT(*) FROM criterion_link WHERE criterion_id = ?", c.id())).isZero();
    }

    @Test
    @DisplayName("R2-BLOCKING-3: record_deliverable on a spec or task across a concurrent uncommitted move to another project re-authorizes after the move and fails SESSION_NOT_GRANTED")
    void r2Blocking3_recordDeliverableWaitsAndRechecksMovedProject(Scenario s) throws Exception {
        String p2 = s.otherProject();
        String mover = s.actorWith("spec", "task");
        s.grant(mover, p2, "spec", "task");
        String p1Only = s.actorWith("deliverable", "spec", "task");
        SaveResult spec = s.bean(SaveService.class).save(mover, s.create("spec",
                "FREE-SP-" + Scenario.uuid().substring(0, 8),
                Map.of("project_id", s.projectId(), "title", "movable spec")));
        SaveResult task = s.bean(SaveService.class).save(mover, s.create("task",
                "FREE-TK-" + Scenario.uuid().substring(0, 8), s.taskFields("movable task")));

        CountDownLatch specMoved = new CountDownLatch(1);
        CountDownLatch specRelease = new CountDownLatch(1);
        AtomicInteger specHolderPid = new AtomicInteger();
        Future<?> movingSpec = holdingTransaction(s, () -> {
            specHolderPid.set(s.ctx().jdbc().sql("SELECT pg_backend_pid()").query(Integer.class).single());
            return s.bean(SaveService.class).save(mover, s.edit("spec", spec.id(), 1, Map.of("project_id", p2)));
        }, specMoved, specRelease);
        assertThat(specMoved.await(60, TimeUnit.SECONDS)).isTrue();
        Future<Optional<ErrorCode>> deliveringSpec = Parallel.start(() -> Expect.outcome(() -> s
                .bean(RecordDeliverableService.class).recordDeliverable(p1Only, new RecordDeliverableCommand(
                        Scenario.uuid(), "spec", spec.id(), "code_change", Scenario.ref('s'), Scenario.REASON,
                        "repo/e2e", "summary", null, null, null, true))));
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(15))
                .pollInterval(java.time.Duration.ofMillis(10))
                .until(() -> deliveringSpec.isDone()
                        || LockWaits.waitingFor(s.ctx().jdbc(), specHolderPid.get()) > 0);
        specRelease.countDown();
        movingSpec.get(60, TimeUnit.SECONDS);
        assertThat(deliveringSpec.get(60, TimeUnit.SECONDS)).contains(TrustCode.SESSION_NOT_GRANTED);
        assertThat(s.count("SELECT COUNT(*) FROM deliverable WHERE target_entity = 'SPEC' AND target_id = ?",
                spec.id())).isZero();

        CountDownLatch taskMoved = new CountDownLatch(1);
        CountDownLatch taskRelease = new CountDownLatch(1);
        AtomicInteger taskHolderPid = new AtomicInteger();
        Future<?> movingTask = holdingTransaction(s, () -> {
            taskHolderPid.set(s.ctx().jdbc().sql("SELECT pg_backend_pid()").query(Integer.class).single());
            return s.bean(SaveService.class).save(mover, s.edit("task", task.id(), 1, Map.of("project_id", p2)));
        }, taskMoved, taskRelease);
        assertThat(taskMoved.await(60, TimeUnit.SECONDS)).isTrue();
        Future<Optional<ErrorCode>> deliveringTask = Parallel.start(() -> Expect.outcome(() -> s
                .bean(RecordDeliverableService.class).recordDeliverable(p1Only, new RecordDeliverableCommand(
                        Scenario.uuid(), "task", task.id(), "code_change", Scenario.ref('t'), Scenario.REASON,
                        "repo/e2e", "summary", null, null, null, true))));
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(15))
                .pollInterval(java.time.Duration.ofMillis(10))
                .until(() -> deliveringTask.isDone()
                        || LockWaits.waitingFor(s.ctx().jdbc(), taskHolderPid.get()) > 0);
        taskRelease.countDown();
        movingTask.get(60, TimeUnit.SECONDS);
        assertThat(deliveringTask.get(60, TimeUnit.SECONDS)).contains(TrustCode.SESSION_NOT_GRANTED);
        assertThat(s.count("SELECT COUNT(*) FROM deliverable WHERE target_entity = 'TASK' AND target_id = ?",
                task.id())).isZero();
    }

    @Test
    @DisplayName("R3-F1: save edit on spec, task, or criterion across a concurrent uncommitted move re-authorizes after the move and fails SESSION_NOT_GRANTED")
    void r3F1_saveEditWaitsAndRechecksMovedProject(Scenario s) throws Exception {
        String p2 = s.otherProject();
        String mover = s.actorWith("spec", "task");
        s.grant(mover, p2, "spec", "task");
        String p1Only = s.actorWith("spec", "task");
        SaveResult spec = s.bean(SaveService.class).save(mover, s.create("spec",
                "FREE-SP-F1-" + Scenario.uuid().substring(0, 8),
                Map.of("project_id", s.projectId(), "title", "movable spec")));
        SaveResult task = s.bean(SaveService.class).save(mover, s.create("task",
                "FREE-TK-F1-" + Scenario.uuid().substring(0, 8), s.taskFields("movable task")));
        SaveResult criterion = s.newCriterion("FREE-CR-F1-" + Scenario.uuid().substring(0, 8), "m1", "c1");

        // Spec move vs edit
        CountDownLatch specMoved = new CountDownLatch(1);
        CountDownLatch specRelease = new CountDownLatch(1);
        AtomicInteger specHolderPid = new AtomicInteger();
        Future<?> movingSpec = holdingTransaction(s, () -> {
            specHolderPid.set(s.ctx().jdbc().sql("SELECT pg_backend_pid()").query(Integer.class).single());
            return s.bean(SaveService.class).save(mover, s.edit("spec", spec.id(), 1, Map.of("project_id", p2)));
        }, specMoved, specRelease);
        assertThat(specMoved.await(60, TimeUnit.SECONDS)).isTrue();
        Future<Optional<ErrorCode>> editingSpec = Parallel.start(() -> Expect.outcome(() -> s.bean(SaveService.class)
                .save(p1Only, s.edit("spec", spec.id(), 1, Map.of("title", "unauthorized edit")))));
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(15))
                .pollInterval(java.time.Duration.ofMillis(10))
                .until(() -> editingSpec.isDone()
                        || LockWaits.waitingFor(s.ctx().jdbc(), specHolderPid.get()) > 0);
        specRelease.countDown();
        movingSpec.get(60, TimeUnit.SECONDS);
        assertThat(editingSpec.get(60, TimeUnit.SECONDS)).contains(TrustCode.SESSION_NOT_GRANTED);
        assertThat(s.spec(spec.id()).title()).isEqualTo("movable spec");

        // Task move vs edit
        CountDownLatch taskMoved = new CountDownLatch(1);
        CountDownLatch taskRelease = new CountDownLatch(1);
        AtomicInteger taskHolderPid = new AtomicInteger();
        Future<?> movingTask = holdingTransaction(s, () -> {
            taskHolderPid.set(s.ctx().jdbc().sql("SELECT pg_backend_pid()").query(Integer.class).single());
            return s.bean(SaveService.class).save(mover, s.edit("task", task.id(), 1, Map.of("project_id", p2)));
        }, taskMoved, taskRelease);
        assertThat(taskMoved.await(60, TimeUnit.SECONDS)).isTrue();
        Future<Optional<ErrorCode>> editingTask = Parallel.start(() -> Expect.outcome(() -> s.bean(SaveService.class)
                .save(p1Only, s.edit("task", task.id(), 1, Map.of("title", "unauthorized edit")))));
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(15))
                .pollInterval(java.time.Duration.ofMillis(10))
                .until(() -> editingTask.isDone()
                        || LockWaits.waitingFor(s.ctx().jdbc(), taskHolderPid.get()) > 0);
        taskRelease.countDown();
        movingTask.get(60, TimeUnit.SECONDS);
        assertThat(editingTask.get(60, TimeUnit.SECONDS)).contains(TrustCode.SESSION_NOT_GRANTED);
        assertThat(s.task(task.id()).title()).isEqualTo("movable task");

        // Criterion move vs edit
        CountDownLatch critMoved = new CountDownLatch(1);
        CountDownLatch critRelease = new CountDownLatch(1);
        AtomicInteger critHolderPid = new AtomicInteger();
        Future<?> movingCrit = holdingTransaction(s, () -> {
            critHolderPid.set(s.ctx().jdbc().sql("SELECT pg_backend_pid()").query(Integer.class).single());
            return s.bean(SaveService.class).save(mover, s.edit("criterion", criterion.id(), 1, Map.of("project_id", p2)));
        }, critMoved, critRelease);
        assertThat(critMoved.await(60, TimeUnit.SECONDS)).isTrue();
        Future<Optional<ErrorCode>> editingCrit = Parallel.start(() -> Expect.outcome(() -> s.bean(SaveService.class)
                .save(p1Only, s.edit("criterion", criterion.id(), 1, Map.of("criterion_text", "unauthorized edit")))));
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(15))
                .pollInterval(java.time.Duration.ofMillis(10))
                .until(() -> editingCrit.isDone()
                        || LockWaits.waitingFor(s.ctx().jdbc(), critHolderPid.get()) > 0);
        critRelease.countDown();
        movingCrit.get(60, TimeUnit.SECONDS);
        assertThat(editingCrit.get(60, TimeUnit.SECONDS)).contains(TrustCode.SESSION_NOT_GRANTED);
        assertThat(s.criterion(criterion.id()).criterionText()).isNotEqualTo("unauthorized edit");
    }

    @Test
    @DisplayName("R4-F1 (a): concurrent 2-node spec cycle A->B vs B->A serializes on project lock and never commits a cycle")
    void r4F1_concurrentTwoNodeSpecCycleIsPrevented(Scenario s) throws Exception {
        // Deterministic held transaction: Tx1 sets A.parent = B (uncommitted), Tx2 sets B.parent = A -> waits and fails VALIDATION_FAILED
        SaveResult aHeld = s.newSpec("held-a");
        SaveResult bHeld = s.newSpec("held-b");
        CountDownLatch updated = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger holderPid = new AtomicInteger();
        Future<?> tx1 = holdingTransaction(s, () -> {
            holderPid.set(LockWaits.backendPid(s.ctx().jdbc()));
            return s.bean(SaveService.class).save(Scenario.AUTHOR,
                    s.edit("spec", aHeld.id(), 1, Map.of("parent_id", bHeld.id())));
        }, updated, release);
        assertThat(updated.await(60, TimeUnit.SECONDS)).isTrue();
        Future<Optional<ErrorCode>> tx2 = Parallel.start(() -> Expect.outcome(() -> s.bean(SaveService.class)
                .save(Scenario.AUTHOR, s.edit("spec", bHeld.id(), 1, Map.of("parent_id", aHeld.id())))));
        LockWaits.awaitWaitingFor(s.ctx().jdbc(), holderPid.get());
        release.countDown();
        tx1.get(60, TimeUnit.SECONDS);
        assertThat(tx2.get(60, TimeUnit.SECONDS)).contains(ValidationCode.VALIDATION_FAILED);
        assertThat(s.spec(aHeld.id()).parentId().value()).isEqualTo(bHeld.id());
        assertThat(s.spec(bHeld.id()).parentId()).isNull();

        // Barrier rounds: simultaneous A->B and B->A never both commit
        for (int round = 0; round < 10; round++) {
            SaveResult a = s.newSpec("r2-a-" + round);
            SaveResult b = s.newSpec("r2-b-" + round);
            List<Outcome<WriteResult>> outcomes = Parallel.all(List.of(
                    () -> s.bean(SaveService.class).save(Scenario.AUTHOR,
                            s.edit("spec", a.id(), 1, Map.of("parent_id", b.id()))),
                    () -> s.bean(SaveService.class).save(Scenario.AUTHOR,
                            s.edit("spec", b.id(), 1, Map.of("parent_id", a.id())))));
            assertThat(outcomes.stream().filter(Outcome::succeeded)).hasSize(1);
            assertThat(codes(outcomes)).containsExactlyInAnyOrder(Optional.empty(),
                    Optional.of(ValidationCode.VALIDATION_FAILED));
            var rowA = s.spec(a.id());
            var rowB = s.spec(b.id());
            assertThat(rowA.parentId() != null && rowB.parentId() != null).isFalse();
        }
    }

    @Test
    @DisplayName("R4-F1 (b): concurrent 3-node spec cycle A->B, B->C, C->A never commits a cycle")
    void r4F1_concurrentThreeNodeSpecCycleIsPrevented(Scenario s) {
        for (int round = 0; round < 10; round++) {
            SaveResult a = s.newSpec("r3-a-" + round);
            SaveResult b = s.newSpec("r3-b-" + round);
            SaveResult c = s.newSpec("r3-c-" + round);
            List<Outcome<WriteResult>> outcomes = Parallel.all(List.of(
                    () -> s.bean(SaveService.class).save(Scenario.AUTHOR,
                            s.edit("spec", a.id(), 1, Map.of("parent_id", b.id()))),
                    () -> s.bean(SaveService.class).save(Scenario.AUTHOR,
                            s.edit("spec", b.id(), 1, Map.of("parent_id", c.id()))),
                    () -> s.bean(SaveService.class).save(Scenario.AUTHOR,
                            s.edit("spec", c.id(), 1, Map.of("parent_id", a.id())))), 3);
            assertThat(outcomes.stream().filter(Outcome::succeeded)).hasSize(2);
            assertThat(codes(outcomes)).containsExactlyInAnyOrder(Optional.empty(), Optional.empty(),
                    Optional.of(ValidationCode.VALIDATION_FAILED));
            long withParent = List.of(s.spec(a.id()), s.spec(b.id()), s.spec(c.id())).stream()
                    .filter(sp -> sp.parentId() != null).count();
            assertThat(withParent).as("at least one node remains a root (no 3-node cycle)").isEqualTo(2);
        }
    }

    @Test
    @DisplayName("R4-F1 (c): concurrent spec project move A(P1->P2) and attaching child B(P1, parent=A) serializes without cross-project parent")
    void r4F1_concurrentSpecMoveAndAttachChildIsSerialized(Scenario s) throws Exception {
        String p2 = s.otherProject();
        String mover = s.actorWith("spec");
        s.grant(mover, p2, "spec");

        // Case 1: move A(P1->P2) held uncommitted while Tx2 creates child B(P1, parent=A) and Tx3 edits C(P1, parent=A)
        SaveResult a1 = s.bean(SaveService.class).save(mover, s.create("spec",
                "FREE-M1-" + Scenario.uuid().substring(0, 8),
                Map.of("project_id", s.projectId(), "title", "movable parent")));
        SaveResult c1 = s.newSpec("existing-c1");
        CountDownLatch moved = new CountDownLatch(1);
        CountDownLatch releaseMove = new CountDownLatch(1);
        AtomicInteger movePid = new AtomicInteger();
        Future<?> moving = holdingTransaction(s, () -> {
            movePid.set(LockWaits.backendPid(s.ctx().jdbc()));
            return s.bean(SaveService.class).save(mover, s.edit("spec", a1.id(), 1, Map.of("project_id", p2)));
        }, moved, releaseMove);
        assertThat(moved.await(60, TimeUnit.SECONDS)).isTrue();

        Future<Optional<ErrorCode>> creatingChild = Parallel.start(() -> Expect.outcome(() -> s.bean(SaveService.class)
                .save(Scenario.AUTHOR, s.create("spec", "FREE-CH-" + Scenario.uuid().substring(0, 8),
                        Map.of("project_id", s.projectId(), "title", "child", "parent_id", a1.id())))));
        Future<Optional<ErrorCode>> reparenting = Parallel.start(() -> Expect.outcome(() -> s.bean(SaveService.class)
                .save(Scenario.AUTHOR, s.edit("spec", c1.id(), 1, Map.of("parent_id", a1.id())))));
        LockWaits.awaitWaitingFor(s.ctx().jdbc(), movePid.get());
        releaseMove.countDown();
        moving.get(60, TimeUnit.SECONDS);
        assertThat(creatingChild.get(60, TimeUnit.SECONDS)).contains(ValidationCode.VALIDATION_FAILED);
        assertThat(reparenting.get(60, TimeUnit.SECONDS)).contains(ValidationCode.VALIDATION_FAILED);
        assertThat(s.spec(a1.id()).projectId().value()).isEqualTo(p2);
        assertThat(s.spec(c1.id()).parentId()).isNull();
        assertThat(s.count("SELECT COUNT(*) FROM spec WHERE parent_id = ?", a1.id())).isZero();

        // Case 2: attach child held uncommitted while Tx2 attempts to move A(P1->P2) -> move waits and fails PROJECT_MOVE_FORBIDDEN
        SaveResult a2 = s.bean(SaveService.class).save(mover, s.create("spec",
                "FREE-M2-" + Scenario.uuid().substring(0, 8),
                Map.of("project_id", s.projectId(), "title", "movable parent 2")));
        CountDownLatch attached = new CountDownLatch(1);
        CountDownLatch releaseAttach = new CountDownLatch(1);
        AtomicInteger attachPid = new AtomicInteger();
        Future<?> attaching = holdingTransaction(s, () -> {
            attachPid.set(LockWaits.backendPid(s.ctx().jdbc()));
            return s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("spec",
                    "FREE-CH2-" + Scenario.uuid().substring(0, 8),
                    Map.of("project_id", s.projectId(), "title", "child 2", "parent_id", a2.id())));
        }, attached, releaseAttach);
        assertThat(attached.await(60, TimeUnit.SECONDS)).isTrue();

        Future<Optional<ErrorCode>> movingAfterAttach = Parallel.start(() -> Expect.outcome(() -> s
                .bean(SaveService.class).save(mover, s.edit("spec", a2.id(), 1, Map.of("project_id", p2)))));
        LockWaits.awaitWaitingFor(s.ctx().jdbc(), attachPid.get());
        releaseAttach.countDown();
        attaching.get(60, TimeUnit.SECONDS);
        assertThat(movingAfterAttach.get(60, TimeUnit.SECONDS)).contains(TransitionCode.PROJECT_MOVE_FORBIDDEN);
        assertThat(s.spec(a2.id()).projectId().value()).isEqualTo(s.projectId());
    }

    @Test
    @DisplayName("R5-F1: same-author concurrent move (high->low project) and edit/link/deliverable/correct never deadlock on session_grant")
    void r5F1_sameAuthorConcurrentMoveAndWriteBetweenTwoProjectsNeverDeadlocks(Scenario s) {
        String p1 = s.projectId();
        String p2 = s.otherProject();
        String low = p1.compareTo(p2) <= 0 ? p1 : p2;
        String high = p1.compareTo(p2) <= 0 ? p2 : p1;
        String sameAuthor = s.actorWith("issue", "spec", "task", "deliverable");
        s.grant(sameAuthor, p2, "issue", "spec", "task", "deliverable");

        SaveService save = s.bean(SaveService.class);
        LinkService link = s.bean(LinkService.class);
        RecordDeliverableService deliverables = s.bean(RecordDeliverableService.class);
        CorrectService correct = s.bean(CorrectService.class);

        for (int round = 0; round < 8; round++) {
            int idx = round;
            SaveResult spec = save.save(sameAuthor, s.create("spec",
                    "FREE-R5-SP-" + idx + "-" + Scenario.uuid().substring(0, 6),
                    Map.of("project_id", high, "title", "spec-" + idx)));
            SaveResult task = save.save(sameAuthor, s.create("task",
                    "FREE-R5-TK-" + idx + "-" + Scenario.uuid().substring(0, 6),
                    Map.of("project_id", high, "title", "task-" + idx, "purpose", "p", "success_condition", "sc")));

            List<Outcome<WriteResult>> outcomes = Parallel.all(List.of(
                    // Moves spec high -> low (touches grants on high & low)
                    () -> save.save(sameAuthor, s.edit("spec", spec.id(), 1, Map.of("project_id", low))),
                    // Concurrent same-author edit on spec currently in high
                    () -> save.save(sameAuthor, s.edit("spec", spec.id(), 1, Map.of("title", "edited-" + idx))),
                    // Concurrent same-author link spec -> task
                    () -> link.link(sameAuthor, new LinkCommand(Scenario.uuid(), "spec_task", spec.id(), task.id(),
                            Scenario.REASON, null)),
                    // Concurrent same-author deliverable on spec
                    () -> deliverables.recordDeliverable(sameAuthor, new RecordDeliverableCommand(
                            Scenario.uuid(), "spec", spec.id(), "code_change", Scenario.ref('r'), Scenario.REASON,
                            "repo/r5", "summary", null, null, null, false)),
                    // Concurrent same-author correct on spec create history
                    () -> correct.correct(sameAuthor, new CorrectCommand(Scenario.uuid(), spec.historyId(),
                            Scenario.REASON, null)),
                    // Moves task high -> low concurrently with link
                    () -> save.save(sameAuthor, s.edit("task", task.id(), 1, Map.of("project_id", low)))), 6);

            for (Outcome<WriteResult> outcome : outcomes) {
                if (!outcome.succeeded()) {
                    assertThat(outcome.failure())
                            .as("round %d must never fail with DB deadlock (40P01)", idx)
                            .isInstanceOf(LedgerException.class);
                    ErrorCode code = ((LedgerException) outcome.failure()).code();
                    assertThat(code).isIn(ConflictCode.VERSION_CONFLICT, TransitionCode.NOTHING_CHANGES,
                            ConflictCode.CONCURRENT_MODIFICATION);
                }
            }
        }
    }

    @Test
    @DisplayName("R6-F1: multi-op batch (reverse project/entity/counter order) concurrent with single writes and grant revocation never deadlocks")
    void r6F1_batchConcurrentWithSingleWritesAndGrantRevocationNeverDeadlocks(Scenario s) {
        String p1 = s.projectId();
        String p2 = s.otherProject();
        String low = p1.compareTo(p2) <= 0 ? p1 : p2;
        String high = p1.compareTo(p2) <= 0 ? p2 : p1;

        String batchAuthor = s.actorWith("issue", "spec", "task", "deliverable");
        s.grant(batchAuthor, p2, "issue", "spec", "task", "deliverable");
        String peerAuthor = s.actorWith("issue", "spec", "task", "deliverable");
        s.grant(peerAuthor, p2, "issue", "spec", "task", "deliverable");

        BatchService batch = s.bean(BatchService.class);
        SaveService save = s.bean(SaveService.class);
        LinkService link = s.bean(LinkService.class);
        RecordDeliverableService deliverables = s.bean(RecordDeliverableService.class);
        RecordCaseService cases = s.bean(RecordCaseService.class);
        GrantService grants = s.bean(GrantService.class);

        for (int round = 0; round < 8; round++) {
            int idx = round;
            String revocableAuthor = s.actorWith("issue", "spec", "task", "deliverable");
            s.grant(revocableAuthor, p2, "issue", "spec", "task", "deliverable");

            Map<String, String> hiFields = s.issueFields("r6-hi-" + idx);
            hiFields.put("project_id", high);
            Map<String, String> loFields = s.issueFields("r6-lo-" + idx);
            loFields.put("project_id", low);
            SaveResult issueHigh = save.save(batchAuthor, s.create("issue", null, hiFields));
            SaveResult issueLow = save.save(batchAuthor, s.create("issue", null, loFields));
            SaveResult specHigh = save.save(batchAuthor, s.create("spec",
                    "FREE-R6-SP-" + idx + "-" + Scenario.uuid().substring(0, 6),
                    Map.of("project_id", high, "title", "spec-" + idx)));
            SaveResult taskLow = save.save(batchAuthor, s.create("task",
                    "FREE-R6-TK-" + idx + "-" + Scenario.uuid().substring(0, 6),
                    Map.of("project_id", low, "title", "task-" + idx, "purpose", "p", "success_condition", "sc")));

            // Batch intentionally lists ops in reverse entity (task -> spec -> issue), reverse project (high -> low),
            // and reverse counter (record_case -> record_deliverable) order to prove BatchLockPlanner + BatchBuffer
            // enforce the global canonical DAG.
            List<BatchOpInput> batchOps = List.of(
                    new BatchOpInput.Save("task", Fields.of("title", "bt-" + idx), Scenario.REASON, taskLow.id(), 1L),
                    new BatchOpInput.Save("spec", Fields.of("title", "bs-" + idx), Scenario.REASON, specHigh.id(), 1L),
                    new BatchOpInput.RecordCase(issueHigh.id(), "batch-case-" + idx, Scenario.REASON, null, null, null,
                            null),
                    new BatchOpInput.RecordDeliverable("issue", issueLow.id(), "code_change", Scenario.ref('a'),
                            Scenario.REASON, "repo/r6", "batch deliv", null, null, null, false, null),
                    new BatchOpInput.Link("spec_task", specHigh.id(), taskLow.id(), Scenario.REASON, false));

            List<BatchOpInput> revocableBatchOps = List.of(
                    new BatchOpInput.Save("spec", Fields.of("title", "rv-s-" + idx), Scenario.REASON, specHigh.id(),
                            1L),
                    new BatchOpInput.Save("task", Fields.of("title", "rv-t-" + idx), Scenario.REASON, taskLow.id(),
                            1L));

            List<Outcome<Object>> outcomes = Parallel.all(List.of(
                    () -> batch.batch(batchAuthor, Scenario.uuid(), batchOps),
                    () -> batch.batch(revocableAuthor, Scenario.uuid(), revocableBatchOps),
                    () -> {
                        grants.revoke(new RevokeCommand(revocableAuthor, null, null), TestOwners.OWNER);
                        return "revoked";
                    },
                    () -> link.link(peerAuthor, new LinkCommand(Scenario.uuid(), "spec_task", specHigh.id(),
                            taskLow.id(), Scenario.REASON, null)),
                    () -> deliverables.recordDeliverable(peerAuthor, new RecordDeliverableCommand(Scenario.uuid(),
                            "issue", issueHigh.id(), "code_change", Scenario.ref('b'), Scenario.REASON, "repo/peer",
                            "peer deliv", null, null, null, false)),
                    () -> cases.recordCase(peerAuthor, new RecordCaseCommand(Scenario.uuid(), issueLow.id(),
                            "peer-case-" + idx, Scenario.REASON, null, null, null, null))), 6);

            for (Outcome<Object> outcome : outcomes) {
                if (!outcome.succeeded()) {
                    assertThat(outcome.failure())
                            .as("round %d must never fail with DB deadlock (40P01) or raw exception", idx)
                            .isInstanceOf(LedgerException.class);
                    ErrorCode code = ((LedgerException) outcome.failure()).code();
                    assertThat(code).isIn(ConflictCode.VERSION_CONFLICT, ConflictCode.CONCURRENT_MODIFICATION,
                            TransitionCode.NOTHING_CHANGES, TrustCode.SESSION_NOT_GRANTED);
                }
            }
        }
    }

    @Test
    @DisplayName("R6-F2: concurrent spec project move past hierarchy pin returns sealed CONCURRENT_MODIFICATION before stale grant check")
    void r6F2_specHierarchyPinRaceReturnsSealedConcurrentModificationBeforeStaleGrantCheck(Scenario s) throws Exception {
        String p1 = s.projectId();
        String p2 = s.otherProject();
        String p3 = s.otherProject();
        // Order pLow < pMid < pHigh so moving spec from pMid -> pLow while another tx attempts hierarchy edit on pMid
        // exercises the exact !pinned branch (movedProject < maxPinned).
        List<String> sorted = List.of(p1, p2, p3).stream().sorted().toList();
        String pLow = sorted.get(0);
        String pMid = sorted.get(1);
        String pHigh = sorted.get(2);

        String mover = s.actorWith("spec");
        s.grant(mover, p2, "spec");
        s.grant(mover, p3, "spec");

        // Editor has spec grant on pMid and pHigh, intentionally NOT on pLow: if !pinned ran after gate.authorizeAll,
        // this would misreport SESSION_NOT_GRANTED on pLow (or raw IllegalStateException -> INTERNAL_ERROR).
        String editor = s.actorWith("task");
        s.grant(editor, pMid, "spec");
        s.grant(editor, pHigh, "spec");

        SaveService save = s.bean(SaveService.class);
        SaveResult spec = save.save(mover, s.create("spec",
                "FREE-R6F2-" + Scenario.uuid().substring(0, 8),
                Map.of("project_id", pMid, "title", "r6-f2-spec")));

        // Hold move pMid -> pLow uncommitted (locks project pLow then pMid, updates spec.project_id = pLow).
        CountDownLatch moved = new CountDownLatch(1);
        CountDownLatch releaseMove = new CountDownLatch(1);
        AtomicInteger movePid = new AtomicInteger();
        Future<?> moving = holdingTransaction(s, () -> {
            movePid.set(LockWaits.backendPid(s.ctx().jdbc()));
            return save.save(mover, s.edit("spec", spec.id(), 1, Map.of("project_id", pLow)));
        }, moved, releaseMove);
        assertThat(moved.await(60, TimeUnit.SECONDS)).isTrue();

        // Editor starts hierarchy edit pMid -> pHigh: sees committed project pMid, blocks trying to pin project pMid
        // until mover commits pMid -> pLow, then pinSpecInProject(spec, pMid) fails and movedProject=pLow < maxPinned=pHigh.
        Future<Optional<ErrorCode>> editorOutcome = Parallel.start(() -> Expect.outcome(() -> save.save(editor,
                s.edit("spec", spec.id(), 1, Map.of("project_id", pHigh)))));
        LockWaits.awaitWaitingFor(s.ctx().jdbc(), movePid.get());
        releaseMove.countDown();
        moving.get(60, TimeUnit.SECONDS);

        assertThat(editorOutcome.get(60, TimeUnit.SECONDS)).contains(ConflictCode.CONCURRENT_MODIFICATION);
        assertThat(s.spec(spec.id()).projectId().value()).isEqualTo(pLow);
    }

    @Test
    @DisplayName("R7-B1 (a): same-project {ISSUE, SPEC, TASK} + DELIVERABLE batch concurrent with GrantService.revoke never deadlocks")
    void r7B1_sameProjectDeliverableAndWriteScopesBatchVsRevokeNeverDeadlocks(Scenario s) {
        String p = s.projectId();
        BatchService batch = s.bean(BatchService.class);
        SaveService save = s.bean(SaveService.class);
        GrantService grants = s.bean(GrantService.class);

        for (int round = 0; round < 12; round++) {
            int idx = round;
            String author = s.actorWith("issue", "spec", "task", "deliverable");
            SaveResult issue = save.save(author, s.create("issue", null, s.issueFields("r7b1-i-" + idx)));
            SaveResult spec = save.save(author, s.create("spec",
                    "FREE-R7B1-SP-" + idx + "-" + Scenario.uuid().substring(0, 6),
                    Map.of("project_id", p, "title", "r7b1-s-" + idx)));
            SaveResult task = save.save(author, s.create("task",
                    "FREE-R7B1-TK-" + idx + "-" + Scenario.uuid().substring(0, 6),
                    Map.of("project_id", p, "title", "r7b1-t-" + idx, "purpose", "p", "success_condition", "sc")));

            // Batch requires ISSUE, SPEC, TASK, and DELIVERABLE on the SAME project p.
            // Prior to Scope.LOCK_ORDER, WriteGate locked ISSUE->SPEC->TASK->DELIVERABLE (enum ordinal) while
            // GrantStateRepository.revokeLive locked DELIVERABLE->ISSUE->SPEC->TASK (SQL ORDER BY grant_scope).
            List<BatchOpInput> ops = List.of(
                    new BatchOpInput.Save("issue", Fields.of("title", "r7b1-i-ed-" + idx), Scenario.REASON, issue.id(),
                            1L),
                    new BatchOpInput.Save("spec", Fields.of("title", "r7b1-s-ed-" + idx), Scenario.REASON, spec.id(),
                            1L),
                    new BatchOpInput.Save("task", Fields.of("title", "r7b1-t-ed-" + idx), Scenario.REASON, task.id(),
                            1L),
                    new BatchOpInput.RecordDeliverable("issue", issue.id(), "code_change", Scenario.ref('d'),
                            Scenario.REASON, "repo/r7b1", "r7b1 deliv", null, null, null, false, null));

            List<Outcome<Object>> outcomes = Parallel.all(List.of(
                    () -> batch.batch(author, Scenario.uuid(), ops),
                    () -> {
                        grants.revoke(new RevokeCommand(author, p, null), TestOwners.OWNER);
                        return "revoked";
                    },
                    () -> grants.grant(new GrantCommand(author, p, List.of("task", "spec", "issue", "deliverable"),
                            null, "r7b1 re-grant"), TestOwners.OWNER)), 3);

            for (Outcome<Object> outcome : outcomes) {
                if (!outcome.succeeded()) {
                    assertThat(outcome.failure())
                            .as("round %d must never fail with DB deadlock (40P01) or raw exception", idx)
                            .isInstanceOf(LedgerException.class);
                    ErrorCode code = ((LedgerException) outcome.failure()).code();
                    assertThat(code).isIn(TrustCode.SESSION_NOT_GRANTED, ConflictCode.DUPLICATE_KEY,
                            ConflictCode.VERSION_CONFLICT, ConflictCode.CONCURRENT_MODIFICATION);
                }
            }
        }
    }

    @Test
    @DisplayName("R7-B1 (b): concurrent reverse-scope GrantService.grant calls on live grants never deadlock and preserve return order")
    void r7B1_concurrentReverseScopeGrantsNeverDeadlock(Scenario s) {
        String p = s.projectId();
        GrantService grants = s.bean(GrantService.class);

        for (int round = 0; round < 12; round++) {
            int idx = round;
            String author = s.actorWith("issue", "spec", "task", "deliverable");

            List<Outcome<List<Long>>> outcomes = Parallel.all(List.of(
                    () -> grants.grant(new GrantCommand(author, p, List.of("task", "spec", "issue", "deliverable"),
                            null, "rev-" + idx), TestOwners.OWNER),
                    () -> grants.grant(new GrantCommand(author, p, List.of("deliverable", "issue", "spec", "task"),
                            null, "fwd-" + idx), TestOwners.OWNER),
                    () -> grants.grant(new GrantCommand(author, p, List.of("task", "deliverable"),
                            null, "sub1-" + idx), TestOwners.OWNER),
                    () -> grants.grant(new GrantCommand(author, p, List.of("deliverable", "issue"),
                            null, "sub2-" + idx), TestOwners.OWNER)), 4);

            for (Outcome<List<Long>> outcome : outcomes) {
                if (!outcome.succeeded()) {
                    assertThat(outcome.failure())
                            .as("round %d must never fail with DB deadlock (40P01) or raw exception", idx)
                            .isInstanceOf(LedgerException.class);
                    ErrorCode code = ((LedgerException) outcome.failure()).code();
                    assertThat(code).isIn(ConflictCode.DUPLICATE_KEY, ConflictCode.CONCURRENT_MODIFICATION);
                }
            }

            // Verify caller-requested scope order is preserved in returned grant IDs
            List<Long> orderedIds = grants.grant(new GrantCommand(author, p, List.of("task", "deliverable", "issue"),
                    null, "order-" + idx), TestOwners.OWNER);
            assertThat(orderedIds).hasSize(3);
            assertThat(orderedIds.get(0)).isLessThan(orderedIds.get(1));
            assertThat(orderedIds.get(1)).isLessThan(orderedIds.get(2));
        }
    }
}
