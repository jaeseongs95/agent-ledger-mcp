// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.cli.token;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.veridger.cli.CliCommand;
import org.veridger.cli.CliMessages;
import org.veridger.cli.CliOptions;
import org.veridger.cli.CliOutput;
import org.veridger.cli.Command;
import org.veridger.cli.ErrorCode;
import org.veridger.cli.UsageException;
import org.veridger.cli.secret.SecretName;
import org.veridger.cli.secret.SecretVault;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/**
 * {@code token-generate <name> [--print]}: stores a new random token as the secret {@code <name>} of the secret store (the
 * same owner-only file {@code secret-set} writes, owned by the user that runs the command, in the folder that the name belongs
 * in: {@link org.veridger.cli.secret.SecretFolder}; {@code --copy-to} may name a secret of another folder, which is how the admin
 * web gets the token of the agents without the MCP server getting anything of the admin web). An existing secret is never
 * replaced: a re-run of the installation keeps every token, and the command says so. The value is shown only with
 * {@code --print}, only when it was created by this run, and then once; it is never written to a log, and a token that was
 * kept is never shown (nothing in the product prints a stored secret). The agents' token has to be known to the owner to be
 * put into the client configuration; the admin token has to be known to sign in; both are printed once at the installation.
 */
@Component
public class TokenGenerateCommand implements Command {

    static final String PRINT = "print";
    /** A second name for the same token: the admin web has to know the token of the agents to refuse it. */
    static final String COPY_TO = "copy-to";

    private final SecretVault store;
    private final TokenGenerator generator;
    private final CliOutput output;
    private final CliMessages messages;

    TokenGenerateCommand(SecretVault store, TokenGenerator generator, CliOutput output, CliMessages messages) {
        this.store = store;
        this.generator = generator;
        this.output = output;
        this.messages = messages;
    }

    @Override
    public CliCommand name() {
        return CliCommand.TOKEN_GENERATE;
    }

    @Override
    public Set<String> options() {
        return Set.of(PRINT, COPY_TO);
    }

    @Override
    public void run(List<String> operands, ApplicationArguments arguments) {
        if (operands.isEmpty()) {
            throw new UsageException(ErrorCode.MISSING_ARGUMENT);
        }
        if (operands.size() > 1) {
            throw new UsageException(ErrorCode.TOO_MANY_ARGUMENTS);
        }
        boolean print = CliOptions.flag(arguments, PRINT);
        SecretName name = new SecretName(operands.getFirst());
        Optional<SecretName> copy = CliOptions.value(arguments, COPY_TO).map(SecretName::new);
        if (copy.filter(name::equals).isPresent()) {
            throw new UsageException(ErrorCode.INVALID_OPTION_VALUE);
        }
        // both names are looked at before anything is written: a store that cannot be used refuses with nothing stored
        boolean present = store.exists(name);
        boolean copyPresent = copy.filter(store::exists).isPresent();
        if (present) {
            output.info(messages.get("token-generate.kept", name.value()));
        } else {
            char[] token = generator.generate();
            try {
                Path file = store.store(name, token);
                output.info(messages.get("token-generate.created", name.value(), file.toString()));
                if (print) {
                    output.info(messages.get("token-generate.printed", name.value(), String.valueOf(token)));
                }
            } finally {
                Arrays.fill(token, '\0');
            }
        }
        if (!copyPresent) {
            copy.ifPresent(target -> copyTo(name, target));
        }
    }

    /** The same value under a second name, once: a copy that exists is never replaced (a re-run, or a token that is already in use elsewhere). */
    private void copyTo(SecretName source, SecretName target) {
        char[] value = store.load(source);
        try {
            Path file = store.store(target, value);
            output.info(messages.get("token-generate.copied", source.value(), target.value(), file.toString()));
        } finally {
            Arrays.fill(value, '\0');
        }
    }
}
