// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.database.repository;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ProjectVersionRepository {

    public record Entry(String projectId, String name, String description) {}

    public record Stored(String id, String name, String description) {}

    private final JdbcClient jdbc;

    public ProjectVersionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Entry> list(List<String> projects) {
        if (projects.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT project_id, name, description FROM project_version
                WHERE project_id IN (:projects) ORDER BY project_id, name
                """).param("projects", projects)
                .query((rs, row) -> new Entry(rs.getString(1), rs.getString(2), rs.getString(3))).list();
    }

    public List<String> namesForIssue(String project, String issue) {
        return namesForIssues(project, List.of(issue)).getOrDefault(issue, List.of());
    }

    public Map<String, List<String>> namesForIssues(String project, List<String> issues) {
        if (issues.isEmpty()) {
            return Map.of();
        }
        return jdbc.sql("""
                SELECT ipv.issue_id, pv.name FROM project_version pv
                JOIN issue_project_version ipv ON ipv.project_id = pv.project_id AND ipv.version_id = pv.id
                WHERE ipv.project_id = :project AND ipv.issue_id IN (:issues) ORDER BY pv.name
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
        return jdbc.sql("""
                INSERT INTO issue_project_version (project_id, issue_id, version_id)
                SELECT :project, :issue, :version WHERE NOT EXISTS (
                  SELECT 1 FROM issue_project_version
                  WHERE project_id = :project AND issue_id = :issue AND version_id = :version
                )
                """).param("project", project).param("issue", issue).param("version", versionId).update() != 0;
    }

    public boolean unlink(String project, String issue, String versionId) {
        return jdbc.sql("""
                DELETE FROM issue_project_version
                WHERE project_id = :project AND issue_id = :issue AND version_id = :version
                """).param("project", project).param("issue", issue).param("version", versionId).update() != 0;
    }
}
