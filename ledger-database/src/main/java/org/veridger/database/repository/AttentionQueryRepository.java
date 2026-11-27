// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.database.repository;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.veridger.domain.rule.AttentionKind;
import org.veridger.domain.value.MaxRows;
import org.veridger.domain.value.ProjectId;
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

    /** Bounded count for one attention kind without hydrating full result rows. */
    public record BoundedCount(int count, boolean atLeast) {
    }

    private final JdbcClient jdbc;
    private final SqlBuilder sqlBuilder;

    public AttentionQueryRepository(JdbcClient jdbc, SqlBuilder sqlBuilder) {
        this.jdbc = jdbc;
        this.sqlBuilder = sqlBuilder;
    }

    public KeysetPage<Item> page(ProjectId project, AttentionKind kind, MaxRows maxRows, Position after) {
        boolean hasProject = project != null;
        if (kind != null) {
            // Single-kind path: query only the matching entity table with pushed project and keyset predicates
            boolean bindAfterId = false;
            if (after != null) {
                int cmp = kind.name().compareTo(after.kind().name());
                if (cmp < 0) {
                    return new KeysetPage<>(List.of(), false);
                }
                bindAfterId = (cmp == 0);
            }
            String sql = AttentionSql.branch(kind, hasProject, bindAfterId) + " ORDER BY id";
            JdbcClient.StatementSpec spec = jdbc.sql(sqlBuilder.applyLimit(sql, ":row_count"))
                    .param("row_count", maxRows.value() + 1);
            if (hasProject) {
                spec = spec.param("project", project.value());
            }
            if (bindAfterId) {
                spec = spec.param("afterId", after.id());
            }
            List<Item> fetched = spec.query((rs, n) -> new Item(AttentionKind.valueOf(rs.getString("kind")),
                    rs.getString("id"), rs.getString("project_id"))).list();
            return KeysetPage.of(fetched, maxRows.value());
        }
        List<String> branches = new ArrayList<>();
        boolean bindAfterId = false;
        for (AttentionKind candidate : AttentionKind.values()) {
            if (after != null) {
                int cmp = candidate.name().compareTo(after.kind().name());
                if (cmp < 0) {
                    continue;
                }
                if (cmp == 0) {
                    branches.add(AttentionSql.branch(candidate, hasProject, true));
                    bindAfterId = true;
                    continue;
                }
            }
            branches.add(AttentionSql.branch(candidate, hasProject, false));
        }
        if (branches.isEmpty()) {
            return new KeysetPage<>(List.of(), false);
        }
        String sql = "SELECT kind, id, project_id FROM (" + String.join(" UNION ALL ", branches)
                + ") a ORDER BY kind, id";
        JdbcClient.StatementSpec spec = jdbc.sql(sqlBuilder.applyLimit(sql, ":row_count"))
                .param("row_count", maxRows.value() + 1);
        if (hasProject) {
            spec = spec.param("project", project.value());
        }
        if (bindAfterId) {
            spec = spec.param("afterId", after.id());
        }
        List<Item> fetched = spec.query((rs, n) -> new Item(AttentionKind.valueOf(rs.getString("kind")),
                rs.getString("id"), rs.getString("project_id"))).list();
        return KeysetPage.of(fetched, maxRows.value());
    }

    /**
     * Counts each attention kind up to {@code cap} without hydrating rows or building cursors. Each branch runs a
     * bounded single-table count limited to {@code cap + 1} rows so large tables remain O(cap).
     */
    public Map<AttentionKind, BoundedCount> counts(ProjectId project, int cap) {
        if (cap < 1) {
            throw new IllegalArgumentException("cap must be at least 1");
        }
        boolean hasProject = project != null;
        Map<AttentionKind, BoundedCount> result = new EnumMap<>(AttentionKind.class);
        for (AttentionKind kind : AttentionKind.values()) {
            JdbcClient.StatementSpec spec = jdbc.sql(AttentionSql.countLimited(sqlBuilder, kind, hasProject))
                    .param("row_count", cap + 1);
            if (hasProject) {
                spec = spec.param("project", project.value());
            }
            long fetched = spec.query(Long.class).single();
            boolean truncated = fetched > cap;
            result.put(kind, new BoundedCount((int) Math.min(fetched, cap), truncated));
        }
        return result;
    }
}
