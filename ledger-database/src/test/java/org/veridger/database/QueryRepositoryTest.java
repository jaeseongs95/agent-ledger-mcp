// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.veridger.database.repository.AttentionQueryRepository;
import org.veridger.database.repository.Counter;
import org.veridger.database.repository.DeliverableRepository;
import org.veridger.database.repository.IdAllocator;
import org.veridger.database.repository.IssueQueryRepository;
import org.veridger.database.repository.IssueQueryRepository.Filter;
import org.veridger.database.repository.IssueQueryRepository.Order;
import org.veridger.database.repository.IssueQueryRepository.Position;
import org.veridger.database.repository.IssueRepository;
import org.veridger.database.repository.IssueSummary;
import org.veridger.database.repository.KeysetPage;
import org.veridger.database.repository.KeywordMatch;
import org.veridger.database.repository.MergeChainRepository;
import org.veridger.database.repository.SpecRepository;
import org.veridger.database.repository.StatsQueryRepository;
import org.veridger.database.repository.TaskRepository;
import org.veridger.database.support.LedgerDataJdbcTest;
import org.veridger.database.support.LedgerTestContext;
import org.veridger.database.support.RecordingDataSource;
import org.veridger.database.support.Seed;
import org.veridger.domain.model.Deliverable;
import org.veridger.domain.model.Issue;
import org.veridger.domain.model.Project;
import org.veridger.domain.model.Spec;
import org.veridger.domain.rule.AttentionKind;
import org.veridger.domain.rule.DeliverableTarget;
import org.veridger.domain.rule.IssueStatus;
import org.veridger.domain.rule.SpecStatus;
import org.veridger.domain.rule.TaskState;
import org.veridger.domain.value.CodeValue;
import org.veridger.domain.value.IssueId;
import org.veridger.domain.value.MaxRows;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * REPO-025 .. REPO-028, REPO-034, REPO-036: row limits, keyset pages, search, day ranges, counts, merge chains.
 */
@LedgerDataJdbcTest
class QueryRepositoryTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private RecordingDataSource spy;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private IssueQueryRepository queries;

    @Autowired
    private IssueRepository issues;

    @Autowired
    private SpecRepository specs;

    @Autowired
    private DeliverableRepository deliverables;

    @Autowired
    private TaskRepository tasks;

    @Autowired
    private IdAllocator ids;

    @Autowired
    private StatsQueryRepository stats;

    @Autowired
    private AttentionQueryRepository attentionQueries;

    @Autowired
    private MergeChainRepository mergeChains;

    private Seed seed;

    @BeforeEach
    void setUp() {
        seed = new Seed(context);
    }

    private Project projectWithIssues(int count, boolean distinctTimes) {
        Project project = seed.project();
        IntStream.rangeClosed(1, count).forEach(i -> seed.issue(project, i, "row " + i,
                LedgerTestContext.START.plusSeconds(distinctTimes ? i : 0)));
        return project;
    }

    private record PageSize(int max, int rows, boolean truncated) {
    }

    static Stream<PageSize> pageSizes() {
        return Stream.of(new PageSize(1, 1, true), new PageSize(10, 10, true), new PageSize(29, 29, true),
                new PageSize(30, 30, false), new PageSize(1000, 30, false));
    }

    @ParameterizedTest
    @MethodSource("pageSizes")
    void repo025_oneRowMoreThanTheLimitTellsTheListContinuesWithTheRowLimitOfTheProduct(PageSize size) {
        Project project = projectWithIssues(30, true);
        spy.clear();
        KeysetPage<IssueSummary> page = queries.page(project.id(), Filter.NONE, new MaxRows(size.max()),
                Order.NEWEST_FIRST, null);
        assertThat(page.rows()).hasSize(size.rows());
        assertThat(page.truncated()).isEqualTo(size.truncated());
        assertThat(spy.statements()).hasSize(1).allMatch(s -> s.endsWith(" FETCH FIRST ? ROWS ONLY"));
    }

    private List<IssueId> walk(Project project, Order order, Position after) {
        KeysetPage<IssueSummary> page = queries.page(project.id(), Filter.NONE, new MaxRows(2), order, after);
        Stream<IssueId> here = page.rows().stream().map(IssueSummary::id);
        IssueSummary last = page.rows().getLast();
        return page.truncated()
                ? Stream.concat(here, walk(project, order, new Position(last.createdAt(), last.id())).stream())
                        .toList()
                : here.toList();
    }

    @ParameterizedTest
    @EnumSource(Order.class)
    void repo026_sevenRowsWithTheSameTimeAreWalkedExactlyOnceInEveryOrder(Order order) {
        Project project = projectWithIssues(7, false);
        List<IssueId> expected = IntStream.rangeClosed(1, 7).mapToObj(i -> IssueId.of(project.code(), i))
                .sorted(order == Order.NEWEST_FIRST ? Comparator.comparing(IssueId::value).reversed()
                        : Comparator.comparing(IssueId::value)).toList();
        assertThat(walk(project, order, null)).isEqualTo(expected);
    }

    private record Search(List<String> keywords, KeywordMatch match, List<String> titles) {
        @Override
        public String toString() {
            return keywords + " " + match;
        }
    }

    private static final List<String> SEARCH_TITLES = List.of("Login Bug", "login bug", "LOGIN BUG", "100%_done",
            "a\\b", "1000 done");
    private static final List<String> LOGIN_TITLES = List.of("LOGIN BUG", "login bug", "Login Bug");

    static Stream<Search> searches() {
        return Stream.of(
                new Search(List.of("login"), KeywordMatch.ANY, LOGIN_TITLES),
                new Search(List.of("LOGIN"), KeywordMatch.ANY, LOGIN_TITLES),
                new Search(List.of("100%"), KeywordMatch.ANY, List.of("100%_done")),
                new Search(List.of("_done"), KeywordMatch.ANY, List.of("100%_done")),
                new Search(List.of("a\\b"), KeywordMatch.ANY, List.of("a\\b")),
                new Search(List.of("login", "nothing"), KeywordMatch.ANY, LOGIN_TITLES),
                new Search(List.of("login", "nothing"), KeywordMatch.ALL, List.of()),
                new Search(List.of("symptom of login"), KeywordMatch.ANY, LOGIN_TITLES),
                new Search(List.of("'; DROP TABLE project; --"), KeywordMatch.ANY, List.of()));
    }

    @ParameterizedTest
    @MethodSource("searches")
    void repo027_searchIsCaseInsensitiveAndEscapesLikeCharacters(Search search) {
        Project project = seed.project();
        IntStream.range(0, SEARCH_TITLES.size()).forEach(i -> seed.issue(project, i + 1, SEARCH_TITLES.get(i),
                LedgerTestContext.START.plusSeconds(i + 1)));
        assertThat(queries.search(project.id(), search.keywords(), search.match(), MaxRows.DEFAULT).rows())
                .extracting(IssueSummary::title).containsExactlyElementsOf(search.titles());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM project").query(Integer.class).single())
                .as("injection text is data: the table survives").isPositive();
    }

    @Test
    void repo027_aSearchWithoutKeywordsIsRefused() {
        assertThatThrownBy(() -> queries.search(seed.project().id(), List.of(), KeywordMatch.ANY, MaxRows.DEFAULT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private record Range(String name, Instant from, Instant to, List<String> titles) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static final List<Instant> RANGE_TIMES = List.of(Instant.parse("2026-11-04T23:59:59.999999Z"),
            Instant.parse("2026-11-05T00:00:00Z"), Instant.parse("2026-11-05T14:59:59Z"),
            Instant.parse("2026-11-05T15:00:00Z"));

    static Stream<Range> ranges() {
        return Stream.of(
                new Range("the UTC day 2026-11-05", Instant.parse("2026-11-05T00:00:00Z"),
                        Instant.parse("2026-11-05T23:59:59.999999Z"), List.of("t1", "t2", "t3")),
                new Range("the same day in Seoul (KST)", Instant.parse("2026-11-04T15:00:00Z"),
                        Instant.parse("2026-11-05T14:59:59.999999Z"), List.of("t0", "t1", "t2")),
                new Range("both ends are inclusive", Instant.parse("2026-11-05T14:59:59Z"),
                        Instant.parse("2026-11-05T15:00:00Z"), List.of("t2", "t3")),
                new Range("open end", Instant.parse("2026-11-05T15:00:00Z"), null, List.of("t3")));
    }

    @ParameterizedTest
    @MethodSource("ranges")
    void repo028_dayRangesAreInclusiveAtBothEnds(Range range) {
        Project project = seed.project();
        IntStream.range(0, RANGE_TIMES.size()).forEach(i -> seed.issue(project, i + 1, "t" + i, RANGE_TIMES.get(i)));
        assertThat(queries.page(project.id(), new Filter(null, null, null, range.from(), range.to(), null, null),
                MaxRows.DEFAULT, Order.OLDEST_FIRST, null).rows()).extracting(IssueSummary::title)
                .containsExactlyElementsOf(range.titles());
    }

    @Test
    void repo034_countsAndTheSixAttentionKinds() {
        Project project = seed.project();
        Issue openWithDeliv = seed.issue(project, 1, "open with deliverable", LedgerTestContext.START);
        Issue openNoOwner = seed.issue(project, 2, "open no owner", LedgerTestContext.START);
        Issue resolvedNoDeliv = LedgerTestContext.as(Seed.ALICE, () -> issues.save(
                seed.issue(project, 3, "resolved", LedgerTestContext.START)
                        .transitioned(IssueStatus.RESOLVED, null, LedgerTestContext.START)));
        Issue verifiedWithDeliv = LedgerTestContext.as(Seed.ALICE, () -> issues.save(
                seed.issue(project, 4, "verified", LedgerTestContext.START)
                        .transitioned(IssueStatus.VERIFIED, null, LedgerTestContext.START)));
        deliverables.insert(Deliverable.record(ids.next(Counter.DELIVERABLE), DeliverableTarget.ISSUE,
                openWithDeliv.id().value(), CodeValue.of("CODE_PATCH"), "d".repeat(40), null, "first deliverable",
                null, null, null, null, Seed.ALICE, LedgerTestContext.START));
        deliverables.insert(Deliverable.record(ids.next(Counter.DELIVERABLE), DeliverableTarget.ISSUE,
                verifiedWithDeliv.id().value(), CodeValue.of("CODE_PATCH"), "e".repeat(40), null, "second deliverable",
                null, null, null, null, Seed.ALICE, LedgerTestContext.START));
        Spec completedSpec = seed.spec(project, 1, "completed spec", LedgerTestContext.START);
        LedgerTestContext.as(Seed.ALICE, () -> specs.save(completedSpec.transitioned(SpecStatus.COMPLETED,
                LedgerTestContext.START)));
        var unknownTask = seed.task(project, 1, "unknown task");
        LedgerTestContext.as(Seed.ALICE, () -> tasks.save(unknownTask.transitioned(TaskState.UNKNOWN,
                LedgerTestContext.START)));
        var doneTask = seed.task(project, 2, "done task");
        LedgerTestContext.as(Seed.ALICE, () -> tasks.save(doneTask.transitioned(TaskState.COMPLETED,
                LedgerTestContext.START)));

        assertThat(stats.issuesByStatus(project.id())).containsEntry(IssueStatus.OPEN.name(), 2L)
                .containsEntry(IssueStatus.RESOLVED.name(), 1L).containsEntry(IssueStatus.VERIFIED.name(), 1L);
        assertThat(stats.issuesBySeverity(project.id())).containsEntry("P2", 4L);
        assertThat(stats.specsByStatus(project.id())).containsEntry(SpecStatus.COMPLETED.name(), 1L);
        List<AttentionQueryRepository.Item> attention = attentionQueries
                .page(project.id(), null, MaxRows.DEFAULT, null).rows();
        assertThat(attention).extracting(AttentionQueryRepository.Item::kind, AttentionQueryRepository.Item::id)
                .contains(tuple(AttentionKind.OPEN_WITH_DELIVERABLE, openWithDeliv.id().value()),
                        tuple(AttentionKind.OPEN_NO_OWNER, openNoOwner.id().value()),
                        tuple(AttentionKind.OPEN_NO_OWNER, openWithDeliv.id().value()),
                        tuple(AttentionKind.RESOLVED_WITHOUT_DELIVERABLE, resolvedNoDeliv.id().value()),
                        tuple(AttentionKind.SPEC_COMPLETED_NOT_VERIFIED, completedSpec.id().value()),
                        tuple(AttentionKind.TASK_UNKNOWN, unknownTask.id().value()),
                        tuple(AttentionKind.DONE_NOT_VERIFIED, doneTask.id().value()));
        assertThat(attention).extracting(AttentionQueryRepository.Item::id)
                .doesNotContain(verifiedWithDeliv.id().value());
        assertThat(attention).hasSize(7);
        assertThat(queries.page(project.id(), new Filter(null, null, null, null, null,
                null, AttentionKind.RESOLVED_WITHOUT_DELIVERABLE), MaxRows.DEFAULT, Order.OLDEST_FIRST, null).rows())
                .extracting(IssueSummary::id).containsExactly(resolvedNoDeliv.id());
        assertThatThrownBy(() -> queries.page(project.id(), new Filter(null, null, null,
                null, null, null, AttentionKind.TASK_UNKNOWN), MaxRows.DEFAULT, Order.OLDEST_FIRST, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("issue attention kind");
    }

    @Test
    void repo036_aMergeChainOfDepth50IsWalkedWithoutRecursiveSql() {
        Project project = seed.project();
        Issue root = seed.issue(project, 1, "root", LedgerTestContext.START);
        List<IssueId> chain = IntStream.rangeClosed(2, 51).mapToObj(i -> IssueId.of(project.code(), i)).toList();
        chain.stream().reduce(root.id(), (target, id) -> {
            Issue child = seed.issue(project, IssueId.of(id.value()).number(), "link " + id.value(),
                    LedgerTestContext.START);
            LedgerTestContext.as(Seed.ALICE, () -> issues.save(
                    child.transitioned(IssueStatus.MERGED, target, LedgerTestContext.START)));
            return id;
        });
        spy.clear();
        assertThat(mergeChains.mergedInto(root.id())).containsExactlyInAnyOrderElementsOf(chain);
        assertThat(spy.statements()).noneMatch(s -> s.toUpperCase().contains("RECURSIVE"));
        assertThat(mergeChains.mergedInto(chain.getLast())).isEmpty();
    }
}
