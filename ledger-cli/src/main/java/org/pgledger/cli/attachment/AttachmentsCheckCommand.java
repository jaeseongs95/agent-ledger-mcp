// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.cli.attachment;

import java.util.List;
import java.util.Set;
import org.pgledger.application.attachment.AttachmentCheck;
import org.pgledger.cli.CliCommand;
import org.pgledger.cli.CliMessages;
import org.pgledger.cli.CliOptions;
import org.pgledger.cli.CliOutput;
import org.pgledger.cli.Command;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.FailureException;
import org.pgledger.cli.UsageException;
import org.pgledger.cli.database.CliLedger;
import org.pgledger.cli.database.DatabaseBeans;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/**
 * {@code attachments-check [--purge-orphans]} (common design 7): reconciles the attachment folder, the key files and the
 * database through {@link AttachmentCheck}. A file that no row names and that is older than {@code orphan-grace} and a file that
 * a re-encryption replaced are moved under {@code orphans/} (never deleted); a quarantined file whose row exists again (after a
 * restore) is moved back. The report gives the counts and the ids of the attachments that are missing, damaged or referenced by
 * no history row, and never a storage key or a path. The exit code is 0 when no attachment is missing or damaged, and 1
 * (ATTACHMENTS_NOT_CLEAN) otherwise. {@code --purge-orphans} first empties {@code orphans/} and records the count in the
 * owner's audit trail; nothing else deletes a file, and there is no option that removes the files of the check.
 */
@Component
public class AttachmentsCheckCommand implements Command {

    static final String PURGE_ORPHANS = "purge-orphans";

    private final ObjectProvider<CliLedger> ledger;
    private final ObjectProvider<AttachmentCheck> check;
    private final CliOutput output;
    private final CliMessages messages;

    AttachmentsCheckCommand(ObjectProvider<CliLedger> ledger, ObjectProvider<AttachmentCheck> check, CliOutput output,
            CliMessages messages) {
        this.ledger = ledger;
        this.check = check;
        this.output = output;
        this.messages = messages;
    }

    @Override
    public CliCommand name() {
        return CliCommand.ATTACHMENTS_CHECK;
    }

    @Override
    public Set<String> options() {
        return Set.of(PURGE_ORPHANS);
    }

    @Override
    public void run(List<String> operands, ApplicationArguments arguments) {
        if (!operands.isEmpty()) {
            throw new UsageException(ErrorCode.TOO_MANY_ARGUMENTS);
        }
        boolean purge = CliOptions.flag(arguments, PURGE_ORPHANS);
        CliLedger target = DatabaseBeans.require(ledger);
        AttachmentCheck attachments = AttachmentBeans.require(check);
        if (purge) {
            int deleted = target.call(() -> attachments.purgeQuarantine(target.actor()));
            output.info(messages.get("attachments-check.purged", String.valueOf(deleted)));
        }
        AttachmentCheck.Report report = target.call(() -> attachments.run(true));
        output.info(messages.get("attachments-check.summary", String.valueOf(report.attachments()),
                String.valueOf(report.missing().size()), String.valueOf(report.damaged().size()),
                String.valueOf(report.unreferenced().size())));
        output.info(messages.get("attachments-check.files", String.valueOf(report.orphansQuarantined()),
                String.valueOf(report.orphansWaiting()), String.valueOf(report.replacedQuarantined()),
                String.valueOf(report.restored()), String.valueOf(report.quarantined())));
        for (String id : report.missing()) {
            output.info(messages.get("attachments-check.missing", id));
        }
        for (String id : report.damaged()) {
            output.info(messages.get("attachments-check.damaged", id));
        }
        for (String id : report.unreferenced()) {
            output.info(messages.get("attachments-check.unreferenced", id));
        }
        if (!report.clean()) {
            throw new FailureException(ErrorCode.ATTACHMENTS_NOT_CLEAN, String.valueOf(report.missing().size()),
                    String.valueOf(report.damaged().size()));
        }
    }
}
