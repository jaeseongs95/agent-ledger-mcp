// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.hook;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The MCP server (as the host names it) and the ledger write tool that a host tool name stands for. The hook stamps
 * only a name that its host dialect recognises.
 *
 * <p>This is the value and the check of the server name and allowed gateway upstream prefixes only. How a host spells a
 * tool name (which shapes, which separators, which pseudo servers) is the business of that host and lives in its
 * dialect, so that a new host or a changed spelling is one class and no shared code changes (see {@link HostDialect}).
 * The tool is one of the eighteen known ones, exactly ({@link WriteTool}).
 *
 * @param server the server name, 1 to 100 characters of {@code A-Za-z0-9._-} (a dialect may allow fewer characters in its
 *               own spelling)
 * @param tool   the write tool; the attestation is issued for its canonical name ({@code ledger_<operation>} or {@code artifact_<operation>})
 */
record McpToolName(String server, WriteTool tool) {

    /** The characters of a server name, as the body of a regular expression character class. */
    static final String SERVER_CHARACTERS = "A-Za-z0-9._-";
    /** The longest server name. */
    static final int MAX_SERVER_LENGTH = 100;
    /**
     * Canonical upstream prefixes a gateway or portal may put in front of a tool name even when the client names the
     * MCP server differently (for example, client server {@code veridger} talking to a portal whose upstream is
     * {@code ledger}).
     */
    static final Set<String> GATEWAY_PREFIXES = Set.of("ledger", "veridger");
    /** A server name as the hook accepts it from a host. */
    private static final Pattern SERVER_PATTERN = Pattern.compile("[" + SERVER_CHARACTERS + "]{1," + MAX_SERVER_LENGTH + "}");

    /** Whether the text is a valid MCP server name. */
    static boolean isServerName(String text) {
        return text != null && SERVER_PATTERN.matcher(text).matches();
    }

    /**
     * Whether a gateway upstream prefix is allowed for {@code server}: absent (direct call), identical to the client
     * server name, or one of Veridger's canonical gateway prefixes ({@link #GATEWAY_PREFIXES}).
     */
    static boolean isAllowedGatewayPrefix(String server, String prefix) {
        return prefix == null || Objects.equals(server, prefix) || GATEWAY_PREFIXES.contains(prefix);
    }

    /** Regex alternation of {@link #GATEWAY_PREFIXES}, sorted for deterministic pattern compilation. */
    static String gatewayPrefixAlternation() {
        return GATEWAY_PREFIXES.stream().sorted().collect(Collectors.joining("|"));
    }

    /** The tool of the server for a canonical tool name that a dialect read from a host name; empty unless it is one of the eighteen. */
    static Optional<McpToolName> ofName(String server, String canonicalName) {
        return WriteTool.ofName(canonicalName).map(tool -> new McpToolName(server, tool));
    }
}
