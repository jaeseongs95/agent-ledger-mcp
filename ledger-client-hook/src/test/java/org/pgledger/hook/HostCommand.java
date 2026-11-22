// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.hook;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * The command that a host really runs, read from the {@code hooks.json} of the client plugin: the process tests start the light
 * client with exactly the JVM options of that command, so that what the tests prove is what the hosts get (an option that breaks
 * the clean standard output, or the speed, would be seen by the tests, not only by a user).
 */
final class HostCommand {

    private static final Path CLIENTS = Path.of(System.getProperty("ledger.hook.clients"));
    private static final Pattern OPTIONS = Pattern.compile("^(?:cmd\\.exe /d /c )?java ((?:-\\S+ )*)-jar ");

    private HostCommand() {}

    /** The JVM options of the command of the Codex plugin ({@code command}), between {@code java} and {@code -jar}. */
    static List<String> jvmOptions() {
        return jvmOptions("codex/hooks/hooks.json", "/hooks/PreToolUse/0/hooks/0/command");
    }

    /** The JVM options of the command at this pointer of this hooks file of the clients folder. */
    static List<String> jvmOptions(String hooksFile, String pointer) {
        try {
            JsonNode document = Json.of(Files.readString(CLIENTS.resolve(hooksFile)));
            Matcher matcher = OPTIONS.matcher(document.at(pointer).stringValue());
            if (!matcher.find()) {
                throw new AssertionError("the command at " + pointer + " of " + hooksFile + " is not java <options> -jar ...");
            }
            List<String> options = new ArrayList<>();
            for (String option : matcher.group(1).split(" ")) {
                if (!option.isEmpty()) {
                    options.add(option);
                }
            }
            return options;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
