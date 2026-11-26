// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.migrate1x.command;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import org.agentledger.cli.CliMessages;
import org.agentledger.cli.CliOptions;
import org.agentledger.cli.CliOutput;
import org.agentledger.cli.Command;
import org.agentledger.cli.ErrorCode;
import org.agentledger.cli.FailureException;
import org.agentledger.cli.UsageException;
import org.agentledger.cli.database.DatabaseBeans;
import org.agentledger.database.driver.ApplicationAccountSettings;
import org.agentledger.database.driver.LedgerJdbcProperties;
import org.agentledger.domain.error.LedgerException;
import org.agentledger.domain.error.ValidationException;
import org.agentledger.domain.value.Sha256;
import org.agentledger.migrate1x.service.LegacyRetirePlan;
import org.agentledger.migrate1x.service.RetireRefusedException;
import org.agentledger.migrate1x.tool.MigrationError;
import org.agentledger.migrate1x.tool.ToolCommand;
import org.agentledger.migrate1x.wiring.Ledger;
import org.agentledger.migrate1x.wiring.Refusals;
import org.agentledger.migrate1x.wiring.Refusals.Context;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/**
 * {@code retire-1x [--confirm=<sha256> --backup-tested]}: removes what the 1.x installer made in the database (functions,
 * triggers, views, tables, the schema and its roles) after the move to 2.0 was accepted, through {@code LegacyRetireService}.
 * It runs only when the owner runs it; nothing in 2.0 calls it.
 *
 * <p>Without {@code --confirm} it connects to the legacy ledger ({@code ledger.import.source.*}, the account has to own the
 * objects), checks the preconditions, prints every object in the order of the drop and the SHA-256 of that list, and writes
 * nothing. With {@code --confirm=<sha256>} it recomputes the list and drops it in one transaction only when the digest is the
 * one the owner read; the flag {@code --backup-tested} is required with it, as the owner's statement that a backup of the
 * legacy database was made and its restore was tested: the command cannot check that, and says so.
 *
 * <p>The accounts of the 2.0 settings ({@code ledger.jdbc.*}, with and without {@code --owner-account}) are handed to the service
 * so that a legacy role of the same name is never dropped.
 */
@Component
public class Retire1xCommand implements Command {

    static final String CONFIRM = "confirm";
    static final String BACKUP_TESTED = "backup-tested";
    /** How many names a refusal prints; the rest is counted. */
    private static final int NAMES_SHOWN = 20;

    private final ObjectProvider<Ledger> ledger;
    private final ObjectProvider<LedgerJdbcProperties> ledgerSettings;
    private final ObjectProvider<ApplicationAccountSettings> applicationSettings;
    private final CliOutput output;
    private final CliMessages messages;
    private final RefusalReport refusalReport;

    Retire1xCommand(ObjectProvider<Ledger> ledger, ObjectProvider<LedgerJdbcProperties> ledgerSettings,
            ObjectProvider<ApplicationAccountSettings> applicationSettings, CliOutput output, CliMessages messages,
            RefusalReport refusalReport) {
        this.ledger = ledger;
        this.ledgerSettings = ledgerSettings;
        this.applicationSettings = applicationSettings;
        this.output = output;
        this.messages = messages;
        this.refusalReport = refusalReport;
    }

    @Override
    public ToolCommand name() {
        return ToolCommand.RETIRE_1X;
    }

    @Override
    public Set<String> options() {
        return Set.of(CONFIRM, BACKUP_TESTED);
    }

    @Override
    public void run(List<String> operands, ApplicationArguments arguments) {
        if (!operands.isEmpty()) {
            throw new UsageException(ErrorCode.TOO_MANY_ARGUMENTS);
        }
        Optional<String> confirm = CliOptions.value(arguments, CONFIRM);
        boolean backupTested = CliOptions.flag(arguments, BACKUP_TESTED);
        // the acknowledgement belongs to the confirmation: alone it has no use, and the confirmation needs it
        if (backupTested && confirm.isEmpty()) {
            throw new UsageException(ErrorCode.INVALID_OPTION_VALUE);
        }
        if (confirm.isPresent() && !backupTested) {
            throw new UsageException(MigrationError.RETIRE_BACKUP_NOT_ACKNOWLEDGED);
        }
        Optional<Sha256> confirmed = confirm.map(Retire1xCommand::digest);
        Ledger target = DatabaseBeans.require(ledger);
        if (!target.importer().sourceConfigured()) {
            throw new FailureException(MigrationError.IMPORT_SOURCE_NOT_CONFIGURED);
        }
        Set<String> protectedAccounts = protectedAccounts();
        confirmed.ifPresentOrElse(digest -> retire(target, digest, protectedAccounts),
                () -> list(target, protectedAccounts));
    }

    private void list(Ledger target, Set<String> protectedAccounts) {
        LegacyRetirePlan plan = target.call(() -> execute(() -> target.retirer().plan(protectedAccounts)));
        show(plan);
        output.info(messages.get("retire-1x.listed", plan.schema(), String.valueOf(plan.objects().size()),
                plan.digest().hex()));
    }

    private void retire(Ledger target, Sha256 confirmed, Set<String> protectedAccounts) {
        LegacyRetirePlan plan = target.call(() -> execute(() -> target.retirer().retire(confirmed, protectedAccounts)));
        show(plan);
        output.info(messages.get("retire-1x.removed", String.valueOf(plan.objects().size()), plan.schema()));
    }

    private void show(LegacyRetirePlan plan) {
        for (String line : plan.lines()) {
            output.info(messages.get("retire-1x.object", line));
        }
    }

    /**
     * Runs the service. A refusal of the verification it runs first becomes the refusal of this command ({@link Refusals},
     * context RETIRE) after the rows that the owner needs are printed; a refusal of the service has its own error.
     */
    private LegacyRetirePlan execute(Supplier<LegacyRetirePlan> work) {
        try {
            return work.get();
        } catch (RetireRefusedException refused) {
            throw refusal(refused);
        } catch (LedgerException refused) {
            refusalReport.print(refused);
            throw Refusals.translate(refused, Context.RETIRE);
        }
    }

    private static FailureException refusal(RetireRefusedException refused) {
        List<String> arguments = refused.arguments();
        return switch (refused.refusal()) {
            case OWN_SCHEMA -> new FailureException(MigrationError.RETIRE_OWN_SCHEMA, refused, arguments.getFirst());
            case SESSIONS_CONNECTED -> new FailureException(MigrationError.RETIRE_SESSIONS_CONNECTED, refused,
                    names(arguments), String.valueOf(arguments.size()));
            case NOT_OWNER -> new FailureException(MigrationError.RETIRE_NOT_OWNER, refused, names(arguments),
                    String.valueOf(arguments.size()));
            case SOURCE_CHANGED -> new FailureException(MigrationError.RETIRE_SOURCE_CHANGED, refused, arguments.getFirst());
        };
    }

    /** The first names of a long list, joined; the refusal says how many there are in all. */
    private static String names(List<String> names) {
        return String.join(", ", names.subList(0, Math.min(names.size(), NAMES_SHOWN)));
    }

    private static Sha256 digest(String text) {
        try {
            return Sha256.of(text);
        } catch (ValidationException invalid) {
            throw new UsageException(ErrorCode.INVALID_OPTION_VALUE, invalid);
        }
    }

    /** The accounts of the 2.0 settings: the owner account of this run and the application account. A legacy role of that name stays. */
    private Set<String> protectedAccounts() {
        Set<String> accounts = new TreeSet<>();
        ledgerSettings.stream().map(LedgerJdbcProperties::username).filter(Objects::nonNull).forEach(accounts::add);
        applicationSettings.stream().filter(ApplicationAccountSettings::configured)
                .map(settings -> settings.settings().username()).filter(Objects::nonNull).forEach(accounts::add);
        return accounts;
    }
}
