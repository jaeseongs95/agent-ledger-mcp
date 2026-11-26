// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.migrate1x.host;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.agentledger.migrate1x.secret.OneXEnvironment;

/**
 * The public host names of a 1.x installation. The clients of a 1.x server reach it through the names its environment file
 * allows ({@code DBHUB_ALLOWED_HOSTS} for the MCP server; {@code PGLEDGER_ADMIN_ALLOWED_HOSTS} for the admin web of 1.1.0), and the
 * Host check of 2.x allows the loopback names and the one name that the installer is given for each service ({@code --mcp-host},
 * {@code --admin-host}: one name each, the form of the installer, {@code DeployForms.isHostName}). {@code stop-1x --env=<file>} tells
 * the operator which names of the file that name does not carry, so that a client that still uses one of them is no surprise
 * after the migration; it refuses nothing for them (the installer cannot take more than one name, so a refusal could never be
 * satisfied). Nothing else of the file is used or kept. The command is run by root, which can read the file of root.
 */
public final class Hosts1x {

    /** The setting of 1.x that names the hosts the MCP server answers to. */
    static final String MCP_KEY = "DBHUB_ALLOWED_HOSTS";
    /** The setting of 1.1.0 that names the hosts the admin web answers to (1.0.0 had no admin web). */
    static final String ADMIN_KEY = "PGLEDGER_ADMIN_ALLOWED_HOSTS";

    /** The options of the installer of 2.0 that carry the name of each service. */
    static final String MCP_OPTION = "mcp-host";
    static final String ADMIN_OPTION = "admin-host";

    /** What the installers of 1.x added to every list: the loopback names need no option, the services of 2.x allow them always. */
    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "::1", "[::1]");

    /**
     * The public names of one service that 2.0 does not accept.
     *
     * @param option the option of the installer that is the service's ({@code mcp-host} or {@code admin-host})
     * @param names  the names in lower case, each once, in the order of the file
     */
    public record NotAccepted(String option, List<String> names) {
    }

    private Hosts1x() {
    }

    /**
     * The names of the 1.x file that the given names do not carry, per service, the MCP server first; a service without such a name is
     * not in the list.
     *
     * @param mcpHost   the name given with {@code --mcp-host}, if any
     * @param adminHost the name given with {@code --admin-host}, if any
     * @throws org.agentledger.cli.FailureException ENV_FILE_FAILED when the file cannot be read
     */
    public static List<NotAccepted> notAccepted(Path file, String mcpHost, String adminHost) {
        OneXEnvironment environment = OneXEnvironment.read(file);
        List<NotAccepted> result = new ArrayList<>();
        add(result, MCP_OPTION, dropped(environment, MCP_KEY, mcpHost));
        add(result, ADMIN_OPTION, dropped(environment, ADMIN_KEY, adminHost));
        return List.copyOf(result);
    }

    private static void add(List<NotAccepted> result, String option, List<String> names) {
        if (!names.isEmpty()) {
            result.add(new NotAccepted(option, names));
        }
    }

    /**
     * The public names of the setting that the given name does not carry: in lower case (host names are not case sensitive and the
     * Host check of 2.x ignores case), each once, in the order of the file. Blank entries, the loopback names and an absent or
     * empty setting give nothing. A value that is no plain host name (a port, a wildcard) is named as it stands, so that it is
     * never lost without a word.
     */
    static List<String> dropped(OneXEnvironment environment, String key, String given) {
        String carried = given == null ? "" : given.toLowerCase(Locale.ROOT);
        Set<String> dropped = new LinkedHashSet<>();
        for (String entry : environment.value(key).orElse("").split(",")) {
            String name = entry.strip().toLowerCase(Locale.ROOT);
            if (!name.isEmpty() && !LOOPBACK.contains(name) && !Objects.equals(carried, name)) {
                dropped.add(name);
            }
        }
        return List.copyOf(dropped);
    }
}
