// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.mcp.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * Settings of the MCP server ({@code ledger.mcp.*}). The token is not part of any configuration file: in a
 * deployment it arrives through the secret store (a configtree property source), so its value is never printed.
 *
 * @param token            the bearer token the agents present (at least 16 characters). It is not checked by Bean
 *                         Validation: Boot prints the rejected value of a binding violation, and this one is a secret;
 *                         {@code AgentTokenProvider} checks it and names only the key
 * @param allowedHosts     host names a request may carry in its Host header (case does not matter)
 * @param allowedOrigins   origins a browser request may carry; a request without an Origin header is not a browser request
 * @param maxRequestSize   the largest request body
 * @param codePollInterval how often the common-code revision is read to notice changes made by another process
 * @param authMode         caller authentication mode ({@link AuthMode#STANDARD} by default, or {@link AuthMode#ATTESTED})
 */
@Validated
@ConfigurationProperties(prefix = "ledger.mcp")
public record LedgerMcpProperties(String token,
        @DefaultValue({"127.0.0.1", "localhost"}) @NotEmpty List<String> allowedHosts,
        @DefaultValue List<String> allowedOrigins, @DefaultValue("1MB") @NotNull DataSize maxRequestSize,
        @DefaultValue("5s") @NotNull Duration codePollInterval,
        @DefaultValue("STANDARD") @NotNull AuthMode authMode) {

    @ConstructorBinding
    public LedgerMcpProperties {
        allowedHosts = List.copyOf(allowedHosts);
        allowedOrigins = List.copyOf(allowedOrigins);
        authMode = authMode == null ? AuthMode.STANDARD : authMode;
    }

    public LedgerMcpProperties(String token, List<String> allowedHosts, List<String> allowedOrigins,
            DataSize maxRequestSize, Duration codePollInterval) {
        this(token, allowedHosts, allowedOrigins, maxRequestSize, codePollInterval, AuthMode.STANDARD);
    }

    /** The maximum request size must be at least 1 byte and fit in an in-memory buffer check ({@code maxBytes + 1}). */
    @AssertTrue(message = "ledger.mcp.max-request-size must be between 1B and 2147483646B")
    public boolean isMaxRequestSizeUsable() {
        return maxRequestSize != null && maxRequestSize.toBytes() >= 1L
                && maxRequestSize.toBytes() < Integer.MAX_VALUE;
    }

    /** The common-code poll interval must be strictly positive. */
    @AssertTrue(message = "ledger.mcp.code-poll-interval must be positive")
    public boolean isCodePollIntervalUsable() {
        return codePollInterval != null && !codePollInterval.isNegative() && !codePollInterval.isZero();
    }

    /** The token is a secret: it never appears in a log line or a failure text made from this record. */
    @Override
    public String toString() {
        return "LedgerMcpProperties[token=<hidden>, allowedHosts=" + allowedHosts + ", allowedOrigins="
                + allowedOrigins + ", maxRequestSize=" + maxRequestSize + ", codePollInterval=" + codePollInterval
                + ", authMode=" + authMode + "]";
    }
}
