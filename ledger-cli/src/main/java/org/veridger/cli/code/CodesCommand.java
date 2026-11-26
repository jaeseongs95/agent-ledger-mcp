// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.cli.code;

import java.util.Arrays;
import java.util.List;
import org.veridger.cli.CliCommand;
import org.veridger.cli.CliMessages;
import org.veridger.cli.CliOutput;
import org.veridger.cli.Command;
import org.veridger.cli.ErrorCode;
import org.veridger.cli.UsageException;
import org.veridger.cli.database.CliLedger;
import org.veridger.cli.database.DatabaseBeans;
import org.veridger.domain.model.CommonCode;
import org.veridger.domain.rule.CodeGroup;
import org.veridger.domain.value.Tokens;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/**
 * {@code codes [<group>]}: lists the common codes of one group, or of every group, one line each: group, code, state, sort
 * order and the names in both languages (the texts as stored); a relation kind adds its direction (GIVEN or CANONICAL).
 */
@Component
public class CodesCommand implements Command {

    private final ObjectProvider<CliLedger> ledger;
    private final CliOutput output;
    private final CliMessages messages;

    CodesCommand(ObjectProvider<CliLedger> ledger, CliOutput output, CliMessages messages) {
        this.ledger = ledger;
        this.output = output;
        this.messages = messages;
    }

    @Override
    public CliCommand name() {
        return CliCommand.CODES;
    }

    @Override
    public void run(List<String> operands, ApplicationArguments arguments) {
        if (operands.size() > 1) {
            throw new UsageException(ErrorCode.TOO_MANY_ARGUMENTS);
        }
        CliLedger target = DatabaseBeans.require(ledger);
        List<CodeGroup> groups = target.call(() -> operands.isEmpty() ? Arrays.asList(CodeGroup.values())
                : List.of(Tokens.parse("group", operands.getFirst(), CodeGroup.values())));
        for (CodeGroup group : groups) {
            List<CommonCode> codes = target.call(() -> target.codes().list(group));
            for (CommonCode code : codes) {
                // a relation kind also says how a relation of it keeps its two ends; the other groups have no direction
                String key = group == CodeGroup.RELATION_KIND ? "codes.line.relation" : "codes.line";
                output.info(messages.get(key, group.name(), code.code().value(), code.state().name(),
                        String.valueOf(code.sortOrder()), code.nameKo(), code.nameEn(), code.direction().name()));
            }
        }
    }
}
