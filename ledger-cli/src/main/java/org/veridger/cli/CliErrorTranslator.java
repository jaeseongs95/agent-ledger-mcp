// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.cli;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** The one place where a refused command becomes an exit status and its texts. */
@Component
public class CliErrorTranslator {

    /** @param action what to do next (the usage text for a usage error), or null */
    public record Report(ExitStatus status, String description, String action) {
    }

    private final CliMessages messages;
    /**
     * The commands of the program, asked for only when a usage text is made. The failure analyzer asks for this class after a failed
     * start, possibly while a bean is still being made (the password of the owner account is asked for while the data source is made);
     * the commands are beans that may need the very bean that failed, so asking for them here, at construction, would fail the
     * report a second time. A usage error is thrown by the runner, when every bean exists.
     */
    private final ObjectProvider<Command> available;

    CliErrorTranslator(CliMessages messages, ObjectProvider<Command> available) {
        this.messages = messages;
        this.available = available;
    }

    /** The names of the commands of the program, in the order of their enum (the order of the usage text). */
    private List<CommandName> commands() {
        return available.orderedStream().map(Command::name)
                .sorted(Comparator.comparingInt((CommandName name) -> name instanceof Enum<?> constant ? constant.ordinal() : 0))
                .toList();
    }

    public Report translate(CliException exception) {
        String description = messages.get(exception.code().messageKey(), exception.arguments().toArray(String[]::new));
        return switch (exception) {
            case UsageException usage -> new Report(ExitStatus.USAGE, description, usageText(usage));
            case FailureException failure -> new Report(ExitStatus.FAILURE, description, null);
            case OperatorStepException step -> new Report(ExitStatus.OPERATOR_STEP, description, null);
        };
    }

    /** The usage line of the refused command, or of every command when there is none (no word or a word that is no command). */
    private String usageText(UsageException usage) {
        List<String> lines = new ArrayList<>();
        List<CommandName> shown = usage.command().map(List::of).orElseGet(this::commands);
        lines.add(messages.get(shown.isEmpty() ? "usage.header" : shown.getFirst().usageHeaderKey()));
        for (CommandName command : shown) {
            lines.add(messages.get(command.usageKey()));
        }
        return String.join("\n", lines);
    }
}
