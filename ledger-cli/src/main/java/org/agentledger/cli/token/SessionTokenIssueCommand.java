// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.cli.token;

import java.util.List;
import java.util.Set;
import org.agentledger.application.admin.SessionTokenService;
import org.agentledger.cli.CliCommand;
import org.agentledger.cli.CliMessages;
import org.agentledger.cli.CliOptions;
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
 * {@code token-issue <author> [--note=<text>] [--expires=<ISO-8601 with Z or an offset>]}: issues a standard HTTP Bearer
 * session token ({@code lgt_...}) for {@code author} through {@link SessionTokenService}, storing only its SHA-256 digest and
 * display prefix, recording {@code TOKEN_ISSUED} as {@code cli:<process user>} in the owner audit trail, and printing the raw
 * bearer token once so it can be placed into any standard MCP client configuration.
 */
@Component
public class SessionTokenIssueCommand implements Command {

    static final String NOTE = "note";
    static final String EXPIRES = "expires";

    private final ObjectProvider<CliLedger> ledger;
    private final CliOutput output;
    private final CliMessages messages;

    SessionTokenIssueCommand(ObjectProvider<CliLedger> ledger, CliOutput output, CliMessages messages) {
        this.ledger = ledger;
        this.output = output;
        this.messages = messages;
    }

    @Override
    public CliCommand name() {
        return CliCommand.TOKEN_ISSUE;
    }

    @Override
    public Set<String> options() {
        return Set.of(NOTE, EXPIRES);
    }

    @Override
    public void run(List<String> operands, ApplicationArguments arguments) {
        if (operands.isEmpty()) {
            throw new UsageException(ErrorCode.MISSING_ARGUMENT);
        }
        if (operands.size() > 1) {
            throw new UsageException(ErrorCode.TOO_MANY_ARGUMENTS);
        }
        String author = operands.getFirst();
        String note = CliOptions.valueOrNull(arguments, NOTE);
        String expires = CliOptions.valueOrNull(arguments, EXPIRES);
        CliLedger target = DatabaseBeans.require(ledger);
        SessionTokenService.IssuedToken issued = target.call(() -> target.tokens()
                .issue(new SessionTokenService.IssueCommand(author, expires, note), target.actor()));
        output.info(messages.get("token-issue.issued", String.valueOf(issued.id()), issued.author(),
                issued.expiresAt() == null ? messages.get("grant.never") : issued.expiresAt().toString(),
                issued.rawToken()));
    }
}
