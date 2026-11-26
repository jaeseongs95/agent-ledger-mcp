// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.database.repository;

import java.util.List;
import java.util.Optional;
import org.agentledger.domain.model.Deliverable;
import org.agentledger.domain.rule.DeliverableTarget;
import org.agentledger.domain.value.CodeValue;
import org.springframework.data.repository.Repository;

/**
 * Deliverables on issues, specs, and tasks: insert on initial record, update when verification/check facts arrive.
 */
public interface DeliverableRepository
        extends Repository<Deliverable, Long>, Insertable<Deliverable>, Updatable<Deliverable> {

    Optional<Deliverable> findById(Long id);

    Optional<Deliverable> findByTargetEntityAndTargetIdAndKindAndDeliverableRefAndLocationKey(
            DeliverableTarget targetEntity, String targetId, CodeValue kind, String deliverableRef, String locationKey);

    List<Deliverable> findByTargetEntityAndTargetIdAndKindAndDeliverableRef(
            DeliverableTarget targetEntity, String targetId, CodeValue kind, String deliverableRef);

    List<Deliverable> findByTargetEntityAndTargetIdOrderById(DeliverableTarget targetEntity, String targetId);
}
