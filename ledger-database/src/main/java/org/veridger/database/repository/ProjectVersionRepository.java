// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.database.repository;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.veridger.domain.rule.HistoryEntity;

@Repository
public class ProjectVersionRepository {

    public record Entry(String projectId, String id, String name, String description) {}

    public record Stored(String id, String name, String description) {}

    public record Item(String id, String title) {}

    private final JdbcClient jdbc;
    private final SqlBuilder sql;

    public ProjectVersionRepository(JdbcClient jdbc, SqlBuilder sql) {
        this.jdbc = jdbc;
        this.sql = sql;
    }

    public List<Entry> list(List<String> projects) {
        if (projects.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT project_id, id, name, description FROM project_version
                WHERE project_id IN (:projects) ORDER BY project_id, name
                """).param("projects", projects)
                .query((rs, row) -> new Entry(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4))).list();
    }

    public List<String> namesForIssue(String project, String issue) {
        return namesForIssues(project, List.of(issue)).getOrDefault(issue, List.of());
    }

    public Map<String, List<String>> namesForIssues(String project, List<String> issues) {
        if (issues.isEmpty()) {
            return Map.of();
        }
        return jdbc.sql("""
                SELECT ipv.item_id, pv.name FROM project_version pv
                JOIN project_version_item ipv ON ipv.project_id = pv.project_id AND ipv.version_id = pv.id
                WHERE ipv.project_id = :project AND ipv.item_kind = 'issue' AND ipv.item_id IN (:issues)
                ORDER BY pv.name
                """).param("project", project).param("issues", issues)
                .query((rs, row) -> Map.entry(rs.getString(1), rs.getString(2))).list().stream()
                .collect(Collectors.groupingBy(Map.Entry::getKey, Collectors.mapping(Map.Entry::getValue,
                        Collectors.toList())));
    }

    public Optional<Stored> find(String project, String name) {
        return jdbc.sql("SELECT id, name, description FROM project_version WHERE project_id = :project AND name = :name")
                .param("project", project).param("name", name)
                .query((rs, row) -> new Stored(rs.getString(1), rs.getString(2), rs.getString(3))).list().stream()
                .filter(row -> Objects.equals(row.name(), name)).findFirst();
    }

    public Optional<Stored> findById(String project, String id) {
        return jdbc.sql("SELECT id, name, description FROM project_version WHERE project_id = :project AND id = :id")
                .param("project", project).param("id", id)
                .query((rs, row) -> new Stored(rs.getString(1), rs.getString(2), rs.getString(3))).optional();
    }

    public List<Stored> versionsForItem(String project, HistoryEntity kind, String item) {
        return jdbc.sql("""
                SELECT pv.id, pv.name, pv.description FROM project_version pv
                JOIN project_version_item ref ON ref.project_id = pv.project_id AND ref.version_id = pv.id
                WHERE ref.project_id = :project AND ref.item_kind = :kind AND ref.item_id = :item
                ORDER BY pv.name
                """).param("project", project).param("kind", kind.token()).param("item", item)
                .query((rs, row) -> new Stored(rs.getString(1), rs.getString(2), rs.getString(3))).list();
    }

    public void insert(String project, String id, String name, String description) {
        jdbc.sql("""
                INSERT INTO project_version (project_id, id, name, description)
                VALUES (:project, :id, :name, :description)
                """).param("project", project).param("id", id).param("name", name)
                .param("description", description).update();
    }

    public void update(String project, String id, String name, String description) {
        jdbc.sql("""
                UPDATE project_version SET name = :name, description = :description
                WHERE project_id = :project AND id = :id
                """).param("project", project).param("id", id).param("name", name)
                .param("description", description).update();
    }

    public boolean link(String project, String issue, String versionId) {
        return linkItem(project, HistoryEntity.ISSUE, issue, versionId);
    }

    public boolean linkItem(String project, HistoryEntity entity, String item, String versionId) {
        return jdbc.sql("""
                INSERT INTO project_version_item (project_id, item_kind, item_id, version_id)
                SELECT :project, :kind, :item, :version WHERE NOT EXISTS (
                  SELECT 1 FROM project_version_item
                  WHERE project_id = :project AND item_kind = :kind AND item_id = :item AND version_id = :version
                )
                """).param("project", project).param("kind", entity.token()).param("item", item)
                .param("version", versionId).update() != 0;
    }

    public boolean unlink(String project, String issue, String versionId) {
        return unlinkItem(project, HistoryEntity.ISSUE, issue, versionId);
    }

    public boolean unlinkItem(String project, HistoryEntity entity, String item, String versionId) {
        return jdbc.sql("""
                DELETE FROM project_version_item
                WHERE project_id = :project AND item_kind = :kind AND item_id = :item AND version_id = :version
                """).param("project", project).param("kind", entity.token()).param("item", item)
                .param("version", versionId).update() != 0;
    }

    public List<Item> items(String project, String version, HistoryEntity kind, String after, int limit) {
        String table = switch (kind) {
            case ISSUE -> "issue";
            case SPEC -> "spec";
            case TASK -> "task";
            case CRITERION -> "milestone_criterion";
            default -> throw new IllegalArgumentException("Unsupported project version item");
        };
        String title = kind == HistoryEntity.CRITERION ? "code" : "title";
        String query = """
                SELECT item.id, item.%s FROM %s item
                JOIN project_version_item ref ON ref.project_id = item.project_id AND ref.item_id = item.id
                WHERE ref.project_id = :project AND ref.version_id = :version AND ref.item_kind = :kind
                """.formatted(title, table);
        var statement = jdbc.sql(sql.applyLimit(query + (after == null ? "" : " AND item.id > :after")
                + " ORDER BY item.id", ":row_count")).param("project", project).param("version", version)
                .param("kind", kind.token()).param("row_count", limit);
        if (after != null) {
            statement.param("after", after);
        }
        return statement.query((rs, row) -> new Item(rs.getString(1), rs.getString(2))).list();
    }
}
