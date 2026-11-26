// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.database.repository;

import java.util.List;
import org.agentledger.domain.rule.CriterionState;
import org.agentledger.domain.value.CriterionId;
import org.agentledger.domain.value.MaxRows;
import org.agentledger.domain.value.ProjectId;
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
}
