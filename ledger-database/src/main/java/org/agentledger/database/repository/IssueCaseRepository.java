// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.database.repository;

import java.util.List;
import java.util.Optional;
import org.agentledger.domain.model.IssueCase;
import org.agentledger.domain.value.IssueId;
import org.springframework.data.repository.Repository;

/** Observed cases of issues: insert and read only. */
public interface IssueCaseRepository extends Repository<IssueCase, Long>, Insertable<IssueCase> {

    Optional<IssueCase> findById(Long id);

    List<IssueCase> findByIssueIdOrderById(IssueId issueId);
}
