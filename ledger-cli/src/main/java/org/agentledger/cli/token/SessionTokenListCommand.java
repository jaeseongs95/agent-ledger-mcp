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
import org.agentledger.domain.model.SessionToken;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/**
 * {@code token-list [<author>]}: lists active (unrevoked) session tokens for one author or every author, printing only the
 * token id, author, display prefix, expiry, and note (never the token secret or hash).
 */
@Component
public class SessionTokenListCommand implements Command {

    private final ObjectProvider<CliLedger> ledger;
    private final CliOutput output;
    private final CliMessages messages;

    SessionTokenListCommand(ObjectProvider<CliLedger> ledger, CliOutput output, CliMessages messages) {
        this.ledger = ledger;
        this.output = output;
        this.messages = messages;
    }

    @Override
    public CliCommand name() {
        return CliCommand.TOKEN_LIST;
    }

    @Override
    public void run(List<String> operands, ApplicationArguments arguments) {
        if (operands.size() > 1) {
            throw new UsageException(ErrorCode.TOO_MANY_ARGUMENTS);
        }
        CliLedger target = DatabaseBeans.require(ledger);
        List<SessionToken> tokens = target.call(() -> operands.isEmpty() ? target.tokens().listUnrevoked()
                : target.tokens().live(operands.getFirst()));
        if (tokens.isEmpty()) {
            output.info(messages.get("token-list.none"));
            return;
        }
        for (SessionToken token : tokens) {
            output.info(messages.get("token-list.line", String.valueOf(token.id()), token.authorId().value(),
                    token.tokenPrefix(), token.expiresAt() == null ? messages.get("grant.never")
                            : token.expiresAt().toString(),
                    token.note() == null ? "" : token.note()));
        }
    }
}
