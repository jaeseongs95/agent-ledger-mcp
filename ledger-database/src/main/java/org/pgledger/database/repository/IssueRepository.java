// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.database.repository;

import java.util.List;
import java.util.Optional;
import org.pgledger.domain.model.Issue;
import org.pgledger.domain.rule.IssueStatus;
import org.pgledger.domain.value.IssueId;
import org.pgledger.domain.value.ProjectId;
import org.springframework.data.repository.Repository;

/** Issues: save (insert when version is 0, optimistic lock update otherwise) and read. */
public interface IssueRepository extends Repository<Issue, IssueId>, CaseSequence {

    <S extends Issue> S save(S issue);

    Optional<Issue> findById(IssueId id);

    boolean existsById(IssueId id);

    List<Issue> findByProjectIdAndStatus(ProjectId projectId, IssueStatus status);

    List<Issue> findByMergedInto(IssueId mergedInto);
}
