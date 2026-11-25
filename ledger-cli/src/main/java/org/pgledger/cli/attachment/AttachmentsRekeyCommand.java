// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.cli.attachment;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.pgledger.application.attachment.AttachmentProperties;
import org.pgledger.application.attachment.AttachmentRekey;
import org.pgledger.cli.CliCommand;
import org.pgledger.cli.CliMessages;
import org.pgledger.cli.CliOptions;
import org.pgledger.cli.CliOutput;
import org.pgledger.cli.Command;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.UsageException;
import org.pgledger.cli.database.CliLedger;
import org.pgledger.cli.database.DatabaseBeans;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/**
 * {@code attachments-rekey --from=<keyId>} (common design 4.3): writes every current file that was written with the key
 * {@code <keyId>} again under the active key ({@link AttachmentRekey}) and says how many files were written and how many were
 * skipped because another run had replaced them meanwhile. The key that is named has to differ from the active key. The old
 * files become replaced files: {@code attachments-check} moves them under {@code orphans/} once {@code orphan-grace} has passed
 * since the new row, which the message tells the owner. The key itself is never printed.
 */
@Component
public class AttachmentsRekeyCommand implements Command {

    static final String FROM = "from";

    private final ObjectProvider<CliLedger> ledger;
    private final ObjectProvider<AttachmentRekey> rekey;
    private final ObjectProvider<AttachmentProperties> properties;
    private final CliOutput output;
    private final CliMessages messages;

    AttachmentsRekeyCommand(ObjectProvider<CliLedger> ledger, ObjectProvider<AttachmentRekey> rekey,
            ObjectProvider<AttachmentProperties> properties, CliOutput output, CliMessages messages) {
        this.ledger = ledger;
        this.rekey = rekey;
        this.properties = properties;
        this.output = output;
        this.messages = messages;
    }

    @Override
    public CliCommand name() {
        return CliCommand.ATTACHMENTS_REKEY;
    }

    @Override
    public Set<String> options() {
        return Set.of(FROM);
    }

    @Override
    public void run(List<String> operands, ApplicationArguments arguments) {
        if (!operands.isEmpty()) {
            throw new UsageException(ErrorCode.TOO_MANY_ARGUMENTS);
        }
        String from = CliOptions.required(arguments, FROM);
        CliLedger target = DatabaseBeans.require(ledger);
        AttachmentRekey attachments = AttachmentBeans.require(rekey);
        String active = AttachmentBeans.require(properties).activeKeyId();
        if (active == null) {
            // no key is active yet (setup has not made the first one): there is no key to write the files under
            throw new UsageException(ErrorCode.ATTACHMENTS_NOT_CONFIGURED);
        }
        if (Objects.equals(from, active)) {
            throw new UsageException(ErrorCode.ATTACHMENTS_REKEY_FROM_ACTIVE, from);
        }
        AttachmentRekey.Result result = target.call(() -> attachments.rekey(target.actor(), from));
        output.info(messages.get("attachments-rekey.done", String.valueOf(result.rewritten()), from, active,
                String.valueOf(result.skipped())));
    }
}
