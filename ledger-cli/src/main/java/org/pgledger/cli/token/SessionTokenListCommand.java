// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.cli.token;

import java.util.List;
import org.pgledger.cli.CliCommand;
import org.pgledger.cli.CliMessages;
import org.pgledger.cli.CliOutput;
import org.pgledger.cli.Command;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.UsageException;
import org.pgledger.cli.database.CliLedger;
import org.pgledger.cli.database.DatabaseBeans;
import org.pgledger.domain.model.SessionToken;
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
