// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.hookclient;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Entry point of {@code java -jar ledger-client-hook.jar <claude|codex|antigravity>} and {@code ... stop}: the one
 * class that reads the process (the properties, the environment, the standard streams) and hands the exit code to it.
 * It loads nothing but java.base and the classes of this module.
 */
public final class Main {

    /** The environment variable that names the key folder: the contract with the installed host configurations and the owner. */
    static final String KEY_DIR_VARIABLE = "LEDGER_HOOK_KEY_DIR";

    private Main() {}

    public static void main(String[] arguments) {
        System.exit(run(arguments));
    }

    private static int run(String[] arguments) {
        try {
            HookClient hook = HookClient.of(environment());
            return new ClientCommand(hook).run(arguments, System.in, System.out, System.err);
        } catch (IOException | URISyntaxException | IllegalArgumentException unusable) {
            byte[] message = ("ledger: the hook program is not available: " + unusable.getMessage() + "\n")
                    .getBytes(StandardCharsets.UTF_8);
            System.err.write(message, 0, message.length);
            System.err.flush();
            return 2;
        }
    }

    static ClientEnvironment environment() throws URISyntaxException {
        // Resolve the OS process account name lazily in ResidentLauncher only when spawning a resident,
        // avoiding a native ProcessHandle.info().user() lookup on every warm hook invocation.
        return new ClientEnvironment(System.getProperty("user.home"), null,
                System.getenv(KEY_DIR_VARIABLE), Path.of(System.getProperty("java.home"), "bin", "java"),
                Path.of(System.getProperty("java.io.tmpdir")),
                Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI()));
    }
}
