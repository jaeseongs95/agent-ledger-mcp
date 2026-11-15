package org.pgledger.migrate1x.secret;

import java.io.Console;

import org.pgledger.migrate1x.tool.ErrorCode;
import org.pgledger.migrate1x.tool.FailureException;
import org.springframework.stereotype.Component;

/**
 * The only way a secret value enters the program: {@link Console#readPassword}. Since Java 22 System.console() is also
 * non-null when stdin or stdout is redirected, so an interactive terminal is checked with {@link Console#isTerminal()}.
 */
@Component
public class ConsoleSecretInput implements SecretInput {

    @Override
    public char[] read(String prompt) {
        Console console = System.console();
        if (console == null || !console.isTerminal()) {
            throw new FailureException(ErrorCode.NO_CONSOLE);
        }
        return console.readPassword("%s ", prompt);
    }
}
