// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.hook;

import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;

/**
 * The exit code of the process (principle 3.8, CLI): {@code ResidentRunner} records what the resident program ended with,
 * and Boot asks for it when {@code SpringApplication.exit} runs after the runner has finished. 0 until a status is
 * recorded.
 */
@Component
public final class HookExitCode implements ExitCodeGenerator {

    private final AtomicInteger status = new AtomicInteger();

    /** The exit code of the process from now on. */
    public void record(int exitStatus) {
        status.set(exitStatus);
    }

    @Override
    public int getExitCode() {
        return status.get();
    }
}
