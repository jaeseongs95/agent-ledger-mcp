package org.pgledger.cli;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Component;

/** The one place where a refused command becomes an exit status and its texts. */
@Component
public class CliErrorTranslator {

    /** @param action what to do next (the usage text for a usage error), or null */
    public record Report(ExitStatus status, String description, String action) {
    }

    private final CliMessages messages;
    /** The names of the commands of the program, in the order of their enum (the order of the usage text). */
    private final List<CommandName> commands;

    CliErrorTranslator(CliMessages messages, List<Command> available) {
        this.messages = messages;
        this.commands = available.stream().map(Command::name)
                .sorted(Comparator.comparingInt(name -> name instanceof Enum<?> constant ? constant.ordinal() : 0)).toList();
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
        List<CommandName> shown = usage.command().map(List::of).orElse(commands);
        lines.add(messages.get(shown.isEmpty() ? "usage.header" : shown.getFirst().usageHeaderKey()));
        for (CommandName command : shown) {
            lines.add(messages.get(command.usageKey()));
        }
        return String.join("\n", lines);
    }
}
