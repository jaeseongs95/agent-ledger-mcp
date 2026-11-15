package org.pgledger.migrate1x.secret;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.pgledger.cli.CliMessages;
import org.pgledger.cli.CliOptions;
import org.pgledger.cli.CliOutput;
import org.pgledger.cli.Command;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.FailureException;
import org.pgledger.cli.UsageException;
import org.pgledger.cli.secret.SecretName;
import org.pgledger.cli.secret.SecretVault;
import org.pgledger.cli.secret.StandardInput;
import org.pgledger.migrate1x.tool.MigrationError;
import org.pgledger.migrate1x.tool.ToolCommand;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/**
 * {@code import-secrets-1x (--env=<file> | --stdin) [--replace]}: moves the two secrets of a 1.x installation into the secret
 * store of the 2.0 installation, so that the clients of the old server and the admin sign-in keep working after the migration.
 * {@code DBHUB_AUTH_TOKEN} (the bearer token of the agents) becomes {@code ledger.mcp.token} and
 * {@code ledger.admin.agent-token}, {@code PGLEDGER_ADMIN_TOKEN} becomes {@code ledger.admin.token}. The other settings of the
 * 1.x environment are not read. {@code PGLEDGER_ADMIN_TOKEN} is optional: a 1.0.0 installation had no admin web and its
 * environment has no such setting; then only the two secrets of the agents are stored and one line says that
 * {@code ledger.admin.token} was not imported (the one the installer of 2.0 generated stays).
 *
 * <p>The installer of 2.0 has run before this command (the secret store belongs to the service account, which the installer
 * creates), so the secrets exist already: the tokens it generated were shown once and are not in use by any client yet. Without
 * {@code --replace} a secret that is stored already is kept and never replaced, so a run that stopped half way is completed by
 * the next one; with {@code --replace} the three secrets are replaced by the 1.x values (the services read them at start, so
 * they are restarted afterwards).
 *
 * <p>All values are checked before anything is written ({@code DBHUB_AUTH_TOKEN} present; what is present at least
 * {@value #MINIMUM_LENGTH} characters, the admin token different from the agent token). Only the names of the secrets and of
 * the 1.x settings are printed, never a value, and no refusal holds a value. The command does not use the database.
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

    /** The two settings of the 1.x environment that are read: the name in the file, in the order they are checked and reported. */
    enum OneXKey {
        /** The bearer token of the agents. */
        AGENT("DBHUB_AUTH_TOKEN"),
        /** The sign-in token of the admin web. */
        ADMIN("PGLEDGER_ADMIN_TOKEN");

        private final String setting;

        OneXKey(String setting) {
            this.setting = setting;
        }

        String setting() {
            return setting;
        }
    }

    /** The secrets of the new installation, each with the 1.x setting it comes from; the order is the order of the output. */
    enum ImportedSecret {
        MCP_TOKEN("ledger.mcp.token", OneXKey.AGENT),
        ADMIN_AGENT_TOKEN("ledger.admin.agent-token", OneXKey.AGENT),
        ADMIN_TOKEN("ledger.admin.token", OneXKey.ADMIN);

        private final SecretName secret;
        private final OneXKey source;

        ImportedSecret(String name, OneXKey source) {
            this.secret = new SecretName(name);
            this.source = source;
        }

        SecretName secret() {
            return secret;
        }

        OneXKey source() {
            return source;
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
        Map<OneXKey, String> values = new EnumMap<>(OneXKey.class);
        for (OneXKey key : OneXKey.values()) {
            environment.value(key.setting()).ifPresent(value -> values.put(key, value));
        }
        requireAgentToken(values);
        requireUsable(values);
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
            if (!values.containsKey(imported.source())) {
                // a 1.0.0 installation had no admin web, so its environment has no admin token: the one of 2.0 stays
                output.info(messages.get("import-secrets-1x.no-admin", imported.source().setting(), name));
                continue;
            }
            char[] value = values.get(imported.source()).toCharArray();
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
                    name, imported.source().setting()));
        }
    }

    /** The token of the agents is required (its name is the one named, never a value); the admin token is optional. */
    private static void requireAgentToken(Map<OneXKey, String> values) {
        if (!values.containsKey(OneXKey.AGENT)) {
            throw new FailureException(MigrationError.ENV_KEYS_MISSING, OneXKey.AGENT.setting());
        }
    }

    /** What is present is checked; the admin token only when the 1.x environment has one. */
    private static void requireUsable(Map<OneXKey, String> values) {
        String agent = values.get(OneXKey.AGENT);
        if (agent.length() < MINIMUM_LENGTH) {
            throw new FailureException(MigrationError.INVALID_1X_VALUE, OneXKey.AGENT.setting());
        }
        // absent for a 1.0.0 installation (no admin web); the two tokens guard different things (the agents, the owner's
        // sign-in): one value for both is refused, not carried over
        String admin = values.get(OneXKey.ADMIN);
        if (admin != null && (admin.length() < MINIMUM_LENGTH || Objects.equals(admin, agent))) {
            throw new FailureException(MigrationError.INVALID_1X_VALUE, OneXKey.ADMIN.setting());
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
