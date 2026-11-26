// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.agentledger.database.repository.IssueRepository;
import org.agentledger.database.support.DatabaseTest;
import org.agentledger.database.support.LedgerTestContext;
import org.agentledger.database.support.Parallel;
import org.agentledger.database.support.Seed;
import org.agentledger.domain.model.Issue;
import org.agentledger.domain.model.Project;
import org.agentledger.domain.rule.IssueStatus;
import org.agentledger.testing.database.LockWaits;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * REPO-039, REPO-040: the number of the next case is one conditional UPDATE of the issue row, which a merge of
 * the same row is decided against.
 */
@DatabaseTest
class CaseSequenceTest {

    private LedgerTestContext ctx;
    private Seed seed;
    private IssueRepository issues;
    private JdbcClient jdbc;

    @BeforeEach
    void setUp(LedgerTestContext context) {
        ctx = context;
        seed = new Seed(ctx);
        issues = ctx.bean(IssueRepository.class);
        jdbc = JdbcClient.create(ctx.pool());
    }

    private long caseSeq(Issue issue) {
        return jdbc.sql("SELECT case_seq FROM issue WHERE id = :id").param("id", issue.id().value())
                .query(Long.class).single();
    }

    private Issue merged(Issue issue, Issue target) {
        return LedgerTestContext.as(Seed.ALICE, () -> ctx.tx(() -> issues.save(
                issues.findById(issue.id()).orElseThrow().transitioned(IssueStatus.MERGED, target.id(),
                        LedgerTestContext.START.plusSeconds(60)))));
    }

    @Test
    void repo039_nextCaseSeqWorksOnLiveIssuesAndLeavesVersionAndTimeAlone() {
        Project project = seed.project();
        Issue live = seed.issue(project, 1, "live", LedgerTestContext.START);
        Issue target = seed.issue(project, 2, "target", LedgerTestContext.START);
        Issue gone = merged(seed.issue(project, 3, "merged", LedgerTestContext.START), target);
        assertThat(ctx.tx(() -> issues.nextCaseSeq(live.id()))).isTrue();
        assertThat(ctx.tx(() -> issues.nextCaseSeq(live.id()))).isTrue();
        assertThat(ctx.tx(() -> issues.nextCaseSeq(gone.id()))).as("a merged issue takes no case").isFalse();
        assertThat(caseSeq(live)).isEqualTo(2);
        assertThat(caseSeq(gone)).isZero();
        Issue reread = issues.findById(live.id()).orElseThrow();
        assertThat(reread.version()).isEqualTo(1);
        assertThat(reread.updatedAt()).isEqualTo(live.updatedAt());
        assertThat(reread.caseSeq()).isEqualTo(2);
        LedgerTestContext.as(Seed.ALICE, () -> ctx.tx(() -> issues.save(live)));
        assertThat(caseSeq(live)).as("the read-only counter is not overwritten by a save").isEqualTo(2);
    }

    @Test
    void repo040_aMergeWaitsForTheCaseThatWroteTheRow() throws Exception {
        Project project = seed.project();
        Issue source = seed.issue(project, 1, "source", LedgerTestContext.START);
        Issue target = seed.issue(project, 2, "target", LedgerTestContext.START);
        TransactionTemplate tx = ctx.bean(TransactionTemplate.class);
        JdbcClient txJdbc = ctx.bean(JdbcClient.class);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger holderPid = new AtomicInteger();
        Future<Boolean> recorder = Parallel.start(() -> tx.execute(status -> {
            boolean ok = issues.nextCaseSeq(source.id());
            holderPid.set(LockWaits.backendPid(txJdbc));
            locked.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ok;
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        Future<Issue> merger = Parallel.start(() -> ScopedValue.where(org.agentledger.domain.context.ActorScope.CURRENT,
                Seed.ALICE).call(() -> tx.execute(status -> issues.save(source.transitioned(IssueStatus.MERGED,
                        target.id(), LedgerTestContext.START.plusSeconds(1))))));
        LockWaits.awaitWaitingFor(txJdbc, holderPid.get());
        release.countDown();
        assertThat(recorder.get(10, TimeUnit.SECONDS)).isTrue();
        Issue result = merger.get(10, TimeUnit.SECONDS);
        assertThat(result.status()).isEqualTo(IssueStatus.MERGED);
        assertThat(caseSeq(source)).as("the case's bump survives the merge").isEqualTo(1);
        assertThat(issues.findById(source.id()).orElseThrow().status()).isEqualTo(IssueStatus.MERGED);
        assertThat(ctx.tx(() -> issues.nextCaseSeq(source.id()))).isFalse();
    }
}
