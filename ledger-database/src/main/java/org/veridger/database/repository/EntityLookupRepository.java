// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.database.repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.veridger.database.time.UtcTime;
import org.veridger.domain.rule.HistoryEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Finds defects, tasks and conditions by id text. The ids are bound as they come in portable chunks (at most 500 per
 * query so 4-branch {@code UNION ALL} queries stay within SQL Server's 2,100-parameter limit and Oracle's 1,000-item
 * {@code IN} limit).
 */
@Repository
public class EntityLookupRepository {

    private static final int IN_CHUNK_SIZE = 500;

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
     * Exact case-sensitive lookup across issues, specifications, tasks, and milestone criteria in chunked SQL queries.
     */
    private static final String FIND_ANY_VARCHAR = "SELECT 'ISSUE' AS entity, id, project_id, status AS state, version, merged_into "
            + "FROM issue WHERE id IN (:ids) "
            + "UNION ALL "
            + "SELECT 'SPEC' AS entity, id, project_id, status AS state, version, CAST(NULL AS VARCHAR(120)) AS merged_into "
            + "FROM spec WHERE id IN (:ids) "
            + "UNION ALL "
            + "SELECT 'TASK' AS entity, id, project_id, state, version, CAST(NULL AS VARCHAR(120)) AS merged_into "
            + "FROM task WHERE id IN (:ids) "
            + "UNION ALL "
            + "SELECT 'CRITERION' AS entity, id, project_id, state, version, CAST(NULL AS VARCHAR(120)) AS merged_into "
            + "FROM milestone_criterion WHERE id IN (:ids)";
    private static final String FIND_ANY_CHAR = "SELECT 'ISSUE' AS entity, id, project_id, status AS state, version, merged_into "
            + "FROM issue WHERE id IN (:ids) "
            + "UNION ALL "
            + "SELECT 'SPEC' AS entity, id, project_id, status AS state, version, CAST(NULL AS CHAR(120)) AS merged_into "
            + "FROM spec WHERE id IN (:ids) "
            + "UNION ALL "
            + "SELECT 'TASK' AS entity, id, project_id, state, version, CAST(NULL AS CHAR(120)) AS merged_into "
            + "FROM task WHERE id IN (:ids) "
            + "UNION ALL "
            + "SELECT 'CRITERION' AS entity, id, project_id, state, version, CAST(NULL AS CHAR(120)) AS merged_into "
            + "FROM milestone_criterion WHERE id IN (:ids)";
    private static final String FIND_ANY_VARCHAR2 = "SELECT 'ISSUE' AS entity, id, project_id, status AS state, version, merged_into "
            + "FROM issue WHERE id IN (:ids) "
            + "UNION ALL "
            + "SELECT 'SPEC' AS entity, id, project_id, status AS state, version, CAST(NULL AS VARCHAR2(120)) AS merged_into "
            + "FROM spec WHERE id IN (:ids) "
            + "UNION ALL "
            + "SELECT 'TASK' AS entity, id, project_id, state, version, CAST(NULL AS VARCHAR2(120)) AS merged_into "
            + "FROM task WHERE id IN (:ids) "
            + "UNION ALL "
            + "SELECT 'CRITERION' AS entity, id, project_id, state, version, CAST(NULL AS VARCHAR2(120)) AS merged_into "
            + "FROM milestone_criterion WHERE id IN (:ids)";

    public List<TypedFound> findAny(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        Set<String> asked = new HashSet<>(ids);
        String nullText = sqlBuilder.nullText(120);
        String sql;
        if ("CAST(NULL AS CHAR(120))".equals(nullText)) {
            sql = FIND_ANY_CHAR;
        } else if ("CAST(NULL AS VARCHAR2(120))".equals(nullText)) {
            sql = FIND_ANY_VARCHAR2;
        } else {
            sql = FIND_ANY_VARCHAR;
        }
        List<TypedFound> out = new ArrayList<>();
        for (List<String> chunk : chunks(ids)) {
            jdbc.sql(sql)
                    .param("ids", chunk)
                    .query((rs, n) -> new TypedFound(HistoryEntity.valueOf(rs.getString("entity")),
                            new Found(rs.getString("id"), rs.getString("project_id"), rs.getString("state"),
                                    rs.getLong("version"), rs.getString("merged_into"))))
                    .list()
                    .stream()
                    .filter(tf -> asked.contains(tf.row().id()))
                    .forEach(out::add);
        }
        return out;
    }

    /** Exact case-sensitive lookup of tasks with owner actor id, used by detail views without N+1 queries. */
    public record TaskSummaryRow(String id, String state, String ownerActorId) {
    }

    /** Batched display metadata for hydrating attention rows on the admin overview without N+1 queries. */
    public record AttentionDetailRow(HistoryEntity entity, String id, String title, String ownerActorId,
            Instant updatedAt) {
    }

    private static final String ISSUE_EFFECTIVE_OWNER = "COALESCE(owner_actor_id, "
            + "(SELECT d.recorded_by FROM deliverable d WHERE d.id = "
            + "(SELECT MAX(d2.id) FROM deliverable d2 WHERE d2.target_entity = 'ISSUE' AND d2.target_id = issue.id)))";

    public List<AttentionDetailRow> attentionDetails(Collection<String> issueIds, Collection<String> specIds,
            Collection<String> taskIds) {
        List<AttentionDetailRow> out = new ArrayList<>();
        if (issueIds != null && !issueIds.isEmpty()) {
            Set<String> asked = new HashSet<>(issueIds);
            for (List<String> chunk : chunks(issueIds)) {
                jdbc.sql("SELECT id, title, " + ISSUE_EFFECTIVE_OWNER + " AS effective_owner, updated_at "
                        + "FROM issue WHERE id IN (:ids)")
                        .param("ids", chunk)
                        .query((rs, n) -> new AttentionDetailRow(HistoryEntity.ISSUE, rs.getString("id"),
                                rs.getString("title"), rs.getString("effective_owner"), UtcTime.read(rs, "updated_at")))
                        .list()
                        .stream()
                        .filter(row -> asked.contains(row.id()))
                        .forEach(out::add);
            }
        }
        if (specIds != null && !specIds.isEmpty()) {
            Set<String> asked = new HashSet<>(specIds);
            for (List<String> chunk : chunks(specIds)) {
                jdbc.sql("SELECT id, title, owner_actor_id, updated_at FROM spec WHERE id IN (:ids)")
                        .param("ids", chunk)
                        .query((rs, n) -> new AttentionDetailRow(HistoryEntity.SPEC, rs.getString("id"),
                                rs.getString("title"), rs.getString("owner_actor_id"), UtcTime.read(rs, "updated_at")))
                        .list()
                        .stream()
                        .filter(row -> asked.contains(row.id()))
                        .forEach(out::add);
            }
        }
        if (taskIds != null && !taskIds.isEmpty()) {
            Set<String> asked = new HashSet<>(taskIds);
            for (List<String> chunk : chunks(taskIds)) {
                jdbc.sql("SELECT id, title, owner_actor_id, updated_at FROM task WHERE id IN (:ids)")
                        .param("ids", chunk)
                        .query((rs, n) -> new AttentionDetailRow(HistoryEntity.TASK, rs.getString("id"),
                                rs.getString("title"), rs.getString("owner_actor_id"), UtcTime.read(rs, "updated_at")))
                        .list()
                        .stream()
                        .filter(row -> asked.contains(row.id()))
                        .forEach(out::add);
            }
        }
        return out;
    }

    public List<TaskSummaryRow> taskSummaries(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        Set<String> asked = new HashSet<>(ids);
        List<TaskSummaryRow> out = new ArrayList<>();
        for (List<String> chunk : chunks(ids)) {
            jdbc.sql("SELECT id, state, owner_actor_id FROM task WHERE id IN (:ids) ORDER BY id")
                    .param("ids", chunk)
                    .query((rs, n) -> new TaskSummaryRow(rs.getString("id"), rs.getString("state"),
                            rs.getString("owner_actor_id")))
                    .list()
                    .stream()
                    .filter(row -> asked.contains(row.id()))
                    .forEach(out::add);
        }
        out.sort(Comparator.comparing(TaskSummaryRow::id));
        return out;
    }

    /** Exact case-sensitive lookup of issues by id. */
    public List<Found> issues(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<Found> fetched = new ArrayList<>();
        for (List<String> chunk : chunks(ids)) {
            fetched.addAll(jdbc.sql("SELECT id, project_id, status, version, merged_into FROM issue WHERE id IN (:ids) "
                    + "ORDER BY id").param("ids", chunk)
                    .query((rs, n) -> new Found(rs.getString("id"), rs.getString("project_id"), rs.getString("status"),
                            rs.getLong("version"), rs.getString("merged_into")))
                    .list());
        }
        return exactly(ids, fetched);
    }

    /** Exact case-sensitive lookup of specifications by id. */
    public List<Found> specs(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<Found> fetched = new ArrayList<>();
        for (List<String> chunk : chunks(ids)) {
            fetched.addAll(jdbc.sql("SELECT id, project_id, status, version FROM spec WHERE id IN (:ids) ORDER BY id")
                    .param("ids", chunk)
                    .query((rs, n) -> new Found(rs.getString("id"), rs.getString("project_id"), rs.getString("status"),
                            rs.getLong("version"), null))
                    .list());
        }
        return exactly(ids, fetched);
    }

    /** Exact case-sensitive lookup of tasks by id. */
    public List<Found> tasks(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<Found> fetched = new ArrayList<>();
        for (List<String> chunk : chunks(ids)) {
            fetched.addAll(jdbc.sql("SELECT id, project_id, state, version FROM task WHERE id IN (:ids) ORDER BY id")
                    .param("ids", chunk)
                    .query((rs, n) -> new Found(rs.getString("id"), rs.getString("project_id"), rs.getString("state"),
                            rs.getLong("version"), null))
                    .list());
        }
        return exactly(ids, fetched);
    }

    /** Exact case-sensitive lookup of milestone criteria by id. */
    public List<Found> criteria(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<Found> fetched = new ArrayList<>();
        for (List<String> chunk : chunks(ids)) {
            fetched.addAll(jdbc.sql("SELECT id, project_id, state, version FROM milestone_criterion WHERE id IN (:ids) "
                    + "ORDER BY id").param("ids", chunk)
                    .query((rs, n) -> new Found(rs.getString("id"), rs.getString("project_id"), rs.getString("state"),
                            rs.getLong("version"), null))
                    .list());
        }
        return exactly(ids, fetched);
    }

    private static List<Found> exactly(Collection<String> ids, List<Found> fetched) {
        Set<String> asked = new HashSet<>(ids);
        return fetched.stream().filter(found -> asked.contains(found.id()))
                .sorted(Comparator.comparing(Found::id)).toList();
    }

    private static List<List<String>> chunks(Collection<String> ids) {
        List<String> distinct = new ArrayList<>(new LinkedHashSet<>(ids));
        List<List<String>> out = new ArrayList<>();
        for (int i = 0; i < distinct.size(); i += IN_CHUNK_SIZE) {
            out.add(distinct.subList(i, Math.min(i + IN_CHUNK_SIZE, distinct.size())));
        }
        return out;
    }
}
