// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.cli.grant;

import java.util.List;
import java.util.Set;
import org.agentledger.application.admin.GrantService;
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
 * {@code grant <author> <project> <scope>... [--note=<text>] [--expires=<ISO-8601 with Z or an offset>]}: issues one grant
 * per scope through {@code GrantService}, the service the admin web uses, and records {@code cli:<process user>} in the
 * owner's audit trail. The scopes are {@code issue}, {@code spec}, {@code task}, {@code deliverable} and {@code verify}. A
 * write is accepted only when its author has a live session key or a live session token, so a grant for an author that has
 * neither would never be usable: the service refuses it (the rule is in {@code GrantService}) and the CLI shows NO_SESSION_KEY,
 * which says how to register a key ({@code key-register}) or issue a token ({@code token-issue}). An expiry without a zone is
 * refused by the ledger.
 */
@Component
public class GrantCommand implements Command {

    static final String NOTE = "note";
    static final String EXPIRES = "expires";

    private final ObjectProvider<CliLedger> ledger;
    private final CliOutput output;
    private final CliMessages messages;

    GrantCommand(ObjectProvider<CliLedger> ledger, CliOutput output, CliMessages messages) {
        this.ledger = ledger;
        this.output = output;
        this.messages = messages;
    }

    @Override
    public CliCommand name() {
        return CliCommand.GRANT;
    }

    @Override
    public Set<String> options() {
        return Set.of(NOTE, EXPIRES);
    }

    @Override
    public void run(List<String> operands, ApplicationArguments arguments) {
        if (operands.size() < 3) {
            throw new UsageException(ErrorCode.MISSING_ARGUMENT);
        }
        String note = CliOptions.valueOrNull(arguments, NOTE);
        String expires = CliOptions.valueOrNull(arguments, EXPIRES);
        CliLedger target = DatabaseBeans.require(ledger);
        String author = operands.get(0);
        String project = operands.get(1);
        List<String> scopes = operands.subList(2, operands.size());
        List<Long> issued = target.call(() -> target.grants()
                .grant(new GrantService.GrantCommand(author, project, scopes, expires, note), target.actor()));
        output.info(messages.get("grant.issued", author, project, String.join(",", scopes),
                expires == null ? messages.get("grant.never") : expires,
                String.join(",", issued.stream().map(String::valueOf).toList())));
    }
}
