// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.database.repository;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.pgledger.domain.model.Spec;
import org.pgledger.domain.rule.SpecStatus;
import org.pgledger.domain.value.ProjectId;
import org.pgledger.domain.value.SpecId;
import org.springframework.data.repository.Repository;

/**
 * Specifications ({@code spec}): save (insert when version is 0, optimistic lock update otherwise) and read.
 *
 * <p>Ids are compared case-sensitively in Java via {@link #findById} while {@link #findByIdKey} detects
 * case-folded duplicates via {@code id_key}.
 */
public interface SpecRepository extends Repository<Spec, SpecId> {

    <S extends Spec> S save(S spec);

    /** Raw fetch that may return a sibling differing only in case on case-insensitive collations. */
    Optional<Spec> findOneById(SpecId id);

    default Optional<Spec> findById(SpecId id) {
        return findOneById(id).filter(row -> Objects.equals(row.id(), id));
    }

    default boolean existsById(SpecId id) {
        return findById(id).isPresent();
    }

    Optional<Spec> findByIdKey(String key);

    List<Spec> findByProjectIdAndStatus(ProjectId projectId, SpecStatus status);

    List<Spec> findByParentId(SpecId parentId);
}
