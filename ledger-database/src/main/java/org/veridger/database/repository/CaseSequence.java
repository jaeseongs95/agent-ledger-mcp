// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.database.repository;

import org.veridger.domain.value.IssueId;

/**
 * Repository fragment of issues: the number of the next case of an issue. Recording a case takes the number with one
 * conditional UPDATE: it affects no row for a MERGED or VERIFIED issue, so a concurrent case and merge or
 * verification serialize in the database on the issue row. It joins the transaction of the calling service method.
 */
public interface CaseSequence {

    /**
     * Takes the next case number of the issue ({@code case_seq = case_seq + 1}, without altering {@code version} or
     * {@code updated_at}).
     *
     * @return true when the issue exists and is neither MERGED nor VERIFIED (its counter moved), false otherwise
     */
    boolean nextCaseSeq(IssueId issue);
}
