// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.mcp.config;

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
 * @param codePollInterval   how often the common-code revision is read to notice changes made by another process
 * @param sessionIdleTimeout how long an inactive Streamable HTTP MCP session may stay in memory before eviction
 * @param maxSessions        maximum number of concurrent Streamable HTTP MCP sessions held in memory
 * @param keepAliveInterval  interval for Streamable HTTP SSE keep-alive pings to detect dead client connections
 */
@Validated
@ConfigurationProperties(prefix = "ledger.mcp")
public record LedgerMcpProperties(String token,
        @DefaultValue({"127.0.0.1", "localhost"}) @NotEmpty List<String> allowedHosts,
        @DefaultValue List<String> allowedOrigins, @DefaultValue("1MB") @NotNull DataSize maxRequestSize,
        @DefaultValue("5s") @NotNull Duration codePollInterval,
        @DefaultValue("30m") @NotNull Duration sessionIdleTimeout,
        @DefaultValue("1000") long maxSessions,
        @DefaultValue("30s") @NotNull Duration keepAliveInterval) {

    public static final Duration DEFAULT_SESSION_IDLE_TIMEOUT = Duration.ofMinutes(30);
    public static final long DEFAULT_MAX_SESSIONS = 1000L;
    public static final Duration DEFAULT_KEEP_ALIVE_INTERVAL = Duration.ofSeconds(30);

    @ConstructorBinding
    public LedgerMcpProperties {
        allowedHosts = List.copyOf(allowedHosts);
        allowedOrigins = List.copyOf(allowedOrigins);
    }

    public LedgerMcpProperties(String token, List<String> allowedHosts, List<String> allowedOrigins,
            DataSize maxRequestSize, Duration codePollInterval) {
        this(token, allowedHosts, allowedOrigins, maxRequestSize, codePollInterval, DEFAULT_SESSION_IDLE_TIMEOUT,
                DEFAULT_MAX_SESSIONS, DEFAULT_KEEP_ALIVE_INTERVAL);
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

    /** The Streamable HTTP session idle timeout must be strictly positive so abandoned sessions are evicted. */
    @AssertTrue(message = "ledger.mcp.session-idle-timeout must be positive")
    public boolean isSessionIdleTimeoutUsable() {
        return sessionIdleTimeout != null && !sessionIdleTimeout.isNegative() && !sessionIdleTimeout.isZero();
    }

    /** The Streamable HTTP session cap must be at least 1 and bounded to prevent unbounded heap retention. */
    @AssertTrue(message = "ledger.mcp.max-sessions must be between 1 and 100000")
    public boolean isMaxSessionsUsable() {
        return maxSessions >= 1L && maxSessions <= 100_000L;
    }

    /** The Streamable HTTP keep-alive interval must be zero (disabled) or strictly positive. */
    @AssertTrue(message = "ledger.mcp.keep-alive-interval must be zero (disabled) or positive")
    public boolean isKeepAliveIntervalUsable() {
        return keepAliveInterval != null && !keepAliveInterval.isNegative();
    }

    /**
     * When keep-alive is enabled ({@code > 0}), the idle timeout must strictly exceed the keep-alive interval so
     * connected clients are not evicted before answering a keep-alive ping.
     */
    @AssertTrue(message = "ledger.mcp.session-idle-timeout must be greater than ledger.mcp.keep-alive-interval")
    public boolean isSessionIdleTimeoutLongerThanKeepAlive() {
        return sessionIdleTimeout != null && keepAliveInterval != null
                && (keepAliveInterval.isZero() || sessionIdleTimeout.compareTo(keepAliveInterval) > 0);
    }

    /** The token is a secret: it never appears in a log line or a failure text made from this record. */
    @Override
    public String toString() {
        return "LedgerMcpProperties[token=<hidden>, allowedHosts=" + allowedHosts + ", allowedOrigins="
                + allowedOrigins + ", maxRequestSize=" + maxRequestSize + ", codePollInterval=" + codePollInterval
                + ", sessionIdleTimeout=" + sessionIdleTimeout + ", maxSessions=" + maxSessions
                + ", keepAliveInterval=" + keepAliveInterval + "]";
    }
}
