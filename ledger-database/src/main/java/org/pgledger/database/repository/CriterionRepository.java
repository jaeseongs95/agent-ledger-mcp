// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.database.repository;

import java.util.Objects;
import java.util.Optional;
import org.pgledger.domain.model.Criterion;
import org.pgledger.domain.value.CriterionId;
import org.pgledger.domain.value.ProjectId;
import org.springframework.data.repository.Repository;

/**
 * Milestone criteria ({@code milestone_criterion}): save and exact-id read.
 */
public interface CriterionRepository extends Repository<Criterion, CriterionId> {

    <S extends Criterion> S save(S criterion);

    /** Raw fetch that may return a sibling differing only in case on case-insensitive collations. */
    Optional<Criterion> findOneById(CriterionId id);

    default Optional<Criterion> findById(CriterionId id) {
        return findOneById(id).filter(row -> Objects.equals(row.id(), id));
    }

    default boolean existsById(CriterionId id) {
        return findById(id).isPresent();
    }

    Optional<Criterion> findByIdKey(String key);

    Optional<Criterion> findByProjectIdAndMilestoneAndCode(ProjectId projectId, String milestone, String code);
}
