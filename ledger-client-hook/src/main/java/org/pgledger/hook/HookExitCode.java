// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.hook;

import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;

/**
 * The exit code of the process (principle 3.8, CLI): {@link HookRunner} records what the hook invocation ended with,
 * and Boot asks for it when {@code SpringApplication.exit} runs after the runner has finished. 0 until a status is
 * recorded.
 */
@Component
final class HookExitCode implements ExitCodeGenerator {

    private final AtomicInteger status = new AtomicInteger();

    /** The exit code of the process from now on. */
    void record(int exitStatus) {
        status.set(exitStatus);
    }

    @Override
    public int getExitCode() {
        return status.get();
    }
}
