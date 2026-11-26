// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.hook.resident;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import org.agentledger.hook.HookExitCode;
import org.agentledger.hookclient.StateFolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * The one entry of the application: {@code start <state-folder> <build>}, which the light client gives when it starts the
 * program. It runs the {@link ResidentServer} until it ends and records the exit code. Anything else on the command line is a
 * usage error: the hosts call the light client, never this program.
 */
@Component
public final class ResidentRunner implements ApplicationRunner {

    /** The first word of the command line. */
    public static final String START = "start";

    private static final Logger LOG = LoggerFactory.getLogger(ResidentRunner.class);
    private static final int EXIT_FAILED = 2;

    private final ResidentServer server;
    private final HookExitCode exitCode;

    ResidentRunner(ResidentServer server, HookExitCode exitCode) {
        this.server = server;
        this.exitCode = exitCode;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        String[] words = arguments.getSourceArgs();
        if (words.length != 3 || !START.equals(words[0])) {
            LOG.error("usage: java -jar ledger-hook-resident.jar start <state-folder> <build> "
                    + "(the hosts run ledger-client-hook.jar, which starts this program)");
            exitCode.record(EXIT_FAILED);
            return;
        }
        try {
            server.run(new StateFolder(Path.of(words[1])), words[2]);
        } catch (IOException | InvalidPathException failure) {
            LOG.error("the resident hook program stops with a failure", failure);
            exitCode.record(EXIT_FAILED);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            exitCode.record(EXIT_FAILED);
        }
    }
}
