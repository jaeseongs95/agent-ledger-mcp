// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.unit.DataSize;
import org.veridger.mcp.config.LedgerMcpProperties;
import org.veridger.mcp.config.McpTransportConfiguration;
import org.veridger.mcp.support.McpServerTest;
import org.veridger.mcp.support.McpTestSettings;
import org.veridger.mcp.support.RawHttp;
import org.veridger.mcp.support.RawHttp.Header;
import org.veridger.mcp.support.RawHttp.Reply;

/**
 * Verifies bounded Streamable HTTP session retention, capacity enforcement, automatic idle session eviction, and
 * keep-alive override validation configured by {@link McpTransportConfiguration}.
 */
@McpServerTest
@TestPropertySource(properties = {
    "ledger.test.context=session-eviction",
    "ledger.mcp.session-idle-timeout=400ms",
    "ledger.mcp.max-sessions=3",
    "ledger.mcp.keep-alive-interval=100ms"
})
class SessionEvictionTest {

    private static final Header BEARER = Header.bearer(McpTestSettings.TOKEN);

    @Autowired
    private LedgerMcpProperties properties;

    @Test
    @DisplayName("PERF-MCP-01: abandoned Streamable HTTP sessions are evicted automatically after sessionIdleTimeout")
    void idleSessionIsEvictedAutomatically(RawHttp http) throws InterruptedException {
        assertThat(properties.sessionIdleTimeout()).isEqualTo(Duration.ofMillis(400));
        assertThat(properties.maxSessions()).isEqualTo(3L);
        assertThat(properties.keepAliveInterval()).isEqualTo(Duration.ofMillis(100));

        String session = http.openSession(BEARER);
        assertThat(http.listTools(session, BEARER).status()).isEqualTo(200);

        // Do not touch the session while waiting for eviction (each request refreshes lastAccessTime).
        Thread.sleep(1500L);
        Reply after = http.listTools(session, BEARER);
        assertThat(after.status()).as("idle MCP session must be evicted and return 404").isEqualTo(404);
    }

    @Test
    @DisplayName("PERF-MCP-02: LedgerMcpProperties defaults bound sessions, idle timeout, and keep-alive interval (with 0s opt-out)")
    void defaultsAndValidationHold() {
        LedgerMcpProperties defaults = new LedgerMcpProperties("test-token-0123456789", List.of("127.0.0.1"),
                List.of(), DataSize.ofMegabytes(1), Duration.ofSeconds(5));

        assertThat(defaults.sessionIdleTimeout()).isEqualTo(Duration.ofMinutes(30));
        assertThat(defaults.maxSessions()).isEqualTo(1000L);
        assertThat(defaults.keepAliveInterval()).isEqualTo(Duration.ofSeconds(30));
        assertThat(defaults.isSessionIdleTimeoutUsable()).isTrue();
        assertThat(defaults.isMaxSessionsUsable()).isTrue();
        assertThat(defaults.isKeepAliveIntervalUsable()).isTrue();
        assertThat(defaults.isSessionIdleTimeoutLongerThanKeepAlive()).isTrue();

        LedgerMcpProperties disabledKeepAlive = new LedgerMcpProperties("test-token-0123456789", List.of("127.0.0.1"),
                List.of(), DataSize.ofMegabytes(1), Duration.ofSeconds(5), Duration.ofMinutes(30), 1000L,
                Duration.ZERO);
        assertThat(disabledKeepAlive.isKeepAliveIntervalUsable()).isTrue();
        assertThat(disabledKeepAlive.isSessionIdleTimeoutLongerThanKeepAlive()).isTrue();

        LedgerMcpProperties bad = new LedgerMcpProperties("test-token-0123456789", List.of("127.0.0.1"),
                List.of(), DataSize.ofMegabytes(1), Duration.ofSeconds(5), Duration.ZERO, 0L, Duration.ofSeconds(-1));
        assertThat(bad.isSessionIdleTimeoutUsable()).isFalse();
        assertThat(bad.isMaxSessionsUsable()).isFalse();
        assertThat(bad.isKeepAliveIntervalUsable()).isFalse();

        LedgerMcpProperties inverted = new LedgerMcpProperties("test-token-0123456789", List.of("127.0.0.1"),
                List.of(), DataSize.ofMegabytes(1), Duration.ofSeconds(5), Duration.ofSeconds(10), 100L,
                Duration.ofSeconds(10));
        assertThat(inverted.isSessionIdleTimeoutLongerThanKeepAlive()).isFalse();
    }

    @Test
    @DisplayName("PERF-MCP-03: maxSessions cap rejects sessions above the limit with 503 and recovers after idle eviction")
    void maxSessionsRejectsOverflowAndRecoversAfterEviction(RawHttp http) throws InterruptedException {
        String s1 = http.openSession(BEARER);
        String s2 = http.openSession(BEARER);
        String s3 = http.openSession(BEARER);
        assertThat(List.of(s1, s2, s3)).doesNotContainNull();

        Reply overflow = http.initialize(BEARER);
        assertThat(overflow.status()).as("4th session above max-sessions=3 must be rejected with 503").isEqualTo(503);

        // Wait for the 3 idle sessions to be evicted and confirm capacity recovers cleanly.
        Thread.sleep(1500L);
        String recovered = http.openSession(BEARER);
        try {
            assertThat(http.listTools(recovered, BEARER).status()).isEqualTo(200);
        } finally {
            http.delete("/mcp", BEARER, Header.session(recovered));
        }
    }

    @Test
    @DisplayName("PERF-MCP-04: McpTransportConfiguration validates Spring AI keep-alive overrides and supports 0s opt-out")
    void resolveKeepAliveValidatesOverridesAndSupportsDisable() {
        LedgerMcpProperties defaults = new LedgerMcpProperties("test-token-0123456789", List.of("127.0.0.1"),
                List.of(), DataSize.ofMegabytes(1), Duration.ofSeconds(5));
        assertThat(McpTransportConfiguration.resolveKeepAlive(null, defaults)).isEqualTo(Duration.ofSeconds(30));
        assertThat(McpTransportConfiguration.resolveKeepAlive(Duration.ofSeconds(15), defaults))
                .isEqualTo(Duration.ofSeconds(15));

        LedgerMcpProperties disabled = new LedgerMcpProperties("test-token-0123456789", List.of("127.0.0.1"),
                List.of(), DataSize.ofMegabytes(1), Duration.ofSeconds(5), Duration.ofMinutes(30), 1000L,
                Duration.ZERO);
        assertThat(McpTransportConfiguration.resolveKeepAlive(null, disabled)).isNull();

        assertThatThrownBy(() -> McpTransportConfiguration.resolveKeepAlive(Duration.ZERO, defaults))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("keep-alive-interval");
        assertThatThrownBy(() -> McpTransportConfiguration.resolveKeepAlive(Duration.ofSeconds(-5), defaults))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("keep-alive-interval");
        assertThatThrownBy(() -> McpTransportConfiguration.resolveKeepAlive(Duration.ofMinutes(45), defaults))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("session-idle-timeout");
    }
}
