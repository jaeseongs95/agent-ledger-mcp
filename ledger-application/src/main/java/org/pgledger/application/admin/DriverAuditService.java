// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.application.admin;

import java.time.Instant;
import java.util.List;
import org.pgledger.database.repository.DriverAuditKeyRepository;
import org.pgledger.database.repository.OwnerAuditRepository;
import org.pgledger.domain.error.ConflictCode;
import org.pgledger.domain.error.ConflictException;
import org.pgledger.domain.rule.OwnerAction;
import org.pgledger.domain.value.OwnerIdentity;
import org.pgledger.domain.value.Sha256;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The audit rows of a driver registration, one transaction per method (principle 4.4: the transaction boundary is a service
 * method; the registration file itself is replaced outside any transaction by the caller, see
 * {@code DriverRegistrationFile#register}). The callers are the entry points that register a driver (the admin web driver
 * page and the command line tool); they hold the file, this service holds the audit trail.
 */
@Service
@Transactional
public class DriverAuditService {

    private final OwnerAuditWriter writer;
    private final OwnerAuditRepository trail;
    private final DriverAuditKeyRepository keys;

    public DriverAuditService(OwnerAuditWriter writer, OwnerAuditRepository trail, DriverAuditKeyRepository keys) {
        this.writer = writer;
        this.trail = trail;
        this.keys = keys;
    }

    /** Appends the row {@link OwnerAction#DRIVER_REGISTERED} of a registration the owner has just made. */
    public void recordRegistered(OwnerIdentity actor, String className, String detail) {
        writer.record(actor, OwnerAction.DRIVER_REGISTERED, className, detail);
    }

    /**
     * Appends the row of a registration made while the schema was not ready, unless the trail already holds one for exactly
     * that registration (the same class, and a detail that starts with {@code known}): the trail is read first, then the key of
     * the registration and the row are written, in this one transaction. Two runs at the same time (migrate and setup) can both
     * find no row; the database decides which of them writes it: the key is unique ({@code driver_audit_key}), and the run that
     * cannot insert it ends with the translated DUPLICATE_KEY and writes nothing. Nothing is run again.
     *
     * @param known        the part of the detail that every row of the registration starts with
     * @param registeredAt when the registration was made (it follows {@code known} in the detail of the row)
     * @return whether a row was written (false when the trail had one)
     * @throws ConflictException DUPLICATE_KEY when another run claimed the registration at the same moment
     */
    public boolean recordIfMissing(OwnerIdentity actor, String className, String known, Instant registeredAt) {
        boolean present = trail.findBySubjectOrderById(className).stream()
                .anyMatch(row -> row.operation() == OwnerAction.DRIVER_REGISTERED && row.detail() != null
                        && row.detail().startsWith(known));
        if (present) {
            return false;
        }
        try {
            keys.insert(registrationKey(className, known, registeredAt));
        } catch (DuplicateKeyException duplicate) {
            throw new ConflictException(ConflictCode.DUPLICATE_KEY, List.of("driver registration " + className),
                    duplicate);
        }
        writer.record(actor, OwnerAction.DRIVER_REGISTERED, className, known + " registered_at=" + registeredAt);
        return true;
    }

    /** The identity of a registration: the class, the jar and its digest ({@code known}) and the time of the registration. */
    private static String registrationKey(String className, String known, Instant registeredAt) {
        return Sha256.ofText(className + "\n" + known + "\n" + registeredAt).hex();
    }
}
