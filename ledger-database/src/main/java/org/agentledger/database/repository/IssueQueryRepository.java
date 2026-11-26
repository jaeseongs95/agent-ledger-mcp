// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.database.repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.agentledger.database.time.UtcTime;
import org.agentledger.domain.rule.AttentionKind;
import org.agentledger.domain.rule.HistoryEntity;
import org.agentledger.domain.rule.IssueStatus;
import org.agentledger.domain.value.CodeValue;
import org.agentledger.domain.value.IssueId;
import org.agentledger.domain.value.MaxRows;
import org.agentledger.domain.value.ProjectId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Issue lists and project-scoped keyword searches, in keyset pages ({@code created_at`/`id} comparisons).
 */
@Repository
public class IssueQueryRepository {

    /** Optional filters; {@code owner} matches explicit owner or the latest deliverable recorder. */
    public record Filter(IssueStatus status, CodeValue severity, CodeValue category, Instant createdFrom,
            Instant createdTo, String owner, AttentionKind attention) {
        public static final Filter NONE = new Filter(null, null, null, null, null, null, null);
    }

    /** Sort order for issue lists. */
    public enum Order {
        NEWEST_FIRST, OLDEST_FIRST
    }

    /** Position after the last row of a page. */
    public record Position(Instant createdAt, IssueId id) {
    }

    private static final String EFFECTIVE_OWNER = "COALESCE(owner_actor_id, "
            + "(SELECT d.recorded_by FROM deliverable d WHERE d.id = "
            + "(SELECT MAX(d2.id) FROM deliverable d2 WHERE d2.target_entity = 'ISSUE' AND d2.target_id = issue.id)))";

    private final JdbcClient jdbc;
    private final SqlBuilder sqlBuilder;

    public IssueQueryRepository(JdbcClient jdbc, SqlBuilder sqlBuilder) {
        this.jdbc = jdbc;
        this.sqlBuilder = sqlBuilder;
    }

    public KeysetPage<IssueSummary> page(ProjectId project, Filter filter, MaxRows maxRows, Order order,
            Position after) {
        StringBuilder sql = new StringBuilder("SELECT id, title, category, severity, status, merged_into, "
                + EFFECTIVE_OWNER + " AS effective_owner, version, created_at, updated_at "
                + "FROM issue WHERE project_id = :project");
        if (filter.status() != null) {
            sql.append(" AND status = :status");
        }
        if (filter.severity() != null) {
            sql.append(" AND severity = :severity");
        }
        if (filter.category() != null) {
            sql.append(" AND category = :category");
        }
        if (filter.createdFrom() != null) {
            sql.append(" AND created_at >= :from");
        }
        if (filter.createdTo() != null) {
            sql.append(" AND created_at <= :to");
        }
        if (filter.owner() != null) {
            sql.append(" AND ").append(EFFECTIVE_OWNER).append(" = :owner");
        }
        if (filter.attention() != null) {
            sql.append(" AND ").append(AttentionSql.condition(filter.attention(), HistoryEntity.ISSUE));
        }
        boolean newest = order == Order.NEWEST_FIRST;
        if (after != null) {
            String cmp = newest ? "<" : ">";
            sql.append(" AND (created_at ").append(cmp).append(" :after OR (created_at = :after AND id ")
                    .append(cmp).append(" :afterId))");
        }
        sql.append(newest ? " ORDER BY created_at DESC, id DESC" : " ORDER BY created_at ASC, id ASC");
        JdbcClient.StatementSpec spec = jdbc.sql(sqlBuilder.applyLimit(sql.toString(), ":row_count"))
                .param("project", project.value()).param("row_count", maxRows.value() + 1);
        if (filter.status() != null) {
            spec = spec.param("status", filter.status().name());
        }
        if (filter.severity() != null) {
            spec = spec.param("severity", filter.severity().value());
        }
        if (filter.category() != null) {
            spec = spec.param("category", filter.category().value());
        }
        if (filter.createdFrom() != null) {
            spec = spec.param("from", UtcTime.toDb(filter.createdFrom()));
        }
        if (filter.createdTo() != null) {
            spec = spec.param("to", UtcTime.toDb(filter.createdTo()));
        }
        if (filter.owner() != null) {
            spec = spec.param("owner", filter.owner());
        }
        if (after != null) {
            spec = spec.param("after", UtcTime.toDb(after.createdAt())).param("afterId", after.id().value());
        }
        return readPage(spec, maxRows);
    }

    public KeysetPage<IssueSummary> search(ProjectId project, List<String> keywords, KeywordMatch match,
            MaxRows maxRows) {
        if (keywords == null || keywords.isEmpty()) {
            throw new IllegalArgumentException("keywords must not be empty");
        }
        List<String> terms = new ArrayList<>();
        for (int i = 0; i < keywords.size(); i++) {
            terms.add("(LOWER(title) LIKE LOWER(:k" + i + ") ESCAPE '\\' "
                    + "OR LOWER(symptom) LIKE LOWER(:k" + i + ") ESCAPE '\\' "
                    + "OR LOWER(root_cause) LIKE LOWER(:k" + i + ") ESCAPE '\\')");
        }
        String joined = String.join(match == KeywordMatch.ALL ? " AND " : " OR ", terms);
        String sql = "SELECT id, title, category, severity, status, merged_into, " + EFFECTIVE_OWNER
                + " AS effective_owner, version, created_at, updated_at FROM issue WHERE project_id = :project AND ("
                + joined + ") ORDER BY created_at DESC, id DESC";
        JdbcClient.StatementSpec spec = jdbc.sql(sqlBuilder.applyLimit(sql, ":row_count"))
                .param("project", project.value()).param("row_count", maxRows.value() + 1);
        for (int i = 0; i < keywords.size(); i++) {
            spec = spec.param("k" + i, LikePattern.contains(keywords.get(i)));
        }
        return readPage(spec, maxRows);
    }

    private static KeysetPage<IssueSummary> readPage(JdbcClient.StatementSpec spec, MaxRows maxRows) {
        List<IssueSummary> fetched = spec.query((rs, n) -> {
            String merged = rs.getString("merged_into");
            return new IssueSummary(IssueId.of(rs.getString("id")), rs.getString("title"),
                    CodeValue.of(rs.getString("category")), CodeValue.of(rs.getString("severity")),
                    IssueStatus.valueOf(rs.getString("status")), merged == null ? null : IssueId.of(merged),
                    rs.getString("effective_owner"), rs.getLong("version"), UtcTime.read(rs, "created_at"),
                    UtcTime.read(rs, "updated_at"));
        }).list();
        return KeysetPage.of(fetched, maxRows.value());
    }
}
