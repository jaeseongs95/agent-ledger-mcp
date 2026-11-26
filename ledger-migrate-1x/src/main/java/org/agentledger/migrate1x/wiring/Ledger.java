// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.migrate1x.wiring;

import java.util.function.Supplier;
import org.agentledger.cli.ErrorCode;
import org.agentledger.cli.FailureException;
import org.agentledger.cli.database.CliActor;
import org.agentledger.cli.database.SchemaReadiness;
import org.agentledger.domain.error.LedgerException;
import org.agentledger.domain.value.OwnerIdentity;
import org.agentledger.migrate1x.service.LegacyImportService;
import org.agentledger.migrate1x.service.LegacyRetireService;
import org.springframework.dao.DataAccessException;

/**
 * The door of the commands to the ledger: the import and retire services, the actor of the audit trail and the door that turns a
 * refusal of the ledger into the refusal of the tool ({@link Refusals}). It exists only when a usable JDBC driver is registered
 * (see {@link LedgerConfiguration}, which imports it as a component under its condition), so a command that asks for it and finds
 * none reports DRIVER_NOT_CONFIGURED.
 */
public final class Ledger {

    private final LegacyImportService importer;
    private final LegacyRetireService retirer;
    private final CliActor actor;
    private final SchemaReadiness readiness;

    public Ledger(LegacyImportService importer, LegacyRetireService retirer, CliActor actor, SchemaReadiness readiness) {
        this.importer = importer;
        this.retirer = retirer;
        this.actor = actor;
        this.readiness = readiness;
    }

    /** The import of the 1.x ledger ({@code import-1x}). */
    public LegacyImportService importer() {
        return importer;
    }

    /** The removal of the 1.x database objects ({@code retire-1x}). */
    public LegacyRetireService retirer() {
        return retirer;
    }

    /** The audit actor {@code cli:<process user>}. */
    public OwnerIdentity actor() {
        return actor.current();
    }

    /**
     * Runs the work on the ledger after the schema was checked. A business refusal of the ledger is the refusal LEDGER_REFUSED (its
     * code and, for a validation failure, the first field and reason; never an input value); a database failure is
     * DATABASE_FAILED (the cause is in the log).
     */
    public <T> T call(Supplier<T> work) {
        readiness.require();
        try {
            return work.get();
        } catch (LedgerException refused) {
            throw Refusals.translate(refused, Refusals.Context.GENERIC);
        } catch (DataAccessException failure) {
            throw new FailureException(ErrorCode.DATABASE_FAILED, failure);
        }
    }
}
