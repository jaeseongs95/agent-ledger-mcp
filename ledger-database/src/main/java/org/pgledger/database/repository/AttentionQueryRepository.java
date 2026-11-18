package org.pgledger.database.repository;

import java.util.List;
import org.pgledger.domain.rule.AttentionKind;
import org.pgledger.domain.value.MaxRows;
import org.pgledger.domain.value.ProjectId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The items that need a look, of one project or of all, optionally of one kind, in keyset pages ordered by kind and
 * id ({@code kind > ? OR (kind = ? AND id > ?)}). There are six kinds across issues, specs, and tasks.
 */
@Repository
public class AttentionQueryRepository {

    /** One item: its kind, the id of the issue, spec, or task and its project. */
    public record Item(AttentionKind kind, String id, String projectId) {
    }

    /** Position after the last row of a page. */
    public record Position(AttentionKind kind, String id) {
    }

    /** What each kind means is {@link AttentionSql}, the same text the defect list and the task list filter with. */
    private static final String ITEMS = AttentionSql.items();

    private final JdbcClient jdbc;
    private final SqlBuilder sqlBuilder;

    public AttentionQueryRepository(JdbcClient jdbc, SqlBuilder sqlBuilder) {
        this.jdbc = jdbc;
        this.sqlBuilder = sqlBuilder;
    }

    public KeysetPage<Item> page(ProjectId project, AttentionKind kind, MaxRows maxRows, Position after) {
        StringBuilder sql = new StringBuilder("SELECT kind, id, project_id FROM (").append(ITEMS)
                .append(") a WHERE 1 = 1");
        if (project != null) {
            sql.append(" AND project_id = :project");
        }
        if (kind != null) {
            sql.append(" AND kind = :kind");
        }
        if (after != null) {
            sql.append(" AND (kind > :afterKind OR (kind = :afterKind AND id > :afterId))");
        }
        sql.append(" ORDER BY kind, id");
        // one row more than the page tells that the list continues; the limit is the clause of the actual product
        JdbcClient.StatementSpec spec = jdbc.sql(sqlBuilder.applyLimit(sql.toString(), ":row_count"))
                .param("row_count", maxRows.value() + 1);
        if (project != null) {
            spec = spec.param("project", project.value());
        }
        if (kind != null) {
            spec = spec.param("kind", kind.name());
        }
        if (after != null) {
            spec = spec.param("afterKind", after.kind().name()).param("afterId", after.id());
        }
        List<Item> fetched = spec.query((rs, n) -> new Item(AttentionKind.valueOf(rs.getString("kind")),
                rs.getString("id"), rs.getString("project_id"))).list();
        return KeysetPage.of(fetched, maxRows.value());
    }
}
