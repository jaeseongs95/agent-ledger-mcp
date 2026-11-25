// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.database.repository;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

    private final JdbcClient jdbc;

    public EntityLookupRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
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
