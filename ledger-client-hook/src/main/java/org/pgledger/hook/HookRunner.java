package org.pgledger.hook;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import org.pgledger.hook.keys.KeyFolder;
import org.pgledger.hook.keys.SessionKeyStore;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The one entry of the application: runs one hook invocation with the command line words of the process and the standard
 * streams of {@link ProcessChannels}, and reports the exit code through {@link HookExitCode}. The decisions and the
 * answers are the business of {@link HookCli}; this class is the connection to the process.
 *
 * <p>The folder of the session keys is the value of the environment variable {@code LEDGER_HOOK_KEY_DIR} if it is
 * set and not blank (it must be an absolute path), otherwise {@code <user.home>/.pg-ledger-mcp/session-keys}
 * ({@link KeyFolder#resolve}). The key store in the {@code keys} package is the only code that uses the folder. The
 * account that must own the key folder is the system property {@code user.name}.
 */
@Component
final class HookRunner implements ApplicationRunner {

    private static final int EXIT_BLOCK_OR_USAGE = 2;
    private static final byte[] OUTPUT_FAILED = "ledger: could not write the hook output\n".getBytes(StandardCharsets.UTF_8);

    private final Environment environment;
    private final ProcessChannels channels;
    private final HookExitCode exitCode;

    HookRunner(Environment environment, ProcessChannels channels, HookExitCode exitCode) {
        this.environment = environment;
        this.channels = channels;
        this.exitCode = exitCode;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        KeyFolder folder = KeyFolder.resolve(environment.getProperty(KeyFolder.ENVIRONMENT_VARIABLE),
                environment.getProperty("user.home"));
        // the account that owns the key folder and the key files: the file system's owner is compared with it
        HookCli cli = new HookCli(JsonCodec.create(), Clock.systemUTC(),
                new SessionKeyStore(folder, environment.getProperty("user.name")));
        int status = cli.run(arguments.getSourceArgs(), channels.in(), channels.out(), channels.err());
        if (status == 0 && channels.outputFailed()) {
            channels.err().write(OUTPUT_FAILED, 0, OUTPUT_FAILED.length);
            status = EXIT_BLOCK_OR_USAGE;
        }
        exitCode.record(status);
    }
}
