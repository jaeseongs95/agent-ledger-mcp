// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.cli.token;

import java.util.List;
import java.util.Set;
import org.pgledger.application.admin.SessionTokenService;
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
