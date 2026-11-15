package org.pgledger.migrate1x.tool;

import java.util.List;

/**
 * A command was refused. The two permitted kinds decide the exit code in {@link ErrorTranslator}; the arguments are message
 * parameters (paths, names, counts) and never a secret value or user-typed text.
 */
public abstract sealed class ToolException extends RuntimeException permits UsageException, FailureException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode code;
    private final String[] arguments;

    ToolException(ErrorCode code, Throwable cause, String[] arguments) {
        super(code.name(), cause);
        this.code = code;
        this.arguments = arguments.clone();
    }

    public ErrorCode code() {
        return code;
    }

    public List<String> arguments() {
        return List.of(arguments);
    }
}
