package org.pgledger.mcp.config;

/**
 * Caller authentication mode for MCP write and attachment tools.
 */
public enum AuthMode {
    /**
     * Vendor-independent default: accepts per-author HTTP {@code Authorization: Bearer lgt_...} session tokens
     * directly without a client hook, while still verifying signed Ed25519 {@code _hostAttestation} tokens when
     * supplied.
     */
    STANDARD,

    /**
     * Requires a signed Ed25519 {@code _hostAttestation} token bound to the tool name, operation key, and arguments
     * digest on every write, whoami, and session-bound attachment call.
     */
    ATTESTED
}
