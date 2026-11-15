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
import org.pgledger.database.repository.ConditionQueryRepository;
import org.pgledger.database.repository.Counter;
import org.pgledger.database.repository.DefectQueryRepository;
import org.pgledger.database.repository.HistoryQueryRepository;
import org.pgledger.database.repository.HistoryRepository;
import org.pgledger.database.repository.IdAllocator;
import org.pgledger.database.repository.KeysetPage;
import org.pgledger.database.repository.KeywordMatch;
import org.pgledger.database.repository.SearchQueryRepository;
import org.pgledger.database.repository.TaskQueryRepository;
import org.pgledger.database.support.DatabaseTest;
import org.pgledger.database.support.LedgerTestContext;
import org.pgledger.database.support.Seed;
import org.pgledger.domain.model.Defect;
import org.pgledger.domain.model.HistoryEntry;
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
 * SQB-012, SQB-013: every list of the query repositories takes its row limit from {@code SqlBuilder} (the clause of the product
 * with a placeholder), and a page of exactly {@code max} rows tells that nothing follows (the limit is {@code max + 1}, the extra
 * row is what tells that the list continues). On the PostgreSQL of the test PC, in a schema of its own.
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
                null, null, null, new OperationKey(UUID.randomUUID().toString()),
                null)));
    }

    /** A page of three rows asked with a page size of {@code max}: the rows it holds and whether the list continues. */
    private static void assertPage(KeysetPage<?> page, int max) {
        assertThat(page.rows()).as("max " + max).hasSize(Math.min(max, 3));
        assertThat(page.truncated()).as("max " + max + " truncated").isEqualTo(max < 3);
    }

    @Test
    @DisplayName("SQB-012 a page of exactly max rows reports no next page in each list, and a shorter page does; "
            + "every statement carries the row limit of the product with a placeholder")
    void aPageOfExactlyMaxRowsHasNoNextPage() {
        Project project = seed.project();
        Defect first = seed.defect(project, 1, "alpha one", T0);
        seed.defect(project, 2, "alpha two", T0.plusSeconds(1));
        seed.defect(project, 3, "alpha three", T0.plusSeconds(2));
        for (int i = 1; i <= 3; i++) {
            seed.task(project, i, "task " + i);
            seed.condition(project, project.code().value() + "-C-" + i, "r", "c" + i);
            history(HistoryEntity.DEFECT, first.id().value(), i);
        }
        ctx.spy().clear();
        HistoryQueryRepository.Filter ofTheDefect = new HistoryQueryRepository.Filter(List.of(), null, null,
                HistoryEntity.DEFECT, first.id().value(), null, null, null, null, null);
        int calls = 0;
        for (int max : new int[] {2, 3, 4}) {
            MaxRows rows = new MaxRows(max);
            assertPage(ctx.bean(DefectQueryRepository.class).page(project.id(), DefectQueryRepository.Filter.NONE, rows,
                    DefectQueryRepository.Order.NEWEST_FIRST, null), max);
            assertPage(ctx.bean(DefectQueryRepository.class).search(project.id(), List.of("alpha"), KeywordMatch.ANY,
                    rows), max);
            assertPage(ctx.bean(TaskQueryRepository.class).page(project.id(), TaskQueryRepository.Filter.NONE, rows,
                    null), max);
            assertPage(ctx.bean(ConditionQueryRepository.class).page(project.id(),
                    ConditionQueryRepository.Filter.NONE, rows, null), max);
            assertPage(ctx.bean(AttentionQueryRepository.class).page(project.id(), AttentionKind.OPEN_NO_OWNER, rows,
                    null), max);
            assertPage(ctx.bean(SearchQueryRepository.class).page(new SearchQueryRepository.Filter(project.id(),
                    Set.of(SearchQueryRepository.Kind.DEFECT), List.of("alpha"), KeywordMatch.ANY, Set.of(), null,
                    null), rows, null), max);
            assertPage(ctx.bean(HistoryQueryRepository.class).idsOfEntity(HistoryEntity.DEFECT, first.id().value(), 0,
                    rows), max);
            assertPage(ctx.bean(HistoryQueryRepository.class).page(ofTheDefect, 0, rows), max);
            calls += 8;
        }
        assertThat(ctx.spy().statements()).hasSize(calls).allMatch(sql -> sql.endsWith(CLAUSE));
    }

    @Test
    @DisplayName("SQB-013 the newest history ids of an entity are at most the count asked for, by the limit of the product")
    void theNewestIdsAreAtMostTheCountAskedFor() {
        Project project = seed.project();
        Defect defect = seed.defect(project, 1, "alpha", T0);
        for (int i = 1; i <= 3; i++) {
            history(HistoryEntity.DEFECT, defect.id().value(), i);
        }
        ctx.spy().clear();
        HistoryQueryRepository queries = ctx.bean(HistoryQueryRepository.class);
        for (int count : new int[] {1, 2, 3, 10}) {
            assertThat(queries.newestOfEntity(HistoryEntity.DEFECT, defect.id().value(), false, count))
                    .as("count " + count).hasSize(Math.min(count, 3));
        }
        assertThat(ctx.spy().statements()).hasSize(4).allMatch(sql -> sql.endsWith(CLAUSE));
    }
}
