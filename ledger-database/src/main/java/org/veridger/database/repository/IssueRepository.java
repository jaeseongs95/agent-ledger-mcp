// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.database.repository;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.veridger.domain.model.Issue;
import org.veridger.domain.rule.IssueStatus;
import org.veridger.domain.value.IssueId;
import org.veridger.domain.value.ProjectId;
import org.springframework.data.repository.Repository;

/**
 * Issues: save (insert when version is 0, optimistic lock update otherwise) and read.
 *
 * <p>Ids are compared case-sensitively in Java via {@link #findById} and {@link #existsById} so case-insensitive DBMS
 * collations never return a differently cased row.
 */
public interface IssueRepository extends Repository<Issue, IssueId>, CaseSequence, IssueMergeGuard {

    <S extends Issue> S save(S issue);

    /** Raw fetch that may return a differently cased row on case-insensitive collations; call {@link #findById}. */
    Optional<Issue> findOneById(IssueId id);

    default Optional<Issue> findById(IssueId id) {
        return findOneById(id).filter(row -> Objects.equals(row.id(), id));
    }

    default boolean existsById(IssueId id) {
        return findById(id).isPresent();
    }

    List<Issue> findByProjectIdAndStatus(ProjectId projectId, IssueStatus status);

    List<Issue> findByMergedInto(IssueId mergedInto);
}
