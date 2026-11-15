package org.pgledger.application.admin;

import org.pgledger.database.repository.DriverAuditKeyRepository;
import org.pgledger.domain.rule.OwnerAction;
import org.pgledger.domain.value.OwnerIdentity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The write that two runs of {@code migrate} / {@code setup} race for when they record the audit row of the same driver
 * registration: the claim of the registration by its key and the row itself. It runs in a nested transaction
 * ({@code Propagation.NESTED}, a JDBC savepoint, which every DBMS supports): the key is inserted first, and when the database
 * refuses it as a duplicate the registration was recorded already, Spring takes the call back to the savepoint and rethrows the
 * {@code DuplicateKeyException}, so the transaction of {@link DriverAuditService} stays usable on every DBMS and nothing was
 * written. The nested boundary has to be a bean of its own so that the call passes the transaction proxy; the transaction of the
 * use case belongs to the calling service.
 */
@Service
@Transactional(propagation = Propagation.NESTED)
public class DriverAuditWriter {

    private final DriverAuditKeyRepository keys;
    private final OwnerAuditWriter writer;

    public DriverAuditWriter(DriverAuditKeyRepository keys, OwnerAuditWriter writer) {
        this.keys = keys;
        this.writer = writer;
    }

    /**
     * Claims the registration and appends its {@link OwnerAction#DRIVER_REGISTERED} row.
     *
     * @param registrationKey the 64 hex characters that identify the registration
     * @throws org.springframework.dao.DuplicateKeyException when the registration was claimed by another call first (nothing of
     *         this call stays written)
     */
    public void recordOnce(String registrationKey, OwnerIdentity actor, String className, String detail) {
        keys.insert(registrationKey);
        writer.record(actor, OwnerAction.DRIVER_REGISTERED, className, detail);
    }
}
