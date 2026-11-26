// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.List;
import java.util.UUID;
import java.lang.reflect.Proxy;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.agentledger.database.repository.CodeRevisionRepository;
import org.agentledger.database.repository.Counter;
import org.agentledger.database.repository.HistoryRepository;
import org.agentledger.database.repository.IdAllocator;
import org.agentledger.database.repository.IssueRepository;
import org.agentledger.database.support.DatabaseTest;
import org.agentledger.database.support.LedgerTestContext;
import org.agentledger.database.support.Parallel;
import org.agentledger.database.support.Seed;
import org.agentledger.domain.error.ConflictCode;
import org.agentledger.domain.error.ConflictException;
import org.agentledger.domain.error.LookupCode;
import org.agentledger.domain.error.LookupException;
import org.agentledger.domain.model.HistoryEntry;
import org.agentledger.domain.model.Issue;
import org.agentledger.domain.model.Project;
import org.agentledger.domain.rule.ActorAssurance;
import org.agentledger.domain.rule.Channel;
import org.agentledger.domain.rule.HistoryEntity;
import org.agentledger.domain.rule.HistoryOp;
import org.agentledger.domain.value.JsonDocument;
import org.agentledger.domain.value.OperationKey;
import org.agentledger.domain.value.ProjectCode;
import org.springframework.jdbc.core.simple.JdbcClient;

/** REPO-009 .. REPO-014 and REPO-035: counters hand out unique numbers without gaps and without deadlocks. */
@DatabaseTest
class CounterTest {

    private LedgerTestContext ctx;
    private Seed seed;
    private IdAllocator ids;

    @BeforeEach
    void setUp(LedgerTestContext context) {
        ctx = context;
        seed = new Seed(ctx);
        ids = ctx.bean(IdAllocator.class);
    }

    private static List<Long> oneTo(int n) {
        return IntStream.rangeClosed(1, n).mapToObj(Long::valueOf).toList();
    }

    private static <T> List<Callable<T>> times(int n, Callable<T> work) {
        return Stream.generate(() -> work).limit(n).toList();
    }

    @Test
    void repo009_theNextNumberIsPreviousPlusOneAndAnUnknownProjectUpdatesNothing() {
        Project project = seed.project();
        assertThat(ctx.tx(() -> ids.nextIssueNo(project.code()))).isEqualTo(1);
        assertThat(ctx.tx(() -> ids.nextIssueNo(project.code()))).isEqualTo(2);
        assertThat(ctx.tx(() -> ids.nextSpecNo(project.code()))).isEqualTo(1);
        assertThat(ctx.tx(() -> ids.nextTaskNo(project.code()))).isEqualTo(1);
        assertThat(ctx.tx(() -> ids.takeIssueNo(project.code(), 3))).isEmpty();
        assertThat(ctx.tx(() -> ids.nextIssueNo(project.code()))).isEqualTo(4);
        assertThat(ctx.tx(() -> ids.takeSpecNo(project.code(), 2))).isEmpty();
        assertThat(ctx.tx(() -> ids.nextSpecNo(project.code()))).isEqualTo(3);
        LookupException unknown = catchThrowableOfType(LookupException.class,
                () -> ctx.tx(() -> ids.nextIssueNo(ProjectCode.of("NOSUCH"))));
        assertThat(unknown.code()).isEqualTo(LookupCode.NOT_FOUND);
    }

    private long issueCounter(Project project) {
        return JdbcClient.create(ctx.pool()).sql("SELECT next_issue_no FROM project WHERE code = :code")
                .param("code", project.code().value()).query(Long.class).single();
    }

    private long taskCounter(Project project) {
        return JdbcClient.create(ctx.pool()).sql("SELECT next_task_no FROM project WHERE code = :code")
                .param("code", project.code().value()).query(Long.class).single();
    }

    @Test
    void vrf020_anExplicitIssueNumberIsTakenUpToTheNextNumberOnly() {
        Project project = seed.project();
        ctx.tx(() -> ids.nextIssueNo(project.code()));
        ctx.tx(() -> ids.nextIssueNo(project.code()));
        assertThat(issueCounter(project)).isEqualTo(2);
        assertThat(ctx.tx(() -> ids.takeIssueNo(project.code(), 3))).isEmpty();
        assertThat(issueCounter(project)).isEqualTo(3);
        assertThat(ctx.tx(() -> ids.takeIssueNo(project.code(), 2))).isEmpty();
        assertThat(ctx.tx(() -> ids.takeIssueNo(project.code(), 1))).isEmpty();
        assertThat(issueCounter(project)).isEqualTo(3);
        assertThat(ctx.tx(() -> ids.takeIssueNo(project.code(), 5))).hasValue(4);
        assertThat(ctx.tx(() -> ids.takeIssueNo(project.code(), 999_999_999_999_999_999L))).hasValue(4);
        assertThat(issueCounter(project)).isEqualTo(3);
        assertThat(ctx.tx(() -> ids.takeIssueNo(project.code(), 4))).isEmpty();
        assertThat(issueCounter(project)).isEqualTo(4);
        assertThat(ctx.tx(() -> ids.nextIssueNo(project.code()))).isEqualTo(5);
        assertThat(taskCounter(project)).isZero();
        LookupException unknown = catchThrowableOfType(LookupException.class,
                () -> ctx.tx(() -> ids.takeIssueNo(ProjectCode.of("NOSUCH"), 1)));
        assertThat(unknown.code()).isEqualTo(LookupCode.NOT_FOUND);
    }

    @Test
    void vrf036_aNumberTakenAndNotCommittedIsNotWaitedFor() throws InterruptedException {
        Project project = seed.project();
        for (int i = 0; i < 3; i++) {
            ctx.tx(() -> ids.nextIssueNo(project.code()));
        }
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> ctx.tx(() -> {
            ids.nextIssueNo(project.code());
            holding.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }));
        holder.start();
        try {
            assertThat(holding.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(ctx.tx(() -> ids.takeIssueNo(project.code(), 5))).hasValue(4);
        } finally {
            release.countDown();
            holder.join();
        }
        assertThat(issueCounter(project)).isEqualTo(4);
        assertThat(ctx.tx(() -> ids.takeIssueNo(project.code(), 5))).isEmpty();
        assertThat(issueCounter(project)).isEqualTo(5);
    }

    /**
     * An allocator whose JdbcClient runs {@code afterUpdate} right after an UPDATE of the project counter, whatever it wrote:
     * the only way to put another transaction between the two statements of a take.
     */
    private IdAllocator allocatorWithHook(Runnable afterUpdate) {
        JdbcClient real = ctx.bean(JdbcClient.class);
        JdbcClient wrapped = (JdbcClient) Proxy.newProxyInstance(JdbcClient.class.getClassLoader(),
                new Class<?>[] {JdbcClient.class}, (proxy, method, args) -> {
                    Object result = invoke(method, real, args);
                    if ("sql".equals(method.getName()) && args != null && args[0] instanceof String sql
                            && result instanceof JdbcClient.StatementSpec spec) {
                        return hooked(spec, sql.startsWith("UPDATE project"), afterUpdate);
                    }
                    return result;
                });
        return new IdAllocator(wrapped);
    }

    private static JdbcClient.StatementSpec hooked(JdbcClient.StatementSpec spec, boolean hook, Runnable afterUpdate) {
        return (JdbcClient.StatementSpec) Proxy.newProxyInstance(JdbcClient.class.getClassLoader(),
                new Class<?>[] {JdbcClient.StatementSpec.class}, (proxy, method, args) -> {
                    Object result = invoke(method, spec, args);
                    if (result == spec) {
                        return proxy;
                    }
                    if (hook && "update".equals(method.getName())) {
                        afterUpdate.run();
                    }
                    return result;
                });
    }

    private static Object invoke(java.lang.reflect.Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @Test
    void vrf039_aCounterThatChangesBetweenTheTwoStatementsIsAConflict() {
        Project project = seed.project();
        for (int i = 0; i < 3; i++) {
            ctx.tx(() -> ids.nextIssueNo(project.code()));
        }
        IdAllocator allocator = allocatorWithHook(() -> CompletableFuture.runAsync(
                () -> ctx.tx(() -> ids.nextIssueNo(project.code()))).join());
        ConflictException conflict = catchThrowableOfType(ConflictException.class,
                () -> ctx.tx(() -> allocator.takeIssueNo(project.code(), 5)));
        assertThat(conflict).isNotNull();
        assertThat(conflict.code()).isEqualTo(ConflictCode.ID_COUNTER_CHANGED);
        assertThat(conflict.args()).containsExactly(project.code().value());
        assertThat(issueCounter(project)).isEqualTo(4);
        assertThat(ctx.tx(() -> ids.takeIssueNo(project.code(), 5))).isEmpty();
        assertThat(issueCounter(project)).isEqualTo(5);
    }

    @Test
    void vrf021_anExplicitTaskNumberIsTakenUpToTheNextNumberOnly() {
        Project project = seed.project();
        ctx.tx(() -> ids.nextIssueNo(project.code()));
        ctx.tx(() -> ids.nextTaskNo(project.code()));
        assertThat(ctx.tx(() -> ids.takeTaskNo(project.code(), 2))).isEmpty();
        assertThat(taskCounter(project)).isEqualTo(2);
        assertThat(ctx.tx(() -> ids.takeTaskNo(project.code(), 1))).isEmpty();
        assertThat(ctx.tx(() -> ids.takeTaskNo(project.code(), 4))).hasValue(3);
        assertThat(ctx.tx(() -> ids.takeTaskNo(project.code(), 123_456_789_012_345_678L))).hasValue(3);
        assertThat(taskCounter(project)).isEqualTo(2);
        assertThat(ctx.tx(() -> ids.nextTaskNo(project.code()))).isEqualTo(3);
        assertThat(issueCounter(project)).isEqualTo(1);
        LookupException unknown = catchThrowableOfType(LookupException.class,
                () -> ctx.tx(() -> ids.takeTaskNo(ProjectCode.of("NOSUCH"), 1)));
        assertThat(unknown.code()).isEqualTo(LookupCode.NOT_FOUND);
    }

    @Test
    void repo010_640ConcurrentAllocationsGiveEverySingleNumber() {
        Project project = seed.project();
        Callable<List<Long>> twentyAllocations = () -> IntStream.range(0, 20)
                .mapToObj(i -> ctx.tx(() -> ids.nextIssueNo(project.code()))).toList();
        List<Long> all = Parallel.all(times(32, twentyAllocations)).stream().flatMap(List::stream).sorted().toList();
        assertThat(all).isEqualTo(oneTo(640));
    }

    @Test
    void repo011_aRolledBackNumberIsIssuedAgain() {
        Project project = seed.project();
        assertThat(ctx.tx(() -> ids.nextIssueNo(project.code()))).isEqualTo(1);
        assertThatThrownBy(() -> ctx.tx(() -> {
            ids.nextIssueNo(project.code());
            throw new IllegalStateException("fail after allocating");
        })).hasMessageContaining("fail after allocating");
        assertThat(ctx.tx(() -> ids.nextIssueNo(project.code()))).isEqualTo(2);
    }

    private record Issued(String kind, long number) {
    }

    @Test
    void repo012_countersAreIndependentPerProjectAndKind() {
        Project a = seed.project();
        Project b = seed.project();
        List<Callable<Issued>> work = IntStream.range(0, 20).boxed().flatMap(i -> Stream.<Callable<Issued>>of(
                () -> new Issued("A-I", ctx.tx(() -> ids.nextIssueNo(a.code()))),
                () -> new Issued("A-S", ctx.tx(() -> ids.nextSpecNo(a.code()))),
                () -> new Issued("A-T", ctx.tx(() -> ids.nextTaskNo(a.code()))),
                () -> new Issued("B-I", ctx.tx(() -> ids.nextIssueNo(b.code()))),
                () -> new Issued("B-T", ctx.tx(() -> ids.nextTaskNo(b.code()))))).toList();
        var byKind = Parallel.all(work).stream().collect(Collectors.groupingBy(Issued::kind,
                Collectors.mapping(Issued::number, Collectors.toList())));
        assertThat(byKind.keySet()).containsExactlyInAnyOrder("A-I", "A-S", "A-T", "B-I", "B-T");
        assertThat(byKind.values()).allSatisfy(numbers -> assertThat(numbers).containsExactlyInAnyOrderElementsOf(
                oneTo(20)));
    }

    private static HistoryEntry historyRow(long id, Issue issue, int n) {
        return new HistoryEntry(id, LedgerTestContext.START, Seed.ALICE, Channel.AUTHOR.login(),
                ActorAssurance.DECLARED, HistoryEntity.ISSUE, issue.id().value(), HistoryOp.CREATE, null, null,
                JsonDocument.EMPTY_OBJECT, null, "writer " + n + " wrote this", null, null, null,
                new OperationKey(UUID.randomUUID().toString()), null, null);
    }

    @Test
    void repo013_surrogateKeysAreUniqueGaplessAndOrderedByInsertion() {
        Project project = seed.project();
        Issue issue = seed.issue(project, 1, "counter target", LedgerTestContext.START);
        long before = JdbcClient.create(ctx.pool()).sql("SELECT issued_no FROM id_counter WHERE name = 'HISTORY'")
                .query(Long.class).single();
        List<Callable<Long>> writers = IntStream.rangeClosed(1, 64).<Callable<Long>>mapToObj(n -> () -> {
            if (n % 8 == 0) {
                assertThatThrownBy(() -> ctx.tx(() -> {
                    long id = ids.next(Counter.HISTORY);
                    ctx.bean(HistoryRepository.class).insert(historyRow(id, issue, n));
                    throw new IllegalStateException("roll back writer " + n);
                })).isInstanceOf(IllegalStateException.class).hasMessage("roll back writer " + n);
                return -1L;
            }
            return ctx.tx(() -> {
                long id = ids.next(Counter.HISTORY);
                ctx.bean(HistoryRepository.class).insert(historyRow(id, issue, n));
                return id;
            });
        }).toList();
        List<Long> committed = Parallel.all(writers).stream().filter(id -> id > 0).sorted().toList();
        assertThat(committed).hasSize(56);
        assertThat(committed).isEqualTo(LongStream.rangeClosed(before + 1, before + 56).boxed().toList());
        List<Long> inOrder = ctx.tx(() -> Stream.generate(() -> ids.next(Counter.DELIVERABLE)).limit(3).toList());
        assertThat(inOrder.get(2) - inOrder.get(0)).isEqualTo(2);
        assertThat(inOrder).isSorted();
    }

    /** 64 writers take a number of the counter at the same time; every eighth rolls back and gives its number back. */
    @ParameterizedTest
    @EnumSource(Counter.class)
    void repo013_everyCounterIssuesDistinctNumbersWithoutGapsAfterRollbacks(Counter counter) {
        long before = ctx.tx(() -> ids.next(counter));
        List<Callable<Long>> writers = IntStream.rangeClosed(1, 64).<Callable<Long>>mapToObj(n -> () -> {
            if (n % 8 == 0) {
                assertThatThrownBy(() -> ctx.tx(() -> {
                    ids.next(counter);
                    throw new IllegalStateException("roll back writer " + n);
                })).isInstanceOf(IllegalStateException.class).hasMessage("roll back writer " + n);
                return -1L;
            }
            return ctx.tx(() -> ids.next(counter));
        }).toList();
        List<Long> committed = Parallel.all(writers).stream().filter(id -> id > 0).sorted().toList();
        assertThat(committed).isEqualTo(LongStream.rangeClosed(before + 1, before + 56).boxed().toList());
        assertThat(ctx.tx(() -> ids.next(counter))).isEqualTo(before + 57);
    }

    @Test
    void repo014_mixedWorkloadInTheFixedLockOrderNeverDeadlocks() {
        Project project = seed.project();
        Issue issue = seed.issue(project, 1, "lock order", LedgerTestContext.START);
        IssueRepository issues = ctx.bean(IssueRepository.class);
        List<Callable<Integer>> work = IntStream.range(0, 100).<Callable<Integer>>mapToObj(i -> () -> ctx.tx(() -> {
            Runnable first = switch (i % 4) {
                case 0, 3 -> () -> ids.nextIssueNo(project.code());
                case 1 -> () -> ids.nextTaskNo(project.code());
                default -> () -> issues.nextCaseSeq(issue.id());
            };
            first.run();
            ctx.bean(HistoryRepository.class).insert(historyRow(ids.next(Counter.HISTORY), issue, i));
            return i;
        })).toList();
        assertThat(Parallel.all(work)).hasSize(100);
    }

    @Test
    void repo035_codeRevisionGrowsByOnePerBump() {
        CodeRevisionRepository revision = ctx.bean(CodeRevisionRepository.class);
        long before = revision.current();
        Parallel.all(times(16, () -> ctx.tx(() -> {
            revision.bump();
            return revision.current();
        })));
        assertThat(revision.current()).isEqualTo(before + 16);
    }
}
