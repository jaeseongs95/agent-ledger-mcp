// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.cli.code;

import java.util.List;
import java.util.Set;
import org.agentledger.application.admin.CommonCodeService.CodeCommand;
import org.agentledger.application.admin.CommonCodeService.CodeResult;
import org.agentledger.cli.CliCommand;
import org.agentledger.cli.CliMessages;
import org.agentledger.cli.CliOptions;
import org.agentledger.cli.CliOutput;
import org.agentledger.cli.Command;
import org.agentledger.cli.ErrorCode;
import org.agentledger.cli.UsageException;
import org.agentledger.cli.database.CliLedger;
import org.agentledger.cli.database.DatabaseBeans;
import org.agentledger.domain.rule.CodeState;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/**
 * {@code code-set <group> <code> <name-ko> [<description-ko>] [--name-en=<text>] [--description-en=<text>] [--sort=<n>]
 * [--inactive] [--direction=<direction>]}: adds a common code or updates it through {@code CommonCodeService}, the service
 * the admin web uses. The texts are taken exactly as given (quotes, line breaks, backslashes and text that looks like a
 * placeholder are data). The English texts fall back to the Korean ones; without {@code --sort} an existing code keeps its position and a new one gets
 * the default; the code is ACTIVE unless {@code --inactive} is given. {@code --direction} is for the group RELATION_KIND: GIVEN
 * keeps the two ends of a relation as the caller gave them, CANONICAL (the default of a new code) makes (a, b) and (b, a) one
 * relation; the direction of a kind that has relations cannot change. Setting a code to what it already is changes nothing.
 * A code is never deleted or renamed. The owner's audit trail records {@code cli:<process user>}.
 */
@Component
public class CodeSetCommand implements Command {

    static final String NAME_EN = "name-en";
    static final String DESCRIPTION_EN = "description-en";
    static final String SORT = "sort";
    static final String INACTIVE = "inactive";
    static final String DIRECTION = "direction";

    private final ObjectProvider<CliLedger> ledger;
    private final CliOutput output;
    private final CliMessages messages;

    CodeSetCommand(ObjectProvider<CliLedger> ledger, CliOutput output, CliMessages messages) {
        this.ledger = ledger;
        this.output = output;
        this.messages = messages;
    }

    @Override
    public CliCommand name() {
        return CliCommand.CODE_SET;
    }

    @Override
    public Set<String> options() {
        return Set.of(NAME_EN, DESCRIPTION_EN, SORT, INACTIVE, DIRECTION);
    }

    @Override
    public void run(List<String> operands, ApplicationArguments arguments) {
        if (operands.size() < 3) {
            throw new UsageException(ErrorCode.MISSING_ARGUMENT);
        }
        if (operands.size() > 4) {
            throw new UsageException(ErrorCode.TOO_MANY_ARGUMENTS);
        }
        String descriptionKo = operands.size() == 4 ? operands.get(3) : null;
        String nameEn = CliOptions.valueOrNull(arguments, NAME_EN);
        String descriptionEn = CliOptions.valueOrNull(arguments, DESCRIPTION_EN);
        // an absent --sort is null for the service (an existing code keeps its position, a new one gets the default); a
        // value that is no number is INVALID_OPTION_VALUE with the number format failure as its cause
        Integer sort = CliOptions.integerOrNull(arguments, SORT);
        CodeState state = CliOptions.flag(arguments, INACTIVE) ? CodeState.INACTIVE : CodeState.ACTIVE;
        // the direction of a RELATION_KIND code (GIVEN or CANONICAL); absent, a new code is CANONICAL and an existing code
        // keeps its direction; a value that is neither, or a direction on another group, is refused by the service
        String direction = CliOptions.valueOrNull(arguments, DIRECTION);
        CliLedger target = DatabaseBeans.require(ledger);
        CodeCommand command = new CodeCommand(operands.get(0), operands.get(1), operands.get(2), nameEn, descriptionKo,
                descriptionEn, sort, state.name(), direction);
        CodeResult result = target.call(() -> target.codes().set(command, target.actor()));
        output.info(messages.get("code-set.set", result.code().group().name(), result.code().code().value(),
                result.code().state().name(), String.valueOf(result.code().sortOrder()),
                String.valueOf(result.revision())));
    }
}
