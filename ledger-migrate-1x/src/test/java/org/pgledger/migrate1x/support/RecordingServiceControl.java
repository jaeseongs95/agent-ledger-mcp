package org.pgledger.migrate1x.support;

import java.util.ArrayList;
import java.util.List;
import org.pgledger.migrate1x.host.ServiceControl;

/**
 * Test double for the service manager boundary: it records every call and answers each with a configurable exit code (0 unless
 * told otherwise) and no output, so a test of {@code stop-1x} starts no process.
 */
public final class RecordingServiceControl implements ServiceControl {

    private final List<List<String>> calls = new ArrayList<>();
    private final int exitCode;

    /** A control that answers every call with exit code 0. */
    public RecordingServiceControl() {
        this(0);
    }

    /** A control that answers every call with this exit code. */
    public RecordingServiceControl(int exitCode) {
        this.exitCode = exitCode;
    }

    @Override
    public Result run(List<String> command) {
        calls.add(List.copyOf(command));
        return new Result(exitCode, "");
    }

    /** The calls so far, each as its words, in the order they were made. */
    public List<List<String>> calls() {
        return List.copyOf(calls);
    }
}
