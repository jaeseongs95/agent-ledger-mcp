package org.pgledger.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.database.repository.AttentionQueryRepository;
import org.pgledger.database.repository.Counter;
import org.pgledger.database.repository.CriterionQueryRepository;
import org.pgledger.database.repository.HistoryQueryRepository;
import org.pgledger.database.repository.HistoryRepository;
import org.pgledger.database.repository.IdAllocator;
import org.pgledger.database.repository.IssueQueryRepository;
import org.pgledger.database.repository.KeysetPage;
import org.pgledger.database.repository.KeywordMatch;
import org.pgledger.database.repository.SearchQueryRepository;
import org.pgledger.database.repository.SpecQueryRepository;
import org.pgledger.database.repository.TaskQueryRepository;
import org.pgledger.database.support.DatabaseTest;
import org.pgledger.database.support.LedgerTestContext;
import org.pgledger.database.support.Seed;
import org.pgledger.domain.model.HistoryEntry;
import org.pgledger.domain.model.Issue;
import org.pgledger.domain.model.Project;
import org.pgledger.domain.rule.ActorAssurance;
import org.pgledger.domain.rule.AttentionKind;
import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.rule.HistoryOp;
import org.pgledger.domain.value.AuthorId;
import org.pgledger.domain.value.JsonDocument;
import org.pgledger.domain.value.MaxRows;
import org.pgledger.domain.value.OperationKey;

/**
 * SQB-012, SQB-013: every list query takes its row limit from {@code SqlBuilder}, and a page of exactly {@code max}
 * rows reports no next page.
 */
@DatabaseTest
class SqlBuilderRepositoryTest {

    private static final Instant T0 = LedgerTestContext.START;
    private static final String CLAUSE = " FETCH FIRST ? ROWS ONLY";

    private LedgerTestContext ctx;
    private Seed seed;

    @BeforeEach
    void setUp(LedgerTestContext context) {
        ctx = context;
        seed = new Seed(ctx);
    }

    private void history(HistoryEntity entity, String entityId, long second) {
        IdAllocator ids = ctx.bean(IdAllocator.class);
        ctx.tx(() -> ctx.bean(HistoryRepository.class).insert(new HistoryEntry(ids.next(Counter.HISTORY),
                T0.plusSeconds(second), AuthorId.of("claude:alice"), "ledger_author", ActorAssurance.DECLARED, entity,
                entityId, HistoryOp.EDIT, null, null, JsonDocument.EMPTY_OBJECT, null, "a reason of enough length",
                null, null, null, new OperationKey(UUID.randomUUID().toString()), null, null)));
    }

    private static void assertPage(KeysetPage<?> page, int max) {
        assertThat(page.rows()).as("max " + max).hasSize(Math.min(max, 3));
        assertThat(page.truncated()).as("max " + max + " truncated").isEqualTo(max < 3);
    }

    @Test
    @DisplayName("SQB-012 a page of exactly max rows reports no next page in each list, and a shorter page does")
    void aPageOfExactlyMaxRowsHasNoNextPage() {
        Project project = seed.project();
        Issue first = seed.issue(project, 1, "alpha one", T0);
        seed.issue(project, 2, "alpha two", T0.plusSeconds(1));
        seed.issue(project, 3, "alpha three", T0.plusSeconds(2));
        for (int i = 1; i <= 3; i++) {
            seed.spec(project, i, "spec " + i, T0.plusSeconds(i));
            seed.task(project, i, "task " + i);
            seed.criterion(project, project.code().value() + "-C-" + i, "r", "c" + i);
            history(HistoryEntity.ISSUE, first.id().value(), i);
        }
        ctx.spy().clear();
        HistoryQueryRepository.Filter ofTheIssue = new HistoryQueryRepository.Filter(List.of(), null, null,
                HistoryEntity.ISSUE, first.id().value(), null, null, null, null, null);
        int calls = 0;
        for (int max : new int[] {2, 3, 4}) {
            MaxRows rows = new MaxRows(max);
            assertPage(ctx.bean(IssueQueryRepository.class).page(project.id(), IssueQueryRepository.Filter.NONE, rows,
                    IssueQueryRepository.Order.NEWEST_FIRST, null), max);
            assertPage(ctx.bean(IssueQueryRepository.class).search(project.id(), List.of("alpha"), KeywordMatch.ANY,
                    rows), max);
            assertPage(ctx.bean(SpecQueryRepository.class).page(project.id(), SpecQueryRepository.Filter.NONE, rows,
                    null), max);
            assertPage(ctx.bean(TaskQueryRepository.class).page(project.id(), TaskQueryRepository.Filter.NONE, rows,
                    null), max);
            assertPage(ctx.bean(CriterionQueryRepository.class).page(project.id(),
                    CriterionQueryRepository.Filter.NONE, rows, null), max);
            assertPage(ctx.bean(AttentionQueryRepository.class).page(project.id(), AttentionKind.OPEN_NO_OWNER, rows,
                    null), max);
            assertPage(ctx.bean(SearchQueryRepository.class).page(new SearchQueryRepository.Filter(project.id(),
                    Set.of(SearchQueryRepository.Kind.ISSUE), List.of("alpha"), KeywordMatch.ANY, Set.of(), null,
                    null), rows, null), max);
            assertPage(ctx.bean(HistoryQueryRepository.class).idsOfEntity(HistoryEntity.ISSUE, first.id().value(), 0,
                    rows), max);
            assertPage(ctx.bean(HistoryQueryRepository.class).page(ofTheIssue, 0, rows), max);
            calls += 9;
        }
        assertThat(ctx.spy().statements()).hasSize(calls).allMatch(sql -> sql.endsWith(CLAUSE));
    }

    @Test
    @DisplayName("SQB-013 the newest history ids of an entity are at most the count asked for")
    void theNewestIdsAreAtMostTheCountAskedFor() {
        Project project = seed.project();
        Issue issue = seed.issue(project, 1, "alpha", T0);
        for (int i = 1; i <= 3; i++) {
            history(HistoryEntity.ISSUE, issue.id().value(), i);
        }
        ctx.spy().clear();
        HistoryQueryRepository queries = ctx.bean(HistoryQueryRepository.class);
        for (int count : new int[] {1, 2, 3, 10}) {
            assertThat(queries.newestOfEntity(HistoryEntity.ISSUE, issue.id().value(), false, count))
                    .as("count " + count).hasSize(Math.min(count, 3));
        }
        assertThat(ctx.spy().statements()).hasSize(4).allMatch(sql -> sql.endsWith(CLAUSE));
    }
}
