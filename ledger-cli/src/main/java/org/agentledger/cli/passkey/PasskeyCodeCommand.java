// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.cli.passkey;

import java.util.List;
import java.util.Set;
import org.agentledger.application.admin.PasskeyService.IssuedCode;
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
 * {@code passkey-code [--remove-existing]}: issues the one-time code that enrols a passkey of the owner in the admin web (the
 * first passkey of an installation, another one, or the first after a reset). The owner runs it on the server, as the service
 * account; that the owner can run it there is the proof that the owner is the owner. The code is printed once, lives fifteen
 * minutes and is stored only as its hash; asking again ends the earlier unused code. {@code --remove-existing} closes every live
 * passkey first (the owner lost them all): without the flag no passkey is touched. Both are recorded in the owner's audit trail
 * with {@code cli:<process user>}, through {@code PasskeyService}, the service the admin web uses.
 *
 * <p>The tool is a Java program because the product is: the code is made with {@code SecureRandom} by the same service that
 * checks it.
 */
@Component
public class PasskeyCodeCommand implements Command {

    static final String REMOVE_EXISTING = "remove-existing";

    private final ObjectProvider<CliLedger> ledger;
    private final CliOutput output;
    private final CliMessages messages;

    PasskeyCodeCommand(ObjectProvider<CliLedger> ledger, CliOutput output, CliMessages messages) {
        this.ledger = ledger;
        this.output = output;
        this.messages = messages;
    }

    @Override
    public CliCommand name() {
        return CliCommand.PASSKEY_CODE;
    }

    @Override
    public Set<String> options() {
        return Set.of(REMOVE_EXISTING);
    }

    @Override
    public void run(List<String> operands, ApplicationArguments arguments) {
        if (!operands.isEmpty()) {
            throw new UsageException(ErrorCode.TOO_MANY_ARGUMENTS);
        }
        boolean removeExisting = CliOptions.flag(arguments, REMOVE_EXISTING);
        CliLedger target = DatabaseBeans.require(ledger);
        IssuedCode issued = target.call(() -> target.passkeys().issueCode(target.actor(), removeExisting));
        if (removeExisting) {
            output.info(messages.get("passkey-code.removed", String.valueOf(issued.closedPasskeys())));
        }
        output.info(messages.get("passkey-code.issued", issued.code(), issued.expiresAt().toString()));
    }
}
