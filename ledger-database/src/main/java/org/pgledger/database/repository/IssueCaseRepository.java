package org.pgledger.database.repository;

import java.util.List;
import java.util.Optional;
import org.pgledger.domain.model.IssueCase;
import org.pgledger.domain.value.IssueId;
import org.springframework.data.repository.Repository;

/** Observed cases of issues: insert and read only. */
public interface IssueCaseRepository extends Repository<IssueCase, Long>, Insertable<IssueCase> {

    Optional<IssueCase> findById(Long id);

    List<IssueCase> findByIssueIdOrderById(IssueId issueId);
}
