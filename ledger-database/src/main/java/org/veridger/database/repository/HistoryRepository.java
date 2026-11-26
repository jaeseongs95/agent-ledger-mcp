// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.database.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.veridger.domain.model.HistoryEntry;
import org.veridger.domain.rule.HistoryEntity;
import org.veridger.domain.value.OperationKey;
import org.springframework.data.repository.Repository;

/**
 * The append-only history: insert and read only. There is deliberately no save, update or delete here, and the
 * application account has no right to UPDATE or DELETE the table.
 */
public interface HistoryRepository extends Repository<HistoryEntry, Long>, Insertable<HistoryEntry> {

    Optional<HistoryEntry> findById(Long id);

    List<HistoryEntry> findByIdIn(Collection<Long> ids);

    List<HistoryEntry> findByEntityAndEntityIdOrderById(HistoryEntity entity, String entityId);

    List<HistoryEntry> findByOperationKeyOrderById(OperationKey operationKey);
}
