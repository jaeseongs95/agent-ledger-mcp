// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.cli;

import java.util.List;

/**
 * A command was refused. The three permitted kinds decide the exit code in {@link CliErrorTranslator}; the arguments are
 * message parameters (paths, modes) and never a secret value or user-typed text.
 */
public abstract sealed class CliException extends RuntimeException permits UsageException, FailureException, OperatorStepException {

    private static final long serialVersionUID = 1L;

    private final ErrorKey code;
    private final String[] arguments;

    CliException(ErrorKey code, Throwable cause, String[] arguments) {
        super(code.name(), cause);
        this.code = code;
        this.arguments = arguments.clone();
    }

    public ErrorKey code() {
        return code;
    }

    public List<String> arguments() {
        return List.of(arguments);
    }
}
