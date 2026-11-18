package org.pgledger.database.repository;

import org.pgledger.domain.value.DefectId;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Spring Data fragment implementation of {@link CaseSequence}. */
public class CaseSequenceImpl implements CaseSequence {

    private final JdbcClient jdbc;

    public CaseSequenceImpl(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean nextCaseSeq(DefectId defect) {
        return jdbc.sql("UPDATE defect SET case_seq = case_seq + 1 WHERE id = :id AND status NOT IN ('MERGED', 'VERIFIED')")
                .param("id", defect.value()).update() == 1;
    }
}
