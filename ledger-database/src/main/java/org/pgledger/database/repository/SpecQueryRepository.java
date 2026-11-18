package org.pgledger.database.repository;

import java.time.Instant;
import java.util.List;
import org.pgledger.database.time.UtcTime;
import org.pgledger.domain.rule.AttentionKind;
import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.rule.SpecStatus;
import org.pgledger.domain.value.MaxRows;
import org.pgledger.domain.value.ProjectId;
import org.pgledger.domain.value.SpecId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Specification lists, newest first, in keyset pages ({@code created_at < ? OR (created_at = ? AND id < ?)}).
 */
@Repository
public class SpecQueryRepository {

    /** Optional filters; {@code owner} is the exact owner id, {@code attention} a spec attention kind. */
    public record Filter(SpecStatus status, SpecId parentId, String priority, String owner, AttentionKind attention) {
        public static final Filter NONE = new Filter(null, null, null, null, null);
    }

    /** Position after the last row of a page. */
    public record Position(Instant createdAt, SpecId id) {
    }

    private final JdbcClient jdbc;
    private final SqlBuilder sqlBuilder;

    public SpecQueryRepository(JdbcClient jdbc, SqlBuilder sqlBuilder) {
        this.jdbc = jdbc;
        this.sqlBuilder = sqlBuilder;
    }

    public KeysetPage<SpecSummary> page(ProjectId project, Filter filter, MaxRows maxRows, Position after) {
        StringBuilder sql = new StringBuilder("SELECT id, parent_id, title, status, priority, owner_actor_id, "
                + "version, created_at, updated_at FROM spec WHERE project_id = :project");
        if (filter.status() != null) {
            sql.append(" AND status = :status");
        }
        if (filter.parentId() != null) {
            sql.append(" AND parent_id = :parentId");
        }
        if (filter.priority() != null) {
            sql.append(" AND priority = :priority");
        }
        if (filter.owner() != null) {
            sql.append(" AND owner_actor_id = :owner");
        }
        if (filter.attention() != null) {
            sql.append(" AND ").append(AttentionSql.condition(filter.attention(), HistoryEntity.SPEC));
        }
        if (after != null) {
            sql.append(" AND (created_at < :after OR (created_at = :after AND id < :afterId))");
        }
        sql.append(" ORDER BY created_at DESC, id DESC");
        JdbcClient.StatementSpec stmt = jdbc.sql(sqlBuilder.applyLimit(sql.toString(), ":row_count"))
                .param("project", project.value()).param("row_count", maxRows.value() + 1);
        if (filter.status() != null) {
            stmt = stmt.param("status", filter.status().name());
        }
        if (filter.parentId() != null) {
            stmt = stmt.param("parentId", filter.parentId().value());
        }
        if (filter.priority() != null) {
            stmt = stmt.param("priority", filter.priority());
        }
        if (filter.owner() != null) {
            stmt = stmt.param("owner", filter.owner());
        }
        if (after != null) {
            stmt = stmt.param("after", UtcTime.toDb(after.createdAt())).param("afterId", after.id().value());
        }
        List<SpecSummary> fetched = stmt.query((rs, n) -> {
            String rawParent = rs.getString("parent_id");
            return new SpecSummary(SpecId.of(rs.getString("id")), rawParent == null ? null : SpecId.of(rawParent),
                    rs.getString("title"), SpecStatus.valueOf(rs.getString("status")), rs.getString("priority"),
                    rs.getString("owner_actor_id"), rs.getLong("version"), UtcTime.read(rs, "created_at"),
                    UtcTime.read(rs, "updated_at"));
        }).list();
        return KeysetPage.of(fetched, maxRows.value());
    }
}
