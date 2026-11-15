package org.pgledger.cli.driver;

import java.util.Optional;
import org.pgledger.application.admin.DriverAuditService;
import org.pgledger.cli.database.CliActor;
import org.pgledger.database.driver.DriverRegistrationFile;

/**
 * The audit row of a driver registration that was made while the ledger schema was not up to date (the first registration of an
 * installation, which has no database yet, or a registration on a database whose schema was dropped or not migrated yet):
 * {@code driver-register} writes the registration file and leaves the row to {@code migrate} and {@code setup}, which call
 * {@link #recordIfMissing()} once the schema is ready. The registration file names the driver (class, jar, digest, time), and the
 * audit trail tells whether a row for exactly that registration is there, so a repeated call writes nothing. The row names the
 * actor of the run that records it (the account the installer runs as, {@code cli:<user>}) and the time of the registration
 * in its detail.
 *
 * <p>The file is read here, outside any transaction; the check of the trail and the row are one transaction, a method of
 * {@link DriverAuditService} (principle 4.4: this class opens none).
 *
 * <p>It exists only when the CLI has its database, like {@code CliLedger} (see {@code CliLedgerConfiguration}).
 */
public final class PendingDriverAudit {

    private final DriverRegistrationFile file;
    private final DriverAuditService audit;
    private final CliActor actor;

    public PendingDriverAudit(DriverRegistrationFile file, DriverAuditService audit, CliActor actor) {
        this.file = file;
        this.audit = audit;
        this.actor = actor;
    }

    /**
     * Writes the row of the registered driver when the audit trail has none for it.
     *
     * @return the class of the driver when a row was written, empty when there is no registration or its row is there
     */
    public Optional<String> recordIfMissing() {
        return file.read().flatMap(entry -> {
            boolean written = audit.recordIfMissing(actor.current(), entry.className(),
                    detail(entry.jar(), entry.sha256()), entry.registeredAt());
            return written ? Optional.of(entry.className()) : Optional.empty();
        });
    }

    /** The detail every row of a registration starts with: the jar and the digest (the time follows when it is recorded later). */
    static String detail(String jar, String sha256) {
        return "jar=" + jar + " sha256=" + sha256;
    }
}
