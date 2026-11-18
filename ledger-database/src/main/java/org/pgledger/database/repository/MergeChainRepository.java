// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.database.repository;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.pgledger.domain.value.IssueId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Follows the merges of issues level by level with plain IN-list selects; a seen set guarantees termination.
 */
@Repository
public class MergeChainRepository {

    private final JdbcClient jdbc;

    public MergeChainRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Every issue merged into {@code root}, directly or transitively. */
    public Set<IssueId> mergedInto(IssueId root) {
        Set<IssueId> found = new LinkedHashSet<>();
        Set<IssueId> seen = new LinkedHashSet<>(List.of(root));
        List<IssueId> level = List.of(root);
        while (!level.isEmpty()) {
            List<String> parents = level.stream().map(IssueId::value).toList();
            List<IssueId> next = new ArrayList<>();
            for (String id : jdbc.sql("SELECT id FROM issue WHERE merged_into IN (:parents) ORDER BY id")
                    .param("parents", parents).query(String.class).list()) {
                IssueId child = IssueId.of(id);
                if (seen.add(child)) {
                    found.add(child);
                    next.add(child);
                }
            }
            level = next;
        }
        return found;
    }

    /**
     * Conditionally enforces that an issue entering {@code MERGED} is not already {@code MERGED}, acquiring a row lock
     * without altering {@code version} or {@code updated_at}.
     */
    public int pinMergeSource(IssueId id) {
        return jdbc.sql("UPDATE issue SET merged_into = NULL WHERE id = :id AND status <> 'MERGED'")
                .param("id", id.value()).update();
    }

    /**
     * Conditionally enforces that a merge target is neither {@code MERGED} nor {@code VERIFIED}, acquiring a row lock
     * on the target.
     */
    public int pinMergeTarget(IssueId id) {
        return jdbc.sql("UPDATE issue SET merged_into = NULL WHERE id = :id AND status NOT IN ('MERGED', 'VERIFIED')")
                .param("id", id.value()).update();
    }

    /**
     * Conditionally enforces that an issue being verified is still {@code RESOLVED} before scanning its merge chain,
     * serializing incoming merges onto the verified row.
     */
    public int pinVerifyCandidate(IssueId id) {
        return jdbc.sql("UPDATE issue SET merged_into = NULL WHERE id = :id AND status = 'RESOLVED'")
                .param("id", id.value()).update();
    }
}
