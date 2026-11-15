package org.pgledger.cli.database;

import java.util.function.Supplier;
import org.pgledger.application.admin.CommonCodeService;
import org.pgledger.application.admin.GrantService;
import org.pgledger.application.admin.PasskeyService;
import org.pgledger.application.admin.ProjectService;
import org.pgledger.application.admin.SessionKeyService;
import org.pgledger.application.legacy.LegacyImportService;
import org.pgledger.application.legacy.LegacyRetireService;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.FailureException;
import org.pgledger.domain.error.LedgerException;
import org.pgledger.domain.value.OwnerIdentity;
import org.springframework.dao.DataAccessException;

/**
 * The door of the owner commands to the ledger: the application services the admin web uses, the actor of the audit trail
 * and the door that turns a refusal of the ledger into the CLI's refusal ({@link LedgerRefusals}). It exists only when a
 * usable JDBC driver is registered (see {@link CliLedgerConfiguration}, which imports it as a component under its
 * condition), so a command that asks for it and finds none reports DRIVER_NOT_CONFIGURED.
 */
public final class CliLedger {

    private final ProjectService projects;
    private final CommonCodeService codes;
    private final GrantService grants;
    private final SessionKeyService keys;
    private final PasskeyService passkeys;
    private final LegacyImportService importer;
    private final LegacyRetireService retirer;
    private final CliActor actor;
    private final SchemaReadiness readiness;

    public CliLedger(ProjectService projects, CommonCodeService codes, GrantService grants, SessionKeyService keys,
            PasskeyService passkeys, LegacyImportService importer, LegacyRetireService retirer, CliActor actor,
            SchemaReadiness readiness) {
        this.projects = projects;
        this.codes = codes;
        this.grants = grants;
        this.keys = keys;
        this.passkeys = passkeys;
        this.importer = importer;
        this.retirer = retirer;
        this.actor = actor;
        this.readiness = readiness;
    }

    public ProjectService projects() {
        return projects;
    }

    public CommonCodeService codes() {
        return codes;
    }

    public GrantService grants() {
        return grants;
    }

    public SessionKeyService keys() {
        return keys;
    }

    /** The passkeys of the owner ({@code passkey-code}). */
    public PasskeyService passkeys() {
        return passkeys;
    }

    /** The legacy import ({@code import-legacy}). */
    public LegacyImportService importer() {
        return importer;
    }

    /** The removal of the 1.x database objects ({@code retire-legacy}). */
    public LegacyRetireService retirer() {
        return retirer;
    }

    /** The audit actor {@code cli:<process user>}. */
    public OwnerIdentity actor() {
        return actor.current();
    }

    /**
     * Runs the work on the ledger after the schema was checked. A business refusal of the ledger is the CLI refusal
     * LEDGER_REFUSED (its code and, for a validation failure, the first field and reason; never an input value); a database
     * failure is DATABASE_FAILED (the cause is in the log).
     */
    public <T> T call(Supplier<T> work) {
        readiness.require();
        try {
            return work.get();
        } catch (LedgerException refused) {
            throw LedgerRefusals.translate(refused, LedgerRefusals.Context.GENERIC);
        } catch (DataAccessException failure) {
            throw new FailureException(ErrorCode.DATABASE_FAILED, failure);
        }
    }
}
