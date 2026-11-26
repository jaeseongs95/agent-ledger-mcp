// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.database.repository;

import java.time.Instant;
import java.util.List;
import org.agentledger.database.time.UtcTime;
import org.agentledger.domain.rule.AttentionKind;
import org.agentledger.domain.rule.HistoryEntity;
import org.agentledger.domain.rule.TaskState;
import org.agentledger.domain.value.MaxRows;
import org.agentledger.domain.value.ProjectId;
import org.agentledger.domain.value.TaskId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Task lists, newest first, in keyset pages ({@code created_at < ? OR (created_at = ? AND id < ?)}); the limit is
 * {@code max + 1} rows in the row-limit clause of the actual product ({@link SqlBuilder}). Values are bind parameters,
 * the SQL is built from fixed fragments.
 */
@Repository
public class TaskQueryRepository {

    /** Optional filters; {@code owner} is the exact owner id, {@code attention} one of the two task kinds. */
    public record Filter(TaskState state, String owner, AttentionKind attention) {
        public static final Filter NONE = new Filter(null, null, null);
    }

    /** The columns a task list shows. */
    public record TaskSummary(TaskId id, String title, TaskState state, String ownerActorId, long version,
            Instant createdAt, Instant updatedAt) {
    }

    /** Position after the last row of a page. */
    public record Position(Instant createdAt, TaskId id) {
    }

    private final JdbcClient jdbc;
    private final SqlBuilder sqlBuilder;

    public TaskQueryRepository(JdbcClient jdbc, SqlBuilder sqlBuilder) {
        this.jdbc = jdbc;
        this.sqlBuilder = sqlBuilder;
    }

    public KeysetPage<TaskSummary> page(ProjectId project, Filter filter, MaxRows maxRows, Position after) {
        StringBuilder sql = new StringBuilder("SELECT id, title, state, owner_actor_id, version, created_at, "
                + "updated_at FROM task WHERE project_id = :project");
        if (filter.state() != null) {
            sql.append(" AND state = :state");
        }
        if (filter.owner() != null) {
            sql.append(" AND owner_actor_id = :owner");
        }
        if (filter.attention() != null) {
            sql.append(" AND ").append(AttentionSql.condition(filter.attention(), HistoryEntity.TASK));
        }
        if (after != null) {
            sql.append(" AND (created_at < :after OR (created_at = :after AND id < :afterId))");
        }
        sql.append(" ORDER BY created_at DESC, id DESC");
        JdbcClient.StatementSpec spec = jdbc.sql(sqlBuilder.applyLimit(sql.toString(), ":row_count"))
                .param("project", project.value()).param("row_count", maxRows.value() + 1);
        if (filter.state() != null) {
            spec = spec.param("state", filter.state().name());
        }
        if (filter.owner() != null) {
            spec = spec.param("owner", filter.owner());
        }
        if (after != null) {
            spec = spec.param("after", UtcTime.toDb(after.createdAt())).param("afterId", after.id().value());
        }
        List<TaskSummary> fetched = spec.query((rs, n) -> new TaskSummary(TaskId.of(rs.getString("id")),
                rs.getString("title"), TaskState.valueOf(rs.getString("state")), rs.getString("owner_actor_id"),
                rs.getLong("version"), UtcTime.read(rs, "created_at"), UtcTime.read(rs, "updated_at"))).list();
        return KeysetPage.of(fetched, maxRows.value());
    }
}
