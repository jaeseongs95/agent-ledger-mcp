// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.migrate1x.secret;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.veridger.cli.CliMessages;
import org.veridger.cli.CliOptions;
import org.veridger.cli.CliOutput;
import org.veridger.cli.Command;
import org.veridger.cli.ErrorCode;
import org.veridger.cli.FailureException;
import org.veridger.cli.UsageException;
import org.veridger.cli.secret.SecretName;
import org.veridger.cli.secret.SecretVault;
import org.veridger.cli.secret.StandardInput;
import org.veridger.migrate1x.tool.MigrationError;
import org.veridger.migrate1x.tool.ToolCommand;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/**
 * {@code import-secrets-1x (--env=<file> | --stdin) [--replace]}: moves the secret of the agents of a 1.x installation into the
 * secret store of the 2.0 installation, so that the clients of the old server keep working after the migration.
 * {@code DBHUB_AUTH_TOKEN} (the bearer token of the agents) becomes {@code ledger.mcp.token} and
 * {@code ledger.admin.agent-token}. The other settings of the 1.x environment are not read. <b>The admin token of 1.x
 * ({@code PGLEDGER_ADMIN_TOKEN}) is not imported</b>: it was chosen by hand and may be weaker than a token that
 * {@code token-generate} makes, and the limit on credential checks of the admin web counts on a token that cannot be guessed;
 * the {@code ledger.admin.token} that the installer generated stays. When the 1.x environment has the setting, one line says so
 * (the name of the setting and of the secret, never a value); it is not read or checked otherwise.
 *
 * <p>The installer of 2.0 has run before this command (the secret store belongs to the service account, which the installer
 * creates), so the secrets exist already: the tokens it generated were shown once and are not in use by any client yet. Without
 * {@code --replace} a secret that is stored already is kept and never replaced, so a run that stopped half way is completed by
 * the next one; with {@code --replace} the two secrets are replaced by the 1.x value (the services read them at start, so
 * they are restarted afterwards).
 *
 * <p>The value is checked before anything is written ({@code DBHUB_AUTH_TOKEN} present and at least
 * {@value #MINIMUM_LENGTH} characters). Only the names of the secrets and of the 1.x settings are printed, never a value, and no
 * refusal holds a value. The command does not use the database.
 *
 * <p>The 1.x file is owned by root with mode 0600 while the secret store belongs to the service account, so the operator opens
 * the file as root and gives it to this command on standard input ({@code --stdin}, at most {@value #MAXIMUM_BYTES} bytes, the
 * file read to its end); {@code --env=<file>} is for an operator who can read the file as the service account.
 */
@Component
public class ImportSecrets1xCommand implements Command {

    static final String ENV = "env";
    static final String STDIN = "stdin";
    static final String REPLACE = "replace";

    /** The one setting of the 1.x environment that is read: the name in the file. */
    static final String AGENT_SETTING = "DBHUB_AUTH_TOKEN";
    /** The sign-in token of the admin web of 1.x: only looked for, to say that it is not imported, never read as a value. */
    static final String ADMIN_SETTING = "PGLEDGER_ADMIN_TOKEN";
    /** The admin token of 2.0, the one the installer made: named in the line that says the 1.x one is not imported. */
    static final String ADMIN_SECRET = "ledger.admin.token";

    /** The secrets of the new installation that take the value of {@link #AGENT_SETTING}; the order is the order of the output. */
    enum ImportedSecret {
        MCP_TOKEN("ledger.mcp.token"),
        ADMIN_AGENT_TOKEN("ledger.admin.agent-token");

        private final SecretName secret;

        ImportedSecret(String name) {
            this.secret = new SecretName(name);
        }

        SecretName secret() {
            return secret;
        }
    }

    /** A 1.x token is 64 hexadecimal characters; anything under this is not a token that was generated. */
    static final int MINIMUM_LENGTH = 16;
    /** The 1.x file has a few lines; this bounds what a wrong input can make the program hold. */
    static final int MAXIMUM_BYTES = OneXEnvironment.MAXIMUM_BYTES;

    private final SecretVault store;
    private final StandardInput standardInput;
    private final CliOutput output;
    private final CliMessages messages;

    ImportSecrets1xCommand(SecretVault store, StandardInput standardInput, CliOutput output, CliMessages messages) {
        this.store = store;
        this.standardInput = standardInput;
        this.output = output;
        this.messages = messages;
    }

    @Override
    public ToolCommand name() {
        return ToolCommand.IMPORT_SECRETS_1X;
    }

    @Override
    public Set<String> options() {
        return Set.of(ENV, STDIN, REPLACE);
    }

    @Override
    public void run(List<String> operands, ApplicationArguments arguments) {
        if (!operands.isEmpty()) {
            throw new UsageException(ErrorCode.TOO_MANY_ARGUMENTS);
        }
        Optional<String> file = CliOptions.value(arguments, ENV);
        boolean fromStandardInput = CliOptions.flag(arguments, STDIN);
        boolean replace = CliOptions.flag(arguments, REPLACE);
        if (file.isPresent() && fromStandardInput) {
            throw new UsageException(ErrorCode.INVALID_OPTION_VALUE);
        }
        if (file.isEmpty() && !fromStandardInput) {
            throw new UsageException(ErrorCode.MISSING_ARGUMENT);
        }
        // no file means --stdin (checked above); standard input is read only then
        OneXEnvironment environment = file.map(name -> OneXEnvironment.read(Path.of(name)))
                .orElseGet(() -> OneXEnvironment.parse(readStandardInput(standardInput.stream())));
        String agent = environment.value(AGENT_SETTING)
                .orElseThrow(() -> new FailureException(MigrationError.ENV_KEYS_MISSING, AGENT_SETTING));
        if (agent.length() < MINIMUM_LENGTH) {
            throw new FailureException(MigrationError.INVALID_1X_VALUE, AGENT_SETTING);
        }
        // every secret is looked at before the first is written: a store that cannot be used refuses with nothing written
        Map<ImportedSecret, Boolean> present = new EnumMap<>(ImportedSecret.class);
        for (ImportedSecret imported : ImportedSecret.values()) {
            present.put(imported, store.exists(imported.secret()));
        }
        List<String> written = new ArrayList<>();
        for (ImportedSecret imported : ImportedSecret.values()) {
            String name = imported.secret().value();
            if (present.get(imported) && !replace) {
                output.info(messages.get("import-secrets-1x.kept", name));
                continue;
            }
            char[] value = agent.toCharArray();
            try {
                store.store(imported.secret(), value);
            } catch (FailureException failure) {
                // every value and every path was checked above, so what can still fail is a write: when an earlier secret is stored
                // already the set is torn (the two tokens of the agents must be one value), and the refusal says what to do
                if (written.isEmpty()) {
                    throw failure;
                }
                throw new FailureException(MigrationError.SECRETS_PARTIAL, failure, String.join(", ", written), name);
            } finally {
                Arrays.fill(value, '\0');
            }
            written.add(name);
            output.info(messages.get(present.get(imported) ? "import-secrets-1x.replaced" : "import-secrets-1x.stored",
                    name, AGENT_SETTING));
        }
        // the admin token of 1.x is not imported (see the class): said when the environment has one, whatever its value is
        if (environment.has(ADMIN_SETTING)) {
            output.info(messages.get("import-secrets-1x.admin-ignored", ADMIN_SETTING, ADMIN_SECRET));
        }
    }

    /** Standard input as the text of the file: the same bound and decoding as {@link OneXEnvironment#read}. */
    private static String readStandardInput(InputStream stream) {
        try {
            return OneXEnvironment.decode(stream.readNBytes(MAXIMUM_BYTES + 1), ErrorCode.STDIN_FAILED);
        } catch (IOException e) {
            throw new FailureException(ErrorCode.STDIN_FAILED, e.getClass().getSimpleName());
        }
    }
}
