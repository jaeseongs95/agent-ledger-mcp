// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.cli.deploy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Runs the two child processes required by the privileged host updater. */
final class HostUpdateProcess {
    private static final Set<String> JAVA_OPTIONS = Set.of("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS");

    private HostUpdateProcess() {
    }

    static void backup(List<String> arguments, Map<String, String> environment, Path log) throws IOException, InterruptedException {
        var process = new ProcessBuilder(arguments);
        process.environment().keySet().removeIf(key -> key.toUpperCase(Locale.ROOT).startsWith("PG"));
        process.environment().putAll(environment);
        execute(process, log, "PostgreSQL backup failed");
    }

    static void migrate(List<String> arguments, Path directory, Path secret, Path log) throws IOException, InterruptedException {
        var process = new ProcessBuilder(arguments);
        process.directory(directory.toFile());
        process.environment().keySet().removeIf(key -> {
            String name = key.toUpperCase(Locale.ROOT);
            return name.startsWith("LEDGER_") || name.startsWith("SPRING_") || JAVA_OPTIONS.contains(name);
        });
        process.redirectInput(secret.toFile());
        execute(process, log, "Database migration failed");
    }

    private static void execute(ProcessBuilder process, Path log, String error) throws IOException, InterruptedException {
        Process running = process.redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            if (running.waitFor() != 0) {
                throw new IOException(error + " (see " + log + ")");
            }
        } finally {
            if (running.isAlive()) {
                running.destroyForcibly();
            }
        }
    }
}
