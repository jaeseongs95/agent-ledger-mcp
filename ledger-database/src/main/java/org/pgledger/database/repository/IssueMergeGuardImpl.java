// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.database.repository;

import org.pgledger.domain.value.IssueId;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Spring Data fragment implementation of {@link IssueMergeGuard}. */
public class IssueMergeGuardImpl implements IssueMergeGuard {

    private final JdbcClient jdbc;

    public IssueMergeGuardImpl(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean pinRow(IssueId id) {
        return jdbc.sql("UPDATE issue SET status = status WHERE id = :id")
                .param("id", id.value()).update() == 1;
    }

    @Override
    public boolean guardMergeSource(IssueId id) {
        return jdbc.sql("UPDATE issue SET status = status WHERE id = :id AND status <> 'MERGED'")
                .param("id", id.value()).update() == 1;
    }

    @Override
    public boolean guardMergeTarget(IssueId id) {
        return jdbc.sql("UPDATE issue SET status = status WHERE id = :id AND status NOT IN ('MERGED', 'VERIFIED')")
                .param("id", id.value()).update() == 1;
    }

    @Override
    public boolean guardVerifyCandidate(IssueId id) {
        return jdbc.sql("UPDATE issue SET status = status WHERE id = :id AND status = 'RESOLVED'")
                .param("id", id.value()).update() == 1;
    }
}
