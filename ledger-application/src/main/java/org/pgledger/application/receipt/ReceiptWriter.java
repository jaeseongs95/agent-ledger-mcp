package org.pgledger.application.receipt;

import org.pgledger.database.repository.ReceiptRepository;
import org.pgledger.domain.model.Receipt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one write that {@link ReceiptService#reserve} races for: the insert of the receipt. It runs in a nested transaction
 * ({@code Propagation.NESTED}, a JDBC savepoint, which every DBMS and {@code DataSourceTransactionManager} support): Spring
 * makes the savepoint when the method starts and, when the insert breaks the unique key of the request id, rolls back to it
 * and rethrows the {@code DuplicateKeyException}. The caller then answers from the receipt of the call that won, and the
 * transaction of the write stays usable on every DBMS (a failed statement aborts the whole transaction on PostgreSQL unless
 * it is taken back to a savepoint). The nested boundary has to be a bean of its own so that the call passes the transaction
 * proxy; it is the boundary of one statement inside the use case of the calling service, which owns the transaction.
 */
@Service
@Transactional(propagation = Propagation.NESTED)
public class ReceiptWriter {

    private final ReceiptRepository receipts;

    public ReceiptWriter(ReceiptRepository receipts) {
        this.receipts = receipts;
    }

    /**
     * Inserts the receipt of a request id that is reserved for the caller.
     *
     * @throws org.springframework.dao.DuplicateKeyException when the request id is reserved already (nothing of this call
     *         stays written)
     */
    public void insert(Receipt receipt) {
        receipts.insert(receipt);
    }
}
