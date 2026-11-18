// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.database.repository;

import java.util.Optional;
import org.pgledger.domain.model.Receipt;
import org.pgledger.domain.value.ActorId;
import org.pgledger.domain.value.OperationKey;
import org.springframework.data.repository.Repository;

/**
 * Write receipts: insert (a duplicate of the pair author and operation key raises a duplicate-key exception) and read by that
 * pair. The response is filled in by {@link ReceiptCompletionRepository}.
 */
public interface ReceiptRepository extends Repository<Receipt, OperationKey>, Insertable<Receipt> {

    Optional<Receipt> findByAuthorIdAndOperationKey(ActorId authorId, OperationKey operationKey);
}
