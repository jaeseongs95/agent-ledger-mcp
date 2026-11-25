// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.migrate1x.support;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.pgledger.migrate1x.host.ServiceControl;

/**
 * Test double for the service manager (M1X-010 ..): it records every call as one text ({@code systemctl stop pg-ledger-mcp}) and
 * answers it by a function of the command; no process is started and no real service is touched.
 */
public final class ScriptedServiceControl implements ServiceControl {

    private final List<String> calls = new ArrayList<>();
    private final Function<List<String>, Result> answers;

    /** Answers every call with exit code 0 and no output. */
    public ScriptedServiceControl() {
        this(0);
    }

    /** Answers every call with this exit code and no output. */
    public ScriptedServiceControl(int exitCode) {
        this(command -> new Result(exitCode, ""));
    }

    public ScriptedServiceControl(Function<List<String>, Result> answers) {
        this.answers = answers;
    }

    @Override
    public Result run(List<String> command) {
        calls.add(String.join(" ", command));
        return answers.apply(command);
    }

    /** The calls in the order they were made, each as the words of the command joined by a blank. */
    public List<String> calls() {
        return List.copyOf(calls);
    }
}
