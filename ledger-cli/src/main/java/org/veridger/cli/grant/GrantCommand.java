// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.cli.grant;

import java.util.List;
import java.util.Set;
import org.veridger.application.admin.GrantService;
import org.veridger.cli.CliCommand;
import org.veridger.cli.CliMessages;
import org.veridger.cli.CliOptions;
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
 * {@code grant <author> <project> <scope>... [--note=<text>] [--expires=<ISO-8601 with Z or an offset>]}: issues one grant
 * per scope through {@code GrantService}, the service the admin web uses, and records {@code cli:<process user>} in the
 * owner's audit trail. The scopes are {@code issue}, {@code spec}, {@code task}, {@code deliverable} and {@code verify}.
 * Grants can be issued directly for any valid {@code <vendor>:<session_id>} author. An expiry without a zone is refused by
 * the ledger.
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
