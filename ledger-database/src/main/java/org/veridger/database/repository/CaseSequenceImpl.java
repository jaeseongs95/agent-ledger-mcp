// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.database.repository;

import org.veridger.domain.value.IssueId;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Spring Data fragment implementation of {@link CaseSequence}. */
public class CaseSequenceImpl implements CaseSequence {

    private final JdbcClient jdbc;

    public CaseSequenceImpl(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean nextCaseSeq(IssueId issue) {
        return jdbc.sql("UPDATE issue SET case_seq = case_seq + 1 WHERE id = :id AND status NOT IN ('MERGED', 'VERIFIED')")
                .param("id", issue.value()).update() == 1;
    }
}
