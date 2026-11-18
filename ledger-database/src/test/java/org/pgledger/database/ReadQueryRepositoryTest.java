package org.pgledger.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pgledger.database.repository.AttentionQueryRepository;
import org.pgledger.database.repository.Counter;
import org.pgledger.database.repository.CriterionQueryRepository;
import org.pgledger.database.repository.DeliverableRepository;
import org.pgledger.database.repository.EntityLookupRepository;
import org.pgledger.database.repository.HistoryQueryRepository;
import org.pgledger.database.repository.HistoryRepository;
import org.pgledger.database.repository.IdAllocator;
import org.pgledger.database.repository.IssueQueryRepository;
import org.pgledger.database.repository.IssueRepository;
import org.pgledger.database.repository.KeysetPage;
import org.pgledger.database.repository.KeywordMatch;
import org.pgledger.database.repository.LinkRepository;
import org.pgledger.database.repository.SearchQueryRepository;
import org.pgledger.database.repository.SpecQueryRepository;
import org.pgledger.database.repository.StatsQueryRepository;
import org.pgledger.database.repository.TaskQueryRepository;
import org.pgledger.database.support.DatabaseTest;
import org.pgledger.database.support.LedgerTestContext;
import org.pgledger.database.support.Seed;
import org.pgledger.domain.model.CriterionLink;
import org.pgledger.domain.model.Deliverable;
import org.pgledger.domain.model.HistoryEntry;
import org.pgledger.domain.model.Issue;
import org.pgledger.domain.model.IssueSpecLink;
import org.pgledger.domain.model.IssueTaskLink;
import org.pgledger.domain.model.Project;
import org.pgledger.domain.model.Spec;
import org.pgledger.domain.model.SpecTaskLink;
import org.pgledger.domain.model.Task;
import org.pgledger.domain.rule.ActorAssurance;
import org.pgledger.domain.rule.AttentionKind;
import org.pgledger.domain.rule.CriterionState;
import org.pgledger.domain.rule.DeliverableTarget;
import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.rule.HistoryOp;
import org.pgledger.domain.rule.SpecStatus;
import org.pgledger.domain.rule.TaskState;
import org.pgledger.domain.value.CodeValue;
import org.pgledger.domain.value.JsonDocument;
import org.pgledger.domain.value.MaxRows;
import org.pgledger.domain.value.OperationKey;

/**
 * The query repositories behind the read tools: lookups, filters, keyset pages, search, history, counts, and attention.
 */
@DatabaseTest
class ReadQueryRepositoryTest {

    private LedgerTestContext ctx;
    private Seed seed;

    @BeforeEach
    void setUp(LedgerTestContext context) {
        ctx = context;
        seed = new Seed(ctx);
    }

    private static final Instant T0 = LedgerTestContext.START;

    private HistoryEntry history(HistoryEntity entity, String entityId, HistoryOp op, String author, Instant at) {
        IdAllocator ids = ctx.bean(IdAllocator.class);
        return ctx.tx(() -> ctx.bean(HistoryRepository.class).insert(new HistoryEntry(ids.next(Counter.HISTORY), at,
                org.pgledger.domain.value.AuthorId.of(author), "ledger_author", ActorAssurance.DECLARED, entity,
                entityId, op, null, null, JsonDocument.EMPTY_OBJECT, null, "a reason of enough length", null, null,
                null, new OperationKey(UUID.randomUUID().toString()), null, null)));
    }

    @Test
    void lookupFindsTheIdsOfEachKindAndIgnoresTheRest() {
        Project project = seed.project();
        Issue issue = seed.issue(project, 1, "i", T0);
        Spec spec = seed.spec(project, 1, "s", T0);
        Task task = seed.task(project, 1, "t");
        var criterion = seed.criterion(project, project.code().value() + "-C-1", "r", "c");
        EntityLookupRepository lookup = ctx.bean(EntityLookupRepository.class);
        List<String> ids = List.of(issue.id().value(), spec.id().value(), task.id().value(), criterion.id().value(),
                "NOPE-I-0001", issue.id().value().toLowerCase());
        assertThat(lookup.issues(ids)).extracting(EntityLookupRepository.Found::id)
                .containsExactly(issue.id().value());
        assertThat(lookup.specs(ids)).extracting(EntityLookupRepository.Found::state).containsExactly("PLANNED");
        assertThat(lookup.tasks(ids)).extracting(EntityLookupRepository.Found::state).containsExactly("PLANNED");
        assertThat(lookup.criteria(ids)).extracting(EntityLookupRepository.Found::version).containsExactly(1L);
        assertThat(lookup.issues(ids).getFirst().projectId()).isEqualTo(project.id().value());
    }

    @Test
    void issueListFiltersByOwnerWithDeliverableRecorderAsFallbackAndByAttentionKind() {
        Project project = seed.project();
        Issue owned = seed.issue(project, 1, "owned", T0);
        Issue withDeliv = seed.issue(project, 2, "with deliverable", T0.plusSeconds(1));
        Issue bare = seed.issue(project, 3, "bare", T0.plusSeconds(2));
        LedgerTestContext.as(Seed.ALICE, () -> ctx.tx(() -> ctx.bean(IssueRepository.class)
                .save(owned.ownedBy(Seed.BOB, T0))));
        IdAllocator ids = ctx.bean(IdAllocator.class);
        ctx.tx(() -> ctx.bean(DeliverableRepository.class).insert(Deliverable.record(ids.next(Counter.DELIVERABLE),
                DeliverableTarget.ISSUE, withDeliv.id().value(), CodeValue.of("CODE_PATCH"), "a".repeat(40), null,
                "deliv", null, null, null, null, Seed.ALICE, T0)));
        IssueQueryRepository queries = ctx.bean(IssueQueryRepository.class);
        MaxRows all = MaxRows.DEFAULT;
        assertThat(queries.page(project.id(), new IssueQueryRepository.Filter(null, null, null, null, null,
                Seed.BOB.value(), null), all, IssueQueryRepository.Order.NEWEST_FIRST, null).rows())
                .extracting(s -> s.id().value()).containsExactly(owned.id().value());
        var byRecorder = queries.page(project.id(), new IssueQueryRepository.Filter(null, null, null, null, null,
                Seed.ALICE.value(), null), all, IssueQueryRepository.Order.NEWEST_FIRST, null).rows();
        assertThat(byRecorder).extracting(s -> s.id().value()).containsExactly(withDeliv.id().value());
        assertThat(byRecorder.getFirst().owner()).isEqualTo(Seed.ALICE.value());
        assertThat(queries.page(project.id(), new IssueQueryRepository.Filter(null, null, null, null, null, null,
                AttentionKind.OPEN_WITH_DELIVERABLE), all, IssueQueryRepository.Order.NEWEST_FIRST, null).rows())
                .extracting(s -> s.id().value()).containsExactly(withDeliv.id().value());
        assertThat(queries.page(project.id(), new IssueQueryRepository.Filter(null, null, null, null, null, null,
                AttentionKind.OPEN_NO_OWNER), all, IssueQueryRepository.Order.NEWEST_FIRST, null).rows())
                .extracting(s -> s.id().value()).containsExactly(bare.id().value(), withDeliv.id().value());
        assertThatThrownBy(() -> queries.page(project.id(), new IssueQueryRepository.Filter(null, null, null, null,
                null, null, AttentionKind.TASK_UNKNOWN), all, IssueQueryRepository.Order.NEWEST_FIRST, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void specListPagesNewestFirstAndFiltersByStatusPriorityAndParent() {
        Project project = seed.project();
        Spec parent = seed.spec(project, 1, "parent spec", T0);
        seed.spec(project, 2, "second spec", T0.plusSeconds(1));
        SpecQueryRepository queries = ctx.bean(SpecQueryRepository.class);
        var page = queries.page(project.id(), SpecQueryRepository.Filter.NONE, new MaxRows(1), null);
        assertThat(page.truncated()).isTrue();
        assertThat(page.rows()).hasSize(1);
        assertThat(queries.page(project.id(), new SpecQueryRepository.Filter(SpecStatus.PLANNED, null, "P2",
                null, null), MaxRows.DEFAULT, null).rows()).hasSize(2);
        assertThat(queries.page(project.id(), new SpecQueryRepository.Filter(null, parent.id(), null, null, null),
                MaxRows.DEFAULT, null).rows()).isEmpty();
    }

    @Test
    void taskListPagesNewestFirstThroughEqualTimesAndFilters() {
        Project project = seed.project();
        List<Task> tasks = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            tasks.add(seed.task(project, i, "task " + i));
        }
        TaskQueryRepository queries = ctx.bean(TaskQueryRepository.class);
        List<String> seen = new ArrayList<>();
        TaskQueryRepository.Position after = null;
        KeysetPage<TaskQueryRepository.TaskSummary> page;
        do {
            page = queries.page(project.id(), TaskQueryRepository.Filter.NONE, new MaxRows(2), after);
            page.rows().forEach(r -> seen.add(r.id().value()));
            after = page.rows().isEmpty() ? null : new TaskQueryRepository.Position(page.rows().getLast()
                    .createdAt(), page.rows().getLast().id());
        } while (page.truncated());
        assertThat(seen).containsExactlyElementsOf(tasks.reversed().stream().map(t -> t.id().value()).toList());
        assertThat(queries.page(project.id(), new TaskQueryRepository.Filter(TaskState.ACTIVE, null, null),
                MaxRows.DEFAULT, null).rows()).isEmpty();
        assertThat(queries.page(project.id(), new TaskQueryRepository.Filter(null, null,
                AttentionKind.TASK_UNKNOWN), MaxRows.DEFAULT, null).rows()).isEmpty();
        assertThat(queries.page(project.id(), new TaskQueryRepository.Filter(TaskState.PLANNED, null, null),
                MaxRows.DEFAULT, null).rows()).hasSize(7);
    }

    @Test
    void criterionListPagesByIdDescendingWithFilters() {
        Project project = seed.project();
        String prefix = project.code().value() + "-C-";
        for (int i = 1; i <= 5; i++) {
            seed.criterion(project, prefix + i, i % 2 == 0 ? "r2" : "r1", "c" + i);
        }
        CriterionQueryRepository queries = ctx.bean(CriterionQueryRepository.class);
        var first = queries.page(project.id(), CriterionQueryRepository.Filter.NONE, new MaxRows(2), null);
        assertThat(first.rows()).extracting(c -> c.id().value()).containsExactly(prefix + 5, prefix + 4);
        assertThat(first.truncated()).isTrue();
        var next = queries.page(project.id(), CriterionQueryRepository.Filter.NONE, new MaxRows(2),
                first.rows().getLast().id());
        assertThat(next.rows()).extracting(c -> c.id().value()).containsExactly(prefix + 3, prefix + 2);
        assertThat(queries.page(project.id(), new CriterionQueryRepository.Filter(CriterionState.UNKNOWN, "r2"),
                MaxRows.DEFAULT, null).rows()).extracting(c -> c.id().value()).containsExactly(prefix + 4,
                prefix + 2);
    }

    private SearchQueryRepository.Filter search(Project project, List<String> keywords, KeywordMatch match,
            Set<SearchQueryRepository.Kind> kinds) {
        return new SearchQueryRepository.Filter(project.id(), kinds, keywords, match, Set.of(), null, null);
    }

    private static final Set<SearchQueryRepository.Kind> EVERY_KIND = Set.of(SearchQueryRepository.Kind.values());

    @Test
    void searchFindsKeywordsInEveryKindIgnoringCaseAndTreatingWildcardsAsText() {
        Project project = seed.project();
        seed.issue(project, 1, "Login Bug", T0);
        seed.issue(project, 2, "100%_done", T0.plusSeconds(1));
        seed.spec(project, 1, "login spec", T0.plusSeconds(2));
        seed.task(project, 1, "login task");
        seed.criterion(project, project.code().value() + "-C-1", "r", "c1");
        SearchQueryRepository repository = ctx.bean(SearchQueryRepository.class);
        var login = repository.page(search(project, List.of("LOGIN"), KeywordMatch.ANY, EVERY_KIND),
                MaxRows.DEFAULT, null);
        assertThat(login.rows()).extracting(SearchQueryRepository.Hit::kind).containsExactlyInAnyOrder(
                SearchQueryRepository.Kind.TASK, SearchQueryRepository.Kind.SPEC, SearchQueryRepository.Kind.ISSUE);
        var criterionHits = repository.page(search(project, List.of("CRITERION te"), KeywordMatch.ANY, EVERY_KIND),
                MaxRows.DEFAULT, null);
        assertThat(criterionHits.rows()).extracting(SearchQueryRepository.Hit::kind)
                .containsExactly(SearchQueryRepository.Kind.CRITERION);
        assertThat(criterionHits.rows().getFirst().createdAt()).isNull();
        assertThat(repository.page(search(project, List.of("100%"), KeywordMatch.ANY, EVERY_KIND), MaxRows.DEFAULT,
                null).rows()).hasSize(1);
        assertThat(repository.page(search(project, List.of("%"), KeywordMatch.ANY, EVERY_KIND), MaxRows.DEFAULT,
                null).rows()).as("a percent sign is text").hasSize(1);
        assertThat(repository.page(search(project, List.of("'; DROP TABLE project; --"), KeywordMatch.ANY,
                EVERY_KIND), MaxRows.DEFAULT, null).rows()).isEmpty();
    }

    @Test
    void searchAnyAllExcludeKindsAndTimeRange() {
        Project project = seed.project();
        seed.issue(project, 1, "alpha beta", T0);
        seed.issue(project, 2, "alpha", T0.plusSeconds(60));
        seed.issue(project, 3, "beta", T0.plusSeconds(120));
        seed.task(project, 1, "alpha beta task");
        SearchQueryRepository repository = ctx.bean(SearchQueryRepository.class);
        Set<SearchQueryRepository.Kind> issuesOnly = Set.of(SearchQueryRepository.Kind.ISSUE);
        assertThat(repository.page(search(project, List.of("alpha", "beta"), KeywordMatch.ANY, issuesOnly),
                MaxRows.DEFAULT, null).rows()).hasSize(3);
        assertThat(repository.page(search(project, List.of("alpha", "beta"), KeywordMatch.ALL, issuesOnly),
                MaxRows.DEFAULT, null).rows()).extracting(SearchQueryRepository.Hit::title)
                .containsExactly("alpha beta");
        assertThat(repository.page(new SearchQueryRepository.Filter(project.id(), issuesOnly, List.of("alpha"),
                KeywordMatch.ANY, Set.of("OPEN"), null, null), MaxRows.DEFAULT, null).rows()).isEmpty();
        assertThat(repository.page(new SearchQueryRepository.Filter(project.id(), EVERY_KIND, List.of(),
                KeywordMatch.ANY, Set.of(), T0.plusSeconds(60), T0.plusSeconds(120)), MaxRows.DEFAULT, null).rows())
                .extracting(SearchQueryRepository.Hit::title).containsExactlyInAnyOrder("alpha", "beta");
    }

    @Test
    void searchPagesWithoutDuplicatesThroughEqualTimes() {
        Project project = seed.project();
        for (int i = 1; i <= 5; i++) {
            seed.issue(project, i, "same time " + i, T0);
        }
        for (int i = 1; i <= 3; i++) {
            seed.task(project, i, "same time task " + i);
        }
        SearchQueryRepository repository = ctx.bean(SearchQueryRepository.class);
        List<String> seen = new ArrayList<>();
        SearchQueryRepository.Position after = null;
        KeysetPage<SearchQueryRepository.Hit> page;
        do {
            page = repository.page(search(project, List.of("same time"), KeywordMatch.ANY, EVERY_KIND),
                    new MaxRows(3), after);
            page.rows().forEach(h -> seen.add(h.kind() + h.id()));
            var last = page.rows().getLast();
            after = new SearchQueryRepository.Position(last.createdAt(), last.kind(), last.id());
        } while (page.truncated());
        assertThat(seen).hasSize(8).doesNotHaveDuplicates();
    }

    @Test
    void historyFiltersAreCombinedAndPagedAscending() {
        Project project = seed.project();
        Project other = seed.project();
        Issue issue = seed.issue(project, 1, "i", T0);
        Issue elsewhere = seed.issue(other, 1, "e", T0);
        var criterion = seed.criterion(project, project.code().value() + "-C-1", "r", "c");
        IdAllocator ids = ctx.bean(IdAllocator.class);
        ctx.tx(() -> ctx.bean(DeliverableRepository.class).insert(Deliverable.record(ids.next(Counter.DELIVERABLE),
                DeliverableTarget.ISSUE, issue.id().value(), CodeValue.of("CODE_PATCH"), "b".repeat(40), null,
                "deliv", null, null, null, null, Seed.ALICE, T0)));
        ctx.tx(() -> ctx.bean(LinkRepository.class).add(CriterionLink.toIssue(criterion.id(), issue.id(),
                Seed.ALICE)));
        HistoryEntry h1 = history(HistoryEntity.ISSUE, issue.id().value(), HistoryOp.CREATE, "claude:alice", T0);
        HistoryEntry h2 = history(HistoryEntity.DELIVERABLE, issue.id().value() + "@" + "b".repeat(40),
                HistoryOp.CREATE, "claude:alice", T0.plusSeconds(10));
        HistoryEntry h3 = history(HistoryEntity.CRITERION_LINK, criterion.id().value() + "|" + issue.id().value(),
                HistoryOp.LINK, "codex:bob", T0.plusSeconds(20));
        HistoryEntry h4 = history(HistoryEntity.ISSUE, elsewhere.id().value(), HistoryOp.CREATE, "claude:alice",
                T0.plusSeconds(30));
        HistoryEntry h5 = history(HistoryEntity.ISSUE, issue.id().value(), HistoryOp.EDIT, "codex:bob",
                T0.plusSeconds(40));
        HistoryQueryRepository queries = ctx.bean(HistoryQueryRepository.class);
        HistoryQueryRepository.Filter none = HistoryQueryRepository.Filter.NONE;
        assertThat(queries.page(withProject(none, project), 0, MaxRows.DEFAULT).rows())
                .containsExactly(h1.id(), h2.id(), h3.id(), h5.id());
        assertThat(queries.page(withProject(none, other), 0, MaxRows.DEFAULT).rows()).containsExactly(h4.id());
        var byIds = new HistoryQueryRepository.Filter(List.of(h5.id(), h1.id()), null, null, null, null, null, null,
                null, null, null);
        assertThat(queries.page(byIds, 0, MaxRows.DEFAULT).rows()).containsExactly(h1.id(), h5.id());
        var range = new HistoryQueryRepository.Filter(List.of(), h2.id(), h4.id(), null, null, null, null, null, null,
                null);
        assertThat(queries.page(range, 0, MaxRows.DEFAULT).rows()).containsExactly(h2.id(), h3.id(), h4.id());
        var edits = new HistoryQueryRepository.Filter(List.of(), null, null, null, null, null, HistoryOp.EDIT,
                "codex:bob", null, null);
        assertThat(queries.page(edits, 0, MaxRows.DEFAULT).rows()).containsExactly(h5.id());
        var timed = new HistoryQueryRepository.Filter(List.of(), null, null, null, null, null, null, null,
                T0.plusSeconds(10), T0.plusSeconds(30));
        assertThat(queries.page(timed, 0, MaxRows.DEFAULT).rows()).containsExactly(h2.id(), h3.id(), h4.id());
        var entity = new HistoryQueryRepository.Filter(List.of(), null, null, HistoryEntity.ISSUE,
                issue.id().value(), null, null, null, null, null);
        var first = queries.page(entity, 0, new MaxRows(1));
        assertThat(first.rows()).containsExactly(h1.id());
        assertThat(first.truncated()).isTrue();
        assertThat(queries.page(entity, h1.id(), new MaxRows(1)).rows()).containsExactly(h5.id());
        assertThat(queries.newestOfEntity(HistoryEntity.ISSUE, issue.id().value(), true, 1))
                .containsExactly(h5.id());
    }

    private static HistoryQueryRepository.Filter withProject(HistoryQueryRepository.Filter base, Project project) {
        return new HistoryQueryRepository.Filter(base.ids(), base.idFrom(), base.idTo(), base.entity(),
                base.entityId(), project.id(), base.op(), base.author(), base.atFrom(), base.atTo());
    }

    @Test
    void countsPerStateAndPerUtcDayForOneProjectAndForAll() {
        Project project = seed.project();
        seed.issue(project, 1, "a", Instant.parse("2026-11-05T14:59:59Z"));
        seed.issue(project, 2, "b", Instant.parse("2026-11-05T15:00:00Z"));
        seed.issue(project, 3, "c", Instant.parse("2026-11-06T00:00:00Z"));
        seed.spec(project, 1, "s", Instant.parse("2026-11-05T15:00:00Z"));
        seed.task(project, 1, "t");
        seed.criterion(project, project.code().value() + "-C-1", "r", "c");
        StatsQueryRepository stats = ctx.bean(StatsQueryRepository.class);
        assertThat(stats.issuesByStatus(project.id())).containsExactly(Map.entry("OPEN", 3L));
        assertThat(stats.issuesBySeverity(project.id())).containsExactly(Map.entry("P2", 3L));
        assertThat(stats.specsByStatus(project.id())).containsExactly(Map.entry("PLANNED", 1L));
        assertThat(stats.tasksByState(project.id())).containsExactly(Map.entry("PLANNED", 1L));
        assertThat(stats.criteriaByState(project.id())).containsExactly(Map.entry("UNKNOWN", 1L));
        assertThat(stats.issuesCreatedPerDay(project.id())).containsExactly(
                Map.entry(LocalDate.of(2026, 11, 5), 2L), Map.entry(LocalDate.of(2026, 11, 6), 1L));
        assertThat(stats.specsCreatedPerDay(project.id())).containsExactly(
                Map.entry(LocalDate.of(2026, 11, 5), 1L));
        assertThat(stats.issuesByStatus(null).get("OPEN")).isGreaterThanOrEqualTo(3L);
    }

    @Test
    void attentionPagesByKindAndIdWithFilters() {
        Project project = seed.project();
        seed.issue(project, 1, "no owner one", T0);
        seed.issue(project, 2, "no owner two", T0);
        seed.task(project, 1, "planned");
        AttentionQueryRepository attention = ctx.bean(AttentionQueryRepository.class);
        var page = attention.page(project.id(), null, new MaxRows(1), null);
        assertThat(page.rows()).extracting(AttentionQueryRepository.Item::id).containsExactly(
                project.code().value() + "-I-0001");
        assertThat(page.truncated()).isTrue();
        var next = attention.page(project.id(), null, new MaxRows(5), new AttentionQueryRepository.Position(
                page.rows().getFirst().kind(), page.rows().getFirst().id()));
        assertThat(next.rows()).extracting(AttentionQueryRepository.Item::id).containsExactly(
                project.code().value() + "-I-0002");
        assertThat(attention.page(project.id(), AttentionKind.OPEN_WITH_DELIVERABLE, MaxRows.DEFAULT, null).rows())
                .isEmpty();
    }

    @Test
    void linkListsFindTheOtherSide() {
        Project project = seed.project();
        Issue issue = seed.issue(project, 1, "i", T0);
        Spec spec = seed.spec(project, 1, "s", T0);
        Task task = seed.task(project, 1, "t");
        var criterion = seed.criterion(project, project.code().value() + "-C-1", "r", "c");
        LinkRepository links = ctx.bean(LinkRepository.class);
        ctx.tx(() -> links.add(new IssueTaskLink(issue.id(), task.id(), Seed.ALICE)));
        ctx.tx(() -> links.add(new IssueSpecLink(issue.id(), spec.id(), Seed.ALICE)));
        ctx.tx(() -> links.add(new SpecTaskLink(spec.id(), task.id(), Seed.ALICE)));
        ctx.tx(() -> links.add(CriterionLink.toIssue(criterion.id(), issue.id(), Seed.ALICE)));
        ctx.tx(() -> links.add(CriterionLink.toSpec(criterion.id(), spec.id(), Seed.ALICE)));
        ctx.tx(() -> links.add(CriterionLink.toTask(criterion.id(), task.id(), Seed.ALICE)));
        assertThat(links.issuesOfTask(task.id())).containsExactly(issue.id().value());
        assertThat(links.specsOfIssue(issue.id())).containsExactly(spec.id().value());
        assertThat(links.tasksOfSpec(spec.id())).containsExactly(task.id().value());
        assertThat(links.criteriaOfIssue(issue.id())).containsExactly(criterion.id().value());
        assertThat(links.criteriaOfSpec(spec.id())).containsExactly(criterion.id().value());
        assertThat(links.criteriaOfTask(task.id())).containsExactly(criterion.id().value());
    }
}
