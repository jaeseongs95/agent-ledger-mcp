package org.pgledger.migrate1x.host;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.pgledger.migrate1x.secret.OneXEnvironment;
import org.pgledger.migrate1x.tool.ErrorCode;
import org.pgledger.migrate1x.tool.UsageException;

/**
 * The public host names of a 1.x installation. The clients of a 1.x server reach it through the names its environment file
 * allows ({@code DBHUB_ALLOWED_HOSTS} for the MCP server; {@code PGLEDGER_ADMIN_ALLOWED_HOSTS} for the admin web of 1.1.0), and the
 * Host check of 2.x refuses every other name, so a name that the options of the installation do not carry would break every client
 * right after the migration. {@code stop-1x --env=<file>} compares the names of the file with the {@code --mcp-host} and
 * {@code --admin-host} that the operator will give to the installer of 2.0 and refuses before anything is stopped
 * ({@code HOSTS_DROPPED}, naming the host names, which are no secret, and the option to add them to). Nothing else of the
 * file is used or kept. The command is run by root, which can read the file of root.
 */
public final class Hosts1x {

    /** The setting of 1.x that names the hosts the MCP server answers to. */
    static final String MCP_KEY = "DBHUB_ALLOWED_HOSTS";
    /** The setting of 1.1.0 that names the hosts the admin web answers to (1.0.0 had no admin web). */
    static final String ADMIN_KEY = "PGLEDGER_ADMIN_ALLOWED_HOSTS";

    /** The options of the installer of 2.0 that carry the names of the two services. */
    static final String MCP_OPTION = "mcp-host";
    static final String ADMIN_OPTION = "admin-host";

    /** What the installers of 1.x added to every list: the loopback names need no option, the services of 2.x allow them always. */
    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "::1", "[::1]");

    private Hosts1x() {
    }

    /**
     * Refuses the names when the 1.x file allows a public host name that they do not carry.
     *
     * @param mcpHosts   the names given to the installer with {@code --mcp-host} (empty when the option is not given)
     * @param adminHosts the names given to the installer with {@code --admin-host} (empty when the option is not given)
     * @throws org.pgledger.migrate1x.tool.FailureException ENV_FILE_FAILED when the file cannot be read
     * @throws UsageException HOSTS_DROPPED naming the host names and the option; the names of the MCP server come first
     */
    public static void require(Path file, List<String> mcpHosts, List<String> adminHosts) {
        OneXEnvironment environment = OneXEnvironment.read(file);
        refuseDropped(dropped(environment, MCP_KEY, mcpHosts), MCP_OPTION);
        refuseDropped(dropped(environment, ADMIN_KEY, adminHosts), ADMIN_OPTION);
    }

    private static void refuseDropped(List<String> dropped, String option) {
        if (!dropped.isEmpty()) {
            throw new UsageException(ErrorCode.HOSTS_DROPPED, String.join(", ", dropped), option);
        }
    }

    /**
     * The public names of the setting that the given names do not carry: in lower case (host names are not case sensitive and the
     * Host check of 2.x ignores case), each once, in the order of the file. Blank entries, the loopback names and an absent or
     * empty setting give nothing. A value that is no plain host name (a port, a wildcard) is named as it stands, so that it is
     * never lost without a word.
     */
    static List<String> dropped(OneXEnvironment environment, String key, List<String> given) {
        Set<String> carried = new LinkedHashSet<>();
        given.forEach(name -> carried.add(name.toLowerCase(Locale.ROOT)));
        Set<String> dropped = new LinkedHashSet<>();
        for (String entry : environment.value(key).orElse("").split(",")) {
            String name = entry.strip().toLowerCase(Locale.ROOT);
            if (!name.isEmpty() && !LOOPBACK.contains(name) && !carried.contains(name)) {
                dropped.add(name);
            }
        }
        return List.copyOf(dropped);
    }
}
