// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.database.repository;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.veridger.domain.value.IssueId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Follows the merges of issues level by level with plain IN-list selects; a seen set guarantees termination.
 */
@Repository
public class MergeChainRepository {

    private static final int IN_CHUNK_SIZE = 500;

    private final JdbcClient jdbc;

    public MergeChainRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Every issue merged into {@code root}, directly or transitively (chunked for portable IN-list limits). */
    public Set<IssueId> mergedInto(IssueId root) {
        Set<IssueId> found = new LinkedHashSet<>();
        Set<IssueId> seen = new LinkedHashSet<>(List.of(root));
        List<IssueId> level = List.of(root);
        while (!level.isEmpty()) {
            List<String> parents = level.stream().map(IssueId::value).toList();
            List<IssueId> next = new ArrayList<>();
            for (int i = 0; i < parents.size(); i += IN_CHUNK_SIZE) {
                List<String> chunk = parents.subList(i, Math.min(i + IN_CHUNK_SIZE, parents.size()));
                for (String id : jdbc.sql("SELECT id FROM issue WHERE merged_into IN (:parents) ORDER BY id")
                        .param("parents", chunk).query(String.class).list()) {
                    IssueId child = IssueId.of(id);
                    if (seen.add(child)) {
                        found.add(child);
                        next.add(child);
                    }
                }
            }
            level = next;
        }
        return found;
    }
}
