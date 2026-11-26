// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.cli.deploy;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** The names of the options of {@code render-deploy} ({@code --name=value}). */
public final class DeployOptions {

    /** {@code linux} (the default) or {@code windows}: the one option that tells the targets apart. */
    public static final String PLATFORM = "platform";
    /** Windows only: the directory of the installation (jars and service wrappers, settings, secrets, logs). */
    public static final String HOME = "home";
    public static final String JAVA = "java";
    public static final String JDBC_URL = "jdbc-url";
    /** The application account: the one database account of the MCP server, the admin web and the owner commands. */
    public static final String APP_ACCOUNT = "app-account";
    public static final String SCHEMA = "schema";
    public static final String MCP_PORT = "mcp-port";
    public static final String ADMIN_PORT = "admin-port";
    /**
     * The one host name of the MCP server that the operator's reverse proxy passes in the {@code Host} header, allowed in the Host
     * check of the service besides {@code 127.0.0.1} and {@code localhost}, which are always allowed; optional. The service listens
     * on loopback only, and the proxy and its certificate are the operator's.
     */
    public static final String MCP_HOST = "mcp-host";
    /** The one host name of the admin web, in the form and the meaning of {@link #MCP_HOST}; optional. */
    public static final String ADMIN_HOST = "admin-host";
    /**
     * The name of the instance, default {@code agent-ledger-mcp}: the service names ({@code <name>} and {@code <name>-admin}),
     * the Linux service user, the jar, settings and secret directories and the WinSW ids all follow it, so that two instances
     * can live on one host (each with its own ports).
     */
    public static final String NAME = "name";
    /** Not rendered into any file: a directory the files are written below instead of at their own paths. */
    public static final String ROOT = "root";
    /** {@code prepare-host} on Windows: the WinSW executable, copied once for each of the two services; no value on Linux. */
    public static final String WINSW = "winsw";
    /** A flag, not a value: validate every option as a run does, write nothing (the installers call it before they change anything). */
    public static final String CHECK = "check";

    /** The options that take a value, in the order of their usage text. */
    public static final List<String> ALL = List.of(NAME, PLATFORM, HOME, JAVA, JDBC_URL, APP_ACCOUNT, SCHEMA, MCP_PORT, ADMIN_PORT, MCP_HOST, ADMIN_HOST, ROOT);

    /** Every option of the command, as the set the runner checks the command line against. */
    public static final Set<String> NAMES = Stream.concat(ALL.stream(), Stream.of(CHECK))
            .collect(Collectors.toUnmodifiableSet());

    private DeployOptions() {
    }
}
