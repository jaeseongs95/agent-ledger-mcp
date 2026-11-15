package org.pgledger.database.repository;

import java.util.Optional;
import org.pgledger.domain.model.Receipt;
import org.pgledger.domain.value.OperationKey;
import org.springframework.data.repository.Repository;

/**
 * Write receipts: insert (a duplicate operation key raises a duplicate-key exception) and read. The response is
 * filled in by {@link ReceiptCompletionRepository}.
 */
public interface ReceiptRepository extends Repository<Receipt, OperationKey>, Insertable<Receipt> {

    Optional<Receipt> findById(OperationKey operationKey);
}
