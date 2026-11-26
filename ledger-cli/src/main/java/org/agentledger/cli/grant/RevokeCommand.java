// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.cli.grant;

import java.util.List;
import org.agentledger.application.admin.GrantService;
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
 * {@code revoke <author> [<project> [<scope>]]}: revokes the live grants of an author through {@code GrantService}: all of
 * them, those of one project, or the one of a project and scope (a scope needs its project; the ledger refuses it
 * otherwise). Rows are never deleted. It prints how many grants were revoked, which may be none, and records
 * {@code cli:<process user>} in the owner's audit trail.
 */
@Component
public class RevokeCommand implements Command {

    private final ObjectProvider<CliLedger> ledger;
    private final CliOutput output;
    private final CliMessages messages;

    RevokeCommand(ObjectProvider<CliLedger> ledger, CliOutput output, CliMessages messages) {
        this.ledger = ledger;
        this.output = output;
        this.messages = messages;
    }

    @Override
    public CliCommand name() {
        return CliCommand.REVOKE;
    }

    @Override
    public void run(List<String> operands, ApplicationArguments arguments) {
        if (operands.isEmpty()) {
            throw new UsageException(ErrorCode.MISSING_ARGUMENT);
        }
        if (operands.size() > 3) {
            throw new UsageException(ErrorCode.TOO_MANY_ARGUMENTS);
        }
        CliLedger target = DatabaseBeans.require(ledger);
        String author = operands.get(0);
        String project = operands.size() > 1 ? operands.get(1) : null;
        String scope = operands.size() > 2 ? operands.get(2) : null;
        int revoked = target.call(() -> target.grants()
                .revoke(new GrantService.RevokeCommand(author, project, scope), target.actor()));
        output.info(messages.get("revoke.revoked", String.valueOf(revoked), author));
    }
}
