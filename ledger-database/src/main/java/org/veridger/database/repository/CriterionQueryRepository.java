// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.database.repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.veridger.domain.rule.CriterionState;
import org.veridger.domain.value.CriterionId;
import org.veridger.domain.value.MaxRows;
import org.veridger.domain.value.ProjectId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Milestone criterion lists ordered by {@code id DESC} in keyset pages ({@code id < ?}).
 */
@Repository
public class CriterionQueryRepository {

    /** Optional filters. */
    public record Filter(CriterionState state, String milestone) {
        public static final Filter NONE = new Filter(null, null);
    }

    /** Columns shown in a milestone criterion list. */
    public record CriterionSummary(CriterionId id, String milestone, String goalLabel, String code,
            String criterionText, CriterionState state, long version) {
    }

    /** Per-milestone criterion counts grouped by state. */
    public record MilestoneRollup(String milestone, long total, long met, long completed, long active, long planned,
            long notMet, long unknown) {
    }

    /** Linked issue, specification, and task counts for one criterion. */
    public record LinkCounts(long issues, long specs, long tasks) {
        public static final LinkCounts ZERO = new LinkCounts(0L, 0L, 0L);
    }

    private static final int MAX_IN_CLAUSE = 1000;

    private final JdbcClient jdbc;
    private final SqlBuilder sqlBuilder;

    public CriterionQueryRepository(JdbcClient jdbc, SqlBuilder sqlBuilder) {
        this.jdbc = jdbc;
        this.sqlBuilder = sqlBuilder;
    }

    public KeysetPage<CriterionSummary> page(ProjectId project, Filter filter, MaxRows maxRows, CriterionId after) {
        StringBuilder sql = new StringBuilder("SELECT id, milestone_name, goal_label, code, criterion_text, state, "
                + "version FROM milestone_criterion WHERE project_id = :project");
        if (filter.state() != null) {
            sql.append(" AND state = :state");
        }
        if (filter.milestone() != null) {
            sql.append(" AND milestone_name = :milestone");
        }
        if (after != null) {
            sql.append(" AND id < :after");
        }
        sql.append(" ORDER BY id DESC");
        JdbcClient.StatementSpec spec = jdbc.sql(sqlBuilder.applyLimit(sql.toString(), ":row_count"))
                .param("project", project.value()).param("row_count", maxRows.value() + 1);
        if (filter.state() != null) {
            spec = spec.param("state", filter.state().name());
        }
        if (filter.milestone() != null) {
            spec = spec.param("milestone", filter.milestone());
        }
        if (after != null) {
            spec = spec.param("after", after.value());
        }
        List<CriterionSummary> fetched = spec.query((rs, n) -> new CriterionSummary(
                CriterionId.of(rs.getString("id")), rs.getString("milestone_name"), rs.getString("goal_label"),
                rs.getString("code"), rs.getString("criterion_text"), CriterionState.valueOf(rs.getString("state")),
                rs.getLong("version"))).list();
        return KeysetPage.of(fetched, maxRows.value());
    }

    /**
     * Per-milestone readiness rollups for a project, ordered alphabetically by milestone name.
     */
    public List<MilestoneRollup> milestoneRollups(ProjectId project) {
        Map<String, long[]> buckets = new LinkedHashMap<>();
        jdbc.sql("SELECT milestone_name, state, COUNT(*) AS cnt FROM milestone_criterion "
                + "WHERE project_id = :project GROUP BY milestone_name, state ORDER BY milestone_name, state")
                .param("project", project.value())
                .query(rs -> {
                    String milestone = rs.getString("milestone_name");
                    CriterionState state = CriterionState.valueOf(rs.getString("state"));
                    long count = rs.getLong("cnt");
                    long[] counts = buckets.computeIfAbsent(milestone, k -> new long[6]);
                    int idx = switch (state) {
                        case MET -> 0;
                        case COMPLETED -> 1;
                        case ACTIVE -> 2;
                        case PLANNED -> 3;
                        case NOT_MET -> 4;
                        case UNKNOWN -> 5;
                    };
                    counts[idx] += count;
                });
        List<MilestoneRollup> out = new ArrayList<>(buckets.size());
        for (Map.Entry<String, long[]> entry : buckets.entrySet()) {
            long[] c = entry.getValue();
            long total = c[0] + c[1] + c[2] + c[3] + c[4] + c[5];
            out.add(new MilestoneRollup(entry.getKey(), total, c[0], c[1], c[2], c[3], c[4], c[5]));
        }
        return out;
    }

    /**
     * Batched counts of linked issues, specifications, and tasks for a page of criterion ids.
     */
    public Map<String, LinkCounts> linkCounts(Collection<String> criterionIds) {
        if (criterionIds == null || criterionIds.isEmpty()) {
            return Map.of();
        }
        List<String> distinct = new ArrayList<>(new LinkedHashSet<>(criterionIds));
        Set<String> asked = new HashSet<>(distinct);
        Map<String, LinkCounts> out = new HashMap<>();
        for (int from = 0; from < distinct.size(); from += MAX_IN_CLAUSE) {
            List<String> batch = distinct.subList(from, Math.min(from + MAX_IN_CLAUSE, distinct.size()));
            jdbc.sql("SELECT criterion_id, COUNT(issue_id) AS issue_cnt, COUNT(spec_id) AS spec_cnt, "
                    + "COUNT(task_id) AS task_cnt FROM criterion_link WHERE criterion_id IN (:ids) "
                    + "GROUP BY criterion_id")
                    .param("ids", batch)
                    .query(rs -> {
                        String id = rs.getString("criterion_id");
                        if (asked.contains(id)) {
                            out.put(id, new LinkCounts(rs.getLong("issue_cnt"), rs.getLong("spec_cnt"),
                                    rs.getLong("task_cnt")));
                        }
                    });
        }
        return out;
    }
}
