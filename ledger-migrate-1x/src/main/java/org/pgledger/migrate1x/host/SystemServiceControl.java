package org.pgledger.migrate1x.host;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.pgledger.cli.FailureException;
import org.pgledger.migrate1x.tool.MigrationError;
import org.springframework.stereotype.Component;

/**
 * Runs a program of the host with {@link ProcessBuilder}. <b>Why a process and no Java API:</b> the JDK has no API to stop,
 * disable or query a service of the operating system (not a systemd unit), and the service manager of the host is not Java's to
 * talk to; the only way to stop a 1.x service (1.x had Linux installations with systemd units only) is its own tool,
 * {@code systemctl}. This is the one class of the tool that starts a process, the arguments are fixed words of
 * {@link Stop1xCommand} and the validated name of the instance (no shell, no argument from the operator that is not
 * validated), and the tests of the tool never reach it (they record the calls through {@link ServiceControl}).
 *
 * <p>Ceiling: the output of the program is read after it ended (at most {@value #MAX_OUTPUT} bytes are kept); the programs this
 * tool runs print a few lines, so no pipe fills up while the process runs.
 */
@Component
class SystemServiceControl implements ServiceControl {

    /** A service manager answers in seconds; a program that has not ended after this is stopped and reported. */
    private static final Duration TIMEOUT = Duration.ofSeconds(120);
    private static final int MAX_OUTPUT = 64 * 1024;

    @Override
    public Result run(List<String> command) {
        String shown = String.join(" ", command);
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(TIMEOUT.toSeconds(), java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new FailureException(MigrationError.SERVICE_COMMAND_FAILED, shown, "timeout");
            }
            try (InputStream output = process.getInputStream()) {
                return new Result(process.exitValue(), new String(output.readNBytes(MAX_OUTPUT), StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            // only the kind of the failure: the text of an exception may hold more than the operator needs
            throw new FailureException(MigrationError.SERVICE_COMMAND_FAILED, e, shown, e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FailureException(MigrationError.SERVICE_COMMAND_FAILED, e, shown, "interrupted");
        }
    }
}
