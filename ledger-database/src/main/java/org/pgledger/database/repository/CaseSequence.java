package org.pgledger.database.repository;

import org.pgledger.domain.value.DefectId;

/**
 * Repository fragment of the defects: the number of the next case of a defect. Recording a case takes the number with one
 * conditional UPDATE, which is the statement of the use case itself (principle 4.4): it affects no row for a MERGED or
 * VERIFIED defect, so a case and a merge or verification of the same defect are decided by the database on the row of the
 * defect, and the case that loses is refused. It joins the transaction of the calling service method.
 */
public interface CaseSequence {

    /**
     * Takes the next case number of the defect ({@code case_seq = case_seq + 1}, which changes neither {@code version} nor
     * {@code updated_at}, so a concurrent edit with an expected version still succeeds).
     *
     * @return true when the defect exists and is neither MERGED nor VERIFIED (its counter moved), false otherwise
     */
    boolean nextCaseSeq(DefectId defect);
}
