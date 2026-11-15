package org.pgledger.database.repository;

import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The one check of a repository method that has to run inside the transaction of the calling service method: one that takes a
 * row lock (the lock lasts until the transaction ends, so without a transaction it is released at once and protects nothing), or
 * one that completes a write that belongs to the use case (the response of a receipt, the bump of the code revision).
 *
 * <p>A repository opens no transaction and carries no {@code @Transactional}, not even {@code Propagation.MANDATORY} (principle
 * 4.4: the boundary is the service layer; the architecture rule of this module allows the annotation on services only), so the
 * method asks Spring for the transaction of the calling thread and refuses a call without one with Spring's
 * {@link IllegalTransactionStateException}, the exception that {@code MANDATORY} throws for the same case.
 */
final class RequiredTransaction {

    private RequiredTransaction() {
    }

    /**
     * @param what what runs, as a sentence start ("ids are issued", "a row is locked"); the refusal says it needs the
     *             transaction of the calling service method
     * @throws IllegalTransactionStateException when the calling thread has no transaction
     */
    static void check(String what) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalTransactionStateException(what + " inside the transaction of the calling service method; there is none");
        }
    }
}
