// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.migrate1x.command;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.agentledger.cli.CliMessages;
import org.agentledger.cli.CliOptions;
import org.agentledger.cli.CliOutput;
import org.agentledger.cli.Command;
import org.agentledger.cli.ErrorCode;
import org.agentledger.cli.FailureException;
import org.agentledger.cli.UsageException;
import org.agentledger.cli.database.DatabaseBeans;
import org.agentledger.domain.error.LedgerException;
import org.agentledger.migrate1x.service.ImportReport;
import org.agentledger.migrate1x.service.LegacyImportService;
import org.agentledger.migrate1x.service.TableCheck;
import org.agentledger.migrate1x.tool.MigrationError;
import org.agentledger.migrate1x.tool.ToolCommand;
import org.agentledger.migrate1x.wiring.Ledger;
import org.agentledger.migrate1x.wiring.Refusals;
import org.agentledger.migrate1x.wiring.Refusals.Context;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/**
 * {@code import-1x [--verify-only] [--owner-account=<account>] [--stdin]}: copies the 1.x ledger (the old schema {@code ledger},
 * read only) into the new schema through {@link LegacyImportService} and prints, per table, the number of rows and the SHA-256
 * digest that the 1.x table and the new table share. The 1.x connection is the settings {@code ledger.import.source.*} (the
 * password is the secret {@code ledger.import.source.password}, read from the secret store like every other secret and
 * never printed). The command is refused when the new schema holds more than {@code migrate} and {@code setup} made (ledger data,
 * a session key or the audit row of an earlier import); the grants are copied and work immediately once the client hook
 * stamps the session author id. The audit row names
 * {@code cli:<process user>}. The request receipts of 1.x are not copied (a 2.0 write needs an {@code operationKey} and refuses the old {@code request_id}, so a lost 1.x answer cannot cause a second write).
 *
 * <p>Every refusal of the service is a refusal of this command with its own code (exit 1): the data that cannot be imported as it
 * is is printed one value per line first ({@code table[key].column} and the reason), the differing tables of a failed comparison
 * as well. The work of the service is one transaction, so every refusal leaves nothing written. {@code --verify-only}
 * writes nothing at all: it repeats the validation and the comparison against what the new schema holds now.
 */
@Component
public class Import1xCommand implements Command {

    static final String VERIFY_ONLY = "verify-only";
    /** The account the whole ledger is written as: the owner account, not the account of an application (INS-012). */
    static final String OWNER_ACCOUNT = "owner-account";
    /** Read by {@code OwnerAccountEnvironmentPostProcessor}: the password of the owner account is the next line of standard input. */
    static final String STDIN = "stdin";

    private final ObjectProvider<Ledger> ledger;
    private final CliOutput output;
    private final CliMessages messages;
    private final RefusalReport refusalReport;

    Import1xCommand(ObjectProvider<Ledger> ledger, CliOutput output, CliMessages messages, RefusalReport refusalReport) {
        this.ledger = ledger;
        this.output = output;
        this.messages = messages;
        this.refusalReport = refusalReport;
    }

    @Override
    public ToolCommand name() {
        return ToolCommand.IMPORT_1X;
    }

    @Override
    public Set<String> options() {
        return Set.of(VERIFY_ONLY, OWNER_ACCOUNT, STDIN);
    }

    @Override
    public void run(List<String> operands, ApplicationArguments arguments) {
        if (!operands.isEmpty()) {
            throw new UsageException(ErrorCode.TOO_MANY_ARGUMENTS);
        }
        boolean verifyOnly = CliOptions.flag(arguments, VERIFY_ONLY);
        // the account itself is read before the context starts (OwnerAccountEnvironmentPostProcessor); a blank one is refused here
        Optional<String> ownerAccount = CliOptions.value(arguments, OWNER_ACCOUNT);
        // --stdin gives the password of the owner account: with no such account it has no use
        if (CliOptions.flag(arguments, STDIN) && ownerAccount.isEmpty()) {
            throw new UsageException(ErrorCode.INVALID_OPTION_VALUE);
        }
        Ledger target = DatabaseBeans.require(ledger);
        LegacyImportService importer = target.importer();
        if (!importer.sourceConfigured()) {
            throw new FailureException(MigrationError.IMPORT_SOURCE_NOT_CONFIGURED);
        }
        ImportReport report = target.call(() -> execute(target, importer, verifyOnly));
        for (TableCheck check : report.tables()) {
            output.info(messages.get("import-1x.table", check.table().targetName(),
                    String.valueOf(check.targetRows()), check.targetDigest().hex()));
        }
        output.info(messages.get(verifyOnly ? "import-1x.verified" : "import-1x.imported",
                String.valueOf(report.rows()), String.valueOf(report.tables().size()), report.schema()));
        // 1.x did not keep which defect the caller of a link named first: the relations of a kind that 2.0 keeps with a
        // direction are imported as stored and listed here, one per line, for the owner to check (D-0016)
        if (!report.relationsOfUnknownDirection().isEmpty()) {
            output.info(messages.get("import-1x.direction",
                    String.valueOf(report.relationsOfUnknownDirection().size())));
            for (String relation : report.relationsOfUnknownDirection()) {
                output.info(messages.get("import-1x.direction.relation", relation));
            }
        }
    }

    /**
     * Runs the service; a refusal of the ledger becomes the refusal of this command ({@link Refusals}, context IMPORT), after what
     * the owner needs to find the rows is printed: the values that cannot be imported, one per line, or the tables of a failed
     * comparison.
     */
    private ImportReport execute(Ledger target, LegacyImportService importer, boolean verifyOnly) {
        try {
            return verifyOnly ? importer.verify() : importer.importLegacy(target.actor());
        } catch (LedgerException refused) {
            refusalReport.print(refused);
            throw Refusals.translate(refused, Context.IMPORT);
        }
    }
}
