package org.pgledger.migrate1x.host;

import java.util.List;

/**
 * The one boundary to the service manager of the host: a program is run with fixed arguments and its exit code and output come
 * back. {@code stop-1x} composes the calls ({@code systemctl}) and asks this interface to make them, so a test
 * records the calls and answers them, and no real service is stopped in a unit test. The product implementation is
 * {@link SystemServiceControl}, the only class of the tool that starts a process.
 */
public interface ServiceControl {

    /** What a program answered: its exit code and what it wrote (standard output and error, at most 64 KiB). */
    record Result(int exitCode, String output) {
    }

    /**
     * Runs the program (the first word) with the other words as its arguments; no shell is involved.
     *
     * @throws org.pgledger.cli.FailureException SERVICE_COMMAND_FAILED when the program cannot be started or does not
     *         end in time
     */
    Result run(List<String> command);
}
