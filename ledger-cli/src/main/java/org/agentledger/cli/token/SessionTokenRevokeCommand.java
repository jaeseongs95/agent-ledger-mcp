// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.cli.token;

import java.util.List;
import org.agentledger.cli.CliCommand;
import org.agentledger.cli.CliMessages;
import org.agentledger.cli.CliOutput;
import org.agentledger.cli.Command;
import org.agentledger.cli.ErrorCode;
import org.agentledger.cli.UsageException;
import org.agentledger.cli.database.CliLedger;
import org.agentledger.cli.database.DatabaseBeans;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/**
 * {@code token-revoke <id>}: revokes one session token by numeric {@code id} through {@code SessionTokenService} (the row
 * stays, {@code revoked_at} is set, and {@code TOKEN_REVOKED} is recorded as {@code cli:<process user>}).
 */
@Component
public class SessionTokenRevokeCommand implements Command {

    private final ObjectProvider<CliLedger> ledger;
    private final CliOutput output;
    private final CliMessages messages;

    SessionTokenRevokeCommand(ObjectProvider<CliLedger> ledger, CliOutput output, CliMessages messages) {
        this.ledger = ledger;
        this.output = output;
        this.messages = messages;
    }

    @Override
    public CliCommand name() {
        return CliCommand.TOKEN_REVOKE;
    }

    @Override
    public void run(List<String> operands, ApplicationArguments arguments) {
        if (operands.isEmpty()) {
            throw new UsageException(ErrorCode.MISSING_ARGUMENT);
        }
        if (operands.size() > 1) {
            throw new UsageException(ErrorCode.TOO_MANY_ARGUMENTS);
        }
        long id = parseId(operands.getFirst());
        CliLedger target = DatabaseBeans.require(ledger);
        boolean changed = target.call(() -> target.tokens().revoke(id, target.actor()));
        output.info(messages.get(changed ? "token-revoke.revoked" : "token-revoke.already", String.valueOf(id)));
    }

    private static long parseId(String raw) {
        try {
            return Long.parseLong(raw.strip());
        } catch (NumberFormatException notANumber) {
            throw new UsageException(ErrorCode.INVALID_OPTION_VALUE, notANumber);
        }
    }
}
