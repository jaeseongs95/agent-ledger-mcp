// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.database.repository;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.veridger.domain.rule.HistoryEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Finds defects, tasks and conditions by id text. The ids are bound as they come: an id that is no valid id of a
 * kind simply finds nothing, so a caller may ask for a mixed list without parsing it first.
 */
@Repository
public class EntityLookupRepository {

    /** The columns every kind has in common; {@code mergedInto} is only set for a merged defect. */
    public record Found(String id, String projectId, String state, long version, String mergedInto) {
    }

    /** A row matched across any of the four primary entity tables in a single round-trip. */
    public record TypedFound(HistoryEntity entity, Found row) {
    }

    private final JdbcClient jdbc;
    private final SqlBuilder sqlBuilder;

    public EntityLookupRepository(JdbcClient jdbc, SqlBuilder sqlBuilder) {
        this.jdbc = jdbc;
        this.sqlBuilder = sqlBuilder;
    }

    /**
     * Exact case-sensitive lookup across issues, specifications, tasks, and milestone criteria in one SQL round-trip.
     */
    public List<TypedFound> findAny(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        Set<String> asked = new HashSet<>(ids);
        String nullMerged = sqlBuilder.nullText(120);
        return jdbc.sql("SELECT 'ISSUE' AS entity, id, project_id, status AS state, version, merged_into "
                + "FROM issue WHERE id IN (:ids) "
                + "UNION ALL "
                + "SELECT 'SPEC' AS entity, id, project_id, status AS state, version, " + nullMerged + " AS merged_into "
                + "FROM spec WHERE id IN (:ids) "
                + "UNION ALL "
                + "SELECT 'TASK' AS entity, id, project_id, state, version, " + nullMerged + " AS merged_into "
                + "FROM task WHERE id IN (:ids) "
                + "UNION ALL "
                + "SELECT 'CRITERION' AS entity, id, project_id, state, version, " + nullMerged + " AS merged_into "
                + "FROM milestone_criterion WHERE id IN (:ids)")
                .param("ids", ids)
                .query((rs, n) -> new TypedFound(HistoryEntity.valueOf(rs.getString("entity")),
                        new Found(rs.getString("id"), rs.getString("project_id"), rs.getString("state"),
                                rs.getLong("version"), rs.getString("merged_into"))))
                .list()
                .stream()
                .filter(tf -> asked.contains(tf.row().id()))
                .toList();
    }

    /** Exact case-sensitive lookup of tasks with owner actor id, used by detail views without N+1 queries. */
    public record TaskSummaryRow(String id, String state, String ownerActorId) {
    }

    public List<TaskSummaryRow> taskSummaries(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        Set<String> asked = new HashSet<>(ids);
        return jdbc.sql("SELECT id, state, owner_actor_id FROM task WHERE id IN (:ids) ORDER BY id")
                .param("ids", ids)
                .query((rs, n) -> new TaskSummaryRow(rs.getString("id"), rs.getString("state"),
                        rs.getString("owner_actor_id")))
                .list()
                .stream()
                .filter(row -> asked.contains(row.id()))
                .toList();
    }

    /** Exact case-sensitive lookup of issues by id. */
    public List<Found> issues(Collection<String> ids) {
        return exactly(ids, jdbc.sql("SELECT id, project_id, status, version, merged_into FROM issue WHERE id IN (:ids) "
                + "ORDER BY id").param("ids", ids)
                .query((rs, n) -> new Found(rs.getString("id"), rs.getString("project_id"), rs.getString("status"),
                        rs.getLong("version"), rs.getString("merged_into")))
                .list());
    }

    /** Exact case-sensitive lookup of specifications by id. */
    public List<Found> specs(Collection<String> ids) {
        return exactly(ids, jdbc.sql("SELECT id, project_id, status, version FROM spec WHERE id IN (:ids) ORDER BY id")
                .param("ids", ids)
                .query((rs, n) -> new Found(rs.getString("id"), rs.getString("project_id"), rs.getString("status"),
                        rs.getLong("version"), null))
                .list());
    }

    /**
     * Exact case-sensitive lookup of tasks by id.
     */
    public List<Found> tasks(Collection<String> ids) {
        return exactly(ids, jdbc.sql("SELECT id, project_id, state, version FROM task WHERE id IN (:ids) ORDER BY id")
                .param("ids", ids)
                .query((rs, n) -> new Found(rs.getString("id"), rs.getString("project_id"), rs.getString("state"),
                        rs.getLong("version"), null))
                .list());
    }

    /** Exact case-sensitive lookup of milestone criteria by id. */
    public List<Found> criteria(Collection<String> ids) {
        return exactly(ids, jdbc.sql("SELECT id, project_id, state, version FROM milestone_criterion WHERE id IN (:ids) "
                + "ORDER BY id").param("ids", ids)
                .query((rs, n) -> new Found(rs.getString("id"), rs.getString("project_id"), rs.getString("state"),
                        rs.getLong("version"), null))
                .list());
    }

    private static List<Found> exactly(Collection<String> ids, List<Found> fetched) {
        Set<String> asked = new HashSet<>(ids);
        return fetched.stream().filter(found -> asked.contains(found.id())).toList();
    }
}
