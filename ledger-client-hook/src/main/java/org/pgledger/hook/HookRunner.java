package org.pgledger.hook;

import java.nio.charset.StandardCharsets;
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

    private final HookCli cli;
    private final ProcessChannels channels;
    private final HookExitCode exitCode;

    HookRunner(HookCli cli, ProcessChannels channels, HookExitCode exitCode) {
        this.cli = cli;
        this.channels = channels;
        this.exitCode = exitCode;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        int status = cli.run(arguments.getSourceArgs(), channels.in(), channels.out(), channels.err());
        // stdout is flushed whatever the status is, and a write that failed (a closed pipe) turns a good answer into exit 2
        boolean outputFailed = channels.outputFailed();
        if (status == 0 && outputFailed) {
            channels.err().write(OUTPUT_FAILED, 0, OUTPUT_FAILED.length);
            status = EXIT_BLOCK_OR_USAGE;
        }
        exitCode.record(status);
    }
}
