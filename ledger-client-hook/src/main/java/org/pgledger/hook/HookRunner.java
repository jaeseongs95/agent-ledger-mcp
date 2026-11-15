package org.pgledger.hook;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import org.pgledger.hook.keys.SessionKeyStore;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * The one entry of the application: runs one hook invocation with the command line words of the process and the standard
 * streams of {@link ProcessChannels}, and reports the exit code through {@link HookExitCode}. The decisions and the
 * answers are the business of {@link HookCli}; this class is the connection to the process.
 */
@Component
final class HookRunner implements ApplicationRunner {

    private static final int EXIT_BLOCK_OR_USAGE = 2;
    private static final byte[] OUTPUT_FAILED = "ledger: could not write the hook output\n".getBytes(StandardCharsets.UTF_8);

    private final ProcessChannels channels;
    private final HookExitCode exitCode;
    private final Clock clock;
    private final SessionKeyStore keys;

    HookRunner(ProcessChannels channels, HookExitCode exitCode, Clock clock, SessionKeyStore keys) {
        this.channels = channels;
        this.exitCode = exitCode;
        this.clock = clock;
        this.keys = keys;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        HookCli cli = new HookCli(JsonCodec.create(), clock, keys);
        int status = cli.run(arguments.getSourceArgs(), channels.in(), channels.out(), channels.err());
        if (status == 0 && channels.outputFailed()) {
            channels.err().write(OUTPUT_FAILED, 0, OUTPUT_FAILED.length);
            status = EXIT_BLOCK_OR_USAGE;
        }
        exitCode.record(status);
    }
}
