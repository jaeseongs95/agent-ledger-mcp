// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.veridger.database.repository.AttentionQueryRepository;
import org.veridger.database.repository.CodeRevisionRepository;
import org.veridger.database.repository.CommonCodeRepository;
import org.veridger.database.repository.Counter;
import org.veridger.database.repository.EntityLookupRepository;
import org.veridger.database.repository.GrantStateRepository;
import org.veridger.database.repository.IdAllocator;
import org.veridger.database.repository.LinkRepository;
import org.veridger.database.repository.ReceiptCompletionRepository;
import org.veridger.database.repository.ReceiptRepository;
import org.veridger.database.repository.SessionGrantRepository;
import org.veridger.database.support.DatabaseTest;
import org.veridger.database.support.LedgerTestContext;
import org.veridger.database.support.Seed;
import org.veridger.domain.model.CommonCode;
import org.veridger.domain.model.Criterion;
import org.veridger.domain.model.Issue;
import org.veridger.domain.model.IssueRelation;
import org.veridger.domain.model.Project;
import org.veridger.domain.model.Receipt;
import org.veridger.domain.model.ReceiptOperation;
import org.veridger.domain.model.SessionGrant;
import org.veridger.domain.model.Spec;
import org.veridger.domain.model.Task;
import org.veridger.domain.rule.AttentionKind;
import org.veridger.domain.rule.Channel;
import org.veridger.domain.rule.CodeDirection;
import org.veridger.domain.rule.CodeGroup;
import org.veridger.domain.rule.CodeState;
import org.veridger.domain.rule.HistoryEntity;
import org.veridger.domain.rule.Scope;
import org.veridger.domain.value.AuthorId;
import org.veridger.domain.value.CodeValue;
import org.veridger.domain.value.JsonDocument;
import org.veridger.domain.value.OperationKey;
import org.veridger.domain.value.Sha256;

/**
 * Verifies the v4.2.0 database performance optimizations (V12 secondary indexes, active_key grant lookup,
 * disjoint UNION ALL issue relations, bounded attention counts, single-round-trip findAny, IdAllocator.reserveRange,
 * CommonCodeRepository revision-cohered caching, and ReceiptCompletionRepository.finishAll).
 */
@DatabaseTest
class PerformanceOptimizationTest {

    private static final Instant T0 = LedgerTestContext.START;

    private LedgerTestContext ctx;
    private Seed seed;

    @BeforeEach
    void setUp(LedgerTestContext context) {
        ctx = context;
        seed = new Seed(ctx);
    }

    @Test
    @DisplayName("PERF-DB-01: V12__performance_indexes.sql creates all 14 secondary indexes")
    void v12IndexesExistInSchema() throws Exception {
        List<String> tables = List.of("criterion_link", "issue_task", "issue_spec", "spec_task", "issue_relation",
                "history", "task", "milestone_criterion", "session_grant", "deliverable");
        Set<String> found = new TreeSet<>();
        DataSource ds = ctx.bean(DataSource.class);
        try (Connection conn = ds.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            String schema = conn.getSchema();
            for (String table : tables) {
                try (ResultSet rs = meta.getIndexInfo(null, schema, table, false, false)) {
                    while (rs.next()) {
                        String name = rs.getString("INDEX_NAME");
                        if (name != null) {
                            found.add(name.toLowerCase(Locale.ROOT));
                        }
                    }
                }
            }
        }
        assertThat(found).contains(
                "ix_criterion_link_crit",
                "ix_criterion_link_issue",
                "ix_criterion_link_spec",
                "ix_criterion_link_task",
                "ix_issue_task_task",
                "ix_issue_spec_spec",
                "ix_spec_task_task",
                "ix_issue_relation_b",
                "ix_history_entity_occ",
                "ix_task_state",
                "ix_criterion_state",
                "ix_criterion_page",
                "ix_session_grant_live",
                "ix_deliverable_attach");
    }

    @Test
    @DisplayName("PERF-DB-02: GrantStateRepository active_key lookup ignores revoked grants and updates live grant")
    void grantStateUsesActiveKeyCorrectly() {
        Project project = seed.project();
        AuthorId author = AuthorId.of("codex:perf-grant-author");
        AuthorId owner = AuthorId.of("user:owner");
        SessionGrantRepository grants = ctx.bean(SessionGrantRepository.class);
        GrantStateRepository state = ctx.bean(GrantStateRepository.class);
        IdAllocator ids = ctx.bean(IdAllocator.class);

        ctx.tx(() -> {
            for (int i = 0; i < 10; i++) {
                long gid = ids.next(Counter.SESSION_GRANT);
                grants.insert(new SessionGrant(gid, author, project.id(), Scope.ISSUE,
                        owner, T0.minusSeconds(100 + i), T0.plusSeconds(3600),
                        T0.minusSeconds(50 + i), null, null, SessionGrant.revokedKey(gid)));
            }
            return null;
        });

        assertThat(state.hasLive(author, project.id(), Scope.ISSUE, T0)).isFalse();

        long liveId = ctx.tx(() -> {
            long gid = ids.next(Counter.SESSION_GRANT);
            grants.insert(SessionGrant.issue(gid, author, project.id(), Scope.ISSUE,
                    owner, T0.minusSeconds(10), T0.plusSeconds(3600), null));
            return gid;
        });

        assertThat(state.hasLive(author, project.id(), Scope.ISSUE, T0)).isTrue();
        assertThat(state.hasLive(author, project.id(), Scope.ISSUE, T0.plusSeconds(3600))).isFalse();

        Instant usedAt = T0.plusSeconds(5);
        ctx.tx(() -> {
            state.markUsed(author, project.id(), Scope.ISSUE, usedAt);
            return null;
        });
        assertThat(grants.findById(liveId).orElseThrow().lastUsedAt()).isEqualTo(usedAt);
    }

    @Test
    @DisplayName("PERF-DB-03: LinkRepository.relationsOf UNION ALL returns both sides sorted by (a, b, kind)")
    void linkRelationsOfUnionAllMatchesBothEndsInOrder() {
        Project project = seed.project();
        Issue i1 = seed.issue(project, 1, "one", T0);
        Issue i2 = seed.issue(project, 2, "two", T0);
        Issue i3 = seed.issue(project, 3, "three", T0);
        LinkRepository links = ctx.bean(LinkRepository.class);

        ctx.tx(() -> {
            links.add(new IssueRelation(i1.id(), i2.id(), CodeValue.of("same_family"),
                    AuthorId.of("codex:rel")));
            links.add(new IssueRelation(i2.id(), i3.id(), CodeValue.of("same_family"),
                    AuthorId.of("codex:rel")));
            links.add(new IssueRelation(i1.id(), i3.id(), CodeValue.of("same_family"),
                    AuthorId.of("codex:rel")));
            return null;
        });

        List<IssueRelation> mid = links.relationsOf(i2.id());
        assertThat(mid).hasSize(2);
        assertThat(mid.get(0).a()).isEqualTo(i1.id());
        assertThat(mid.get(0).b()).isEqualTo(i2.id());
        assertThat(mid.get(1).a()).isEqualTo(i2.id());
        assertThat(mid.get(1).b()).isEqualTo(i3.id());
    }

    @Test
    @DisplayName("PERF-DB-04: AttentionQueryRepository.counts matches row counts and respects cap")
    void attentionCountsMatchesRowsAndRespectsCap() {
        Project project = seed.project();
        seed.issue(project, 1, "open-no-owner-1", T0);
        seed.issue(project, 2, "open-no-owner-2", T0);
        seed.issue(project, 3, "open-no-owner-3", T0);
        AttentionQueryRepository attention = ctx.bean(AttentionQueryRepository.class);

        Map<AttentionKind, AttentionQueryRepository.BoundedCount> uncapped = attention.counts(project.id(), 10);
        assertThat(uncapped.get(AttentionKind.OPEN_NO_OWNER).count()).isEqualTo(3);
        assertThat(uncapped.get(AttentionKind.OPEN_NO_OWNER).atLeast()).isFalse();

        Map<AttentionKind, AttentionQueryRepository.BoundedCount> capped = attention.counts(project.id(), 2);
        assertThat(capped.get(AttentionKind.OPEN_NO_OWNER).count()).isEqualTo(2);
        assertThat(capped.get(AttentionKind.OPEN_NO_OWNER).atLeast()).isTrue();
    }

    @Test
    @DisplayName("PERF-DB-05: EntityLookupRepository.findAny resolves all four entity types in one round-trip")
    void entityLookupFindAnyResolvesAllFourTypes() {
        Project project = seed.project();
        Issue issue = seed.issue(project, 1, "i", T0);
        Spec spec = seed.spec(project, 1, "s", T0);
        Task task = seed.task(project, 1, "t");
        Criterion criterion = seed.criterion(project, "M1", "C-01", "c");
        EntityLookupRepository lookup = ctx.bean(EntityLookupRepository.class);

        List<EntityLookupRepository.TypedFound> foundList = lookup.findAny(List.of(
                issue.id().value(), spec.id().value(), task.id().value(), criterion.id().value(),
                "I-9999", issue.id().value().toLowerCase(Locale.ROOT)));
        Map<String, HistoryEntity> byId = foundList.stream()
                .collect(Collectors.toMap(tf -> tf.row().id(), EntityLookupRepository.TypedFound::entity));

        assertThat(byId).containsOnlyKeys(issue.id().value(), spec.id().value(),
                task.id().value(), criterion.id().value());
        assertThat(byId.get(issue.id().value())).isEqualTo(HistoryEntity.ISSUE);
        assertThat(byId.get(spec.id().value())).isEqualTo(HistoryEntity.SPEC);
        assertThat(byId.get(task.id().value())).isEqualTo(HistoryEntity.TASK);
        assertThat(byId.get(criterion.id().value())).isEqualTo(HistoryEntity.CRITERION);
    }

    @Test
    @DisplayName("PERF-DB-06: IdAllocator.reserveRange allocates an exact contiguous block")
    void idAllocatorReserveRangeAllocatesContiguousBlock() {
        IdAllocator ids = ctx.bean(IdAllocator.class);
        long first = ctx.tx(() -> ids.reserveRange(Counter.HISTORY, 25));
        long after = ctx.tx(() -> ids.next(Counter.HISTORY));
        assertThat(after).isEqualTo(first + 25);
    }

    @Test
    @DisplayName("PERF-DB-07: CommonCodeRepository cache reflects revision bumps and never caches rolled-back writes")
    void commonCodeCacheCoherenceAcrossRevisionAndRollback() {
        CommonCodeRepository codes = ctx.bean(CommonCodeRepository.class);
        CodeRevisionRepository revisions = ctx.bean(CodeRevisionRepository.class);

        // Warm cache outside transaction
        int initialSize = codes.catalog(CodeGroup.ISSUE_CATEGORY).entries().size();
        assertThat(codes.catalog(CodeGroup.ISSUE_CATEGORY).entries()).hasSize(initialSize);

        // Rolled-back write must not poison cached snapshot
        CommonCode phantom = new CommonCode(CodeGroup.ISSUE_CATEGORY, CodeValue.of("perf_phantom"), "유령", "Phantom",
                "d", "d", 999, CodeState.ACTIVE, CodeDirection.CANONICAL, AuthorId.of("user:owner"));
        try {
            ctx.tx(() -> {
                codes.insert(phantom);
                revisions.bump();
                throw new IllegalStateException("force rollback");
            });
        } catch (IllegalStateException expected) {
            assertThat(expected.getMessage()).isEqualTo("force rollback");
        }
        assertThat(codes.find(CodeGroup.ISSUE_CATEGORY, CodeValue.of("perf_phantom"))).isEmpty();

        // Committed write + revision bump must be visible immediately on next read; clean up in finally for shared schema
        CommonCode committed = new CommonCode(CodeGroup.ISSUE_CATEGORY, CodeValue.of("perf_added"), "추가", "Added",
                "d", "d", 998, CodeState.ACTIVE, CodeDirection.CANONICAL, AuthorId.of("user:owner"));
        try {
            ctx.tx(() -> {
                codes.insert(committed);
                revisions.bump();
                return null;
            });
            assertThat(codes.find(CodeGroup.ISSUE_CATEGORY, CodeValue.of("perf_added"))).isPresent();
            assertThat(codes.catalog(CodeGroup.ISSUE_CATEGORY).entries()).hasSize(initialSize + 1);
        } finally {
            JdbcClient jdbc = ctx.bean(JdbcClient.class);
            ctx.tx(() -> {
                jdbc.sql("DELETE FROM common_code WHERE code_group = 'ISSUE_CATEGORY' AND code = 'perf_added'")
                        .update();
                revisions.bump();
                return null;
            });
        }
    }

    @Test
    @DisplayName("PERF-DB-08: ReceiptCompletionRepository.finishAll batch-updates reserved receipts and reports conflicts")
    void receiptCompletionFinishAllBatchUpdates() {
        ReceiptRepository receipts = ctx.bean(ReceiptRepository.class);
        ReceiptCompletionRepository completion = ctx.bean(ReceiptCompletionRepository.class);
        AuthorId author = AuthorId.of("codex:batch-receipt");
        Sha256 hash = Sha256.ofText("perf-payload");

        List<ReceiptCompletionRepository.FinishedReceipt> items = new ArrayList<>();
        ctx.tx(() -> {
            for (int i = 0; i < 5; i++) {
                OperationKey key = new OperationKey("perf-batch-key-" + i + "-" + UUID.randomUUID());
                receipts.insert(Receipt.reserve(key, ReceiptOperation.SAVE, hash, author, Channel.AUTHOR, T0));
                items.add(new ReceiptCompletionRepository.FinishedReceipt(author, key,
                        new JsonDocument("{\"ok\":true,\"i\":" + i + "}")));
            }
            return null;
        });

        OperationKey firstFailure = ctx.tx(() -> completion.finishAll(items));
        assertThat(firstFailure).isNull();

        JdbcClient jdbc = ctx.bean(JdbcClient.class);
        Integer finished = jdbc.sql("SELECT COUNT(*) FROM request_receipt WHERE author_id = :a AND response IS NOT NULL")
                .param("a", author.value()).query(Integer.class).single();
        assertThat(finished).isEqualTo(5);

        // Re-finishing already completed receipts must fail closed and report the first key
        OperationKey alreadyFinished = ctx.tx(() -> completion.finishAll(items));
        assertThat(alreadyFinished).isEqualTo(items.getFirst().operationKey());
    }

    @Test
    @DisplayName("PERF-DB-09: ReceiptCompletionRepository.finishAll fails closed if batch driver returns SUCCESS_NO_INFO")
    void receiptCompletionFinishAllFailsClosedOnSuccessNoInfo() {
        AuthorId author = AuthorId.of("codex:no-info");
        OperationKey key1 = new OperationKey("perf-noinfo-1-" + UUID.randomUUID());
        OperationKey key2 = new OperationKey("perf-noinfo-2-" + UUID.randomUUID());
        List<ReceiptCompletionRepository.FinishedReceipt> items = List.of(
                new ReceiptCompletionRepository.FinishedReceipt(author, key1, JsonDocument.EMPTY_OBJECT),
                new ReceiptCompletionRepository.FinishedReceipt(author, key2, JsonDocument.EMPTY_OBJECT));
        org.springframework.jdbc.core.JdbcTemplate fakeBatch = new org.springframework.jdbc.core.JdbcTemplate() {
            @Override
            public <T> int[][] batchUpdate(String sql, java.util.Collection<T> batchArgs, int batchSize,
                    org.springframework.jdbc.core.ParameterizedPreparedStatementSetter<T> pss) {
                return new int[][] { { java.sql.Statement.SUCCESS_NO_INFO, java.sql.Statement.SUCCESS_NO_INFO } };
            }
        };
        ReceiptCompletionRepository repo = new ReceiptCompletionRepository(
                ctx.bean(JdbcClient.class), fakeBatch, ctx.bean(org.veridger.database.repository.SqlBuilder.class));
        assertThat(repo.finishAll(items)).isEqualTo(key1);
    }

    @Test
    @DisplayName("PERF-DB-10: SqlBuilder.generateNullText produces dialect-valid CAST(NULL AS ...) across all 7 engines")
    void sqlBuilderGenerateNullTextCoversAllDialects() {
        for (org.veridger.database.repository.SqlBuilder.Database db : org.veridger.database.repository.SqlBuilder.Database.values()) {
            String expr = org.veridger.database.repository.SqlBuilder.generateNullText(db, 120);
            assertThat(expr).startsWith("CAST(NULL AS ").endsWith("(120))");
        }
        assertThat(org.veridger.database.repository.SqlBuilder.generateNullText(
                org.veridger.database.repository.SqlBuilder.Database.MYSQL, 120))
                .isEqualTo("CAST(NULL AS CHAR(120))")
                .doesNotContain("VARCHAR");
        assertThat(org.veridger.database.repository.SqlBuilder.generateNullText(
                org.veridger.database.repository.SqlBuilder.Database.MARIADB, 120))
                .isEqualTo("CAST(NULL AS CHAR(120))");
        assertThat(org.veridger.database.repository.SqlBuilder.generateNullText(
                org.veridger.database.repository.SqlBuilder.Database.ORACLE, 120))
                .isEqualTo("CAST(NULL AS VARCHAR2(120))");
        assertThat(org.veridger.database.repository.SqlBuilder.generateNullText(
                org.veridger.database.repository.SqlBuilder.Database.POSTGRESQL, 120))
                .isEqualTo("CAST(NULL AS VARCHAR(120))");
    }

    @Test
    @DisplayName("PERF-DB-11: HistoryQueryRepository lightweight projections return transitions and newest summaries without loading JSON blobs")
    void historyLightweightProjectionsReturnExactMetadata() {
        var historyRepo = ctx.bean(org.veridger.database.repository.HistoryRepository.class);
        var historyQueries = ctx.bean(org.veridger.database.repository.HistoryQueryRepository.class);
        var ids = ctx.bean(IdAllocator.class);
        String entityId = "spec-proj-" + UUID.randomUUID();
        AuthorId author = AuthorId.of("codex:proj");
        Instant base = Instant.parse("2025-01-01T00:00:00Z");

        long h1 = ctx.tx(() -> {
            long id = ids.next(Counter.HISTORY);
            historyRepo.insert(new org.veridger.domain.model.HistoryEntry(id, base, author, "developer",
                    org.veridger.domain.rule.ActorAssurance.DECLARED, HistoryEntity.SPEC, entityId,
                    org.veridger.domain.rule.HistoryOp.CREATE, null, null, JsonDocument.EMPTY_OBJECT, null,
                    "initial spec create", null, null, null, new OperationKey("h1-" + UUID.randomUUID()), null));
            return id;
        });
        long h2 = ctx.tx(() -> {
            long id = ids.next(Counter.HISTORY);
            historyRepo.insert(new org.veridger.domain.model.HistoryEntry(id, base.plusSeconds(10), author, "developer",
                    org.veridger.domain.rule.ActorAssurance.DECLARED, HistoryEntity.SPEC, entityId,
                    org.veridger.domain.rule.HistoryOp.TRANSITION, "ACTIVE", "COMPLETED", JsonDocument.EMPTY_OBJECT,
                    JsonDocument.EMPTY_OBJECT, "spec completed transition", null, null, null,
                    new OperationKey("h2-" + UUID.randomUUID()), null));
            return id;
        });

        var transitions = historyQueries.transitionsOfEntity(HistoryEntity.SPEC, entityId);
        assertThat(transitions).hasSize(1);
        assertThat(transitions.getFirst().fromState()).isEqualTo("ACTIVE");
        assertThat(transitions.getFirst().toState()).isEqualTo("COMPLETED");
        assertThat(transitions.getFirst().authorId()).isEqualTo(author);

        var summaries = historyQueries.newestSummariesOfEntity(HistoryEntity.SPEC, entityId, false, 5);
        assertThat(summaries).hasSize(2);
        assertThat(summaries.get(0).id()).isEqualTo(h2);
        assertThat(summaries.get(0).op()).isEqualTo(org.veridger.domain.rule.HistoryOp.TRANSITION);
        assertThat(summaries.get(0).reason()).isEqualTo("spec completed transition");
        assertThat(summaries.get(1).id()).isEqualTo(h1);
        assertThat(summaries.get(1).op()).isEqualTo(org.veridger.domain.rule.HistoryOp.CREATE);
    }
}
