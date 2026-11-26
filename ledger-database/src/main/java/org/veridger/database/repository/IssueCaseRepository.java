// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.database.repository;

import java.util.List;
import java.util.Optional;
import org.veridger.domain.model.IssueCase;
import org.veridger.domain.value.IssueId;
import org.springframework.data.repository.Repository;

/** Observed cases of issues: insert and read only. */
public interface IssueCaseRepository extends Repository<IssueCase, Long>, Insertable<IssueCase> {

    Optional<IssueCase> findById(Long id);

    List<IssueCase> findByIssueIdOrderById(IssueId issueId);
}
