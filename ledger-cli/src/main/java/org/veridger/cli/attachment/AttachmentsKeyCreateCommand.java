// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.cli.attachment;

import java.util.List;
import org.veridger.application.attachment.AttachmentKeys;
import org.veridger.cli.CliCommand;
import org.veridger.cli.CliMessages;
import org.veridger.cli.CliOutput;
import org.veridger.cli.Command;
import org.veridger.cli.ErrorCode;
import org.veridger.cli.UsageException;
import org.veridger.cli.database.CliLedger;
import org.veridger.cli.database.DatabaseBeans;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/**
 * {@code attachments-key-create} (common design 4.3, key rotation): creates a new 256-bit key file in the key directory (readable
 * by the service account only; on Windows the installing user with everything and LocalService with read) through {@link AttachmentKeys#create()} and prints its key id. The key itself is never printed, and the
 * setting {@code ledger.attachments.active-key-id} is not changed by the command: the owner sets it to the new key id (the servers
 * read it at start) and, to move the existing attachments to the new key, runs {@code attachments-rekey}. An old key stays while
 * rows still use it.
 */
@Component
public class AttachmentsKeyCreateCommand implements Command {

    private final ObjectProvider<CliLedger> ledger;
    private final ObjectProvider<AttachmentKeys> keys;
    private final CliOutput output;
    private final CliMessages messages;

    AttachmentsKeyCreateCommand(ObjectProvider<CliLedger> ledger, ObjectProvider<AttachmentKeys> keys, CliOutput output,
            CliMessages messages) {
        this.ledger = ledger;
        this.keys = keys;
        this.output = output;
        this.messages = messages;
    }

    @Override
    public CliCommand name() {
        return CliCommand.ATTACHMENTS_KEY_CREATE;
    }

    @Override
    public void run(List<String> operands, ApplicationArguments arguments) {
        if (!operands.isEmpty()) {
            throw new UsageException(ErrorCode.TOO_MANY_ARGUMENTS);
        }
        CliLedger target = DatabaseBeans.require(ledger);
        AttachmentKeys attachmentKeys = AttachmentBeans.require(keys);
        String keyId = target.call(attachmentKeys::create);
        output.info(messages.get("attachments-key-create.created", keyId));
    }
}
