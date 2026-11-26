// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.mcp.config;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import java.time.Duration;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerStdioDisabledCondition;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * Configures the Streamable HTTP MCP transport with bounded session capacity, idle session eviction, and SSE
 * keep-alive probes. Spring AI's default auto-configuration leaves {@code sessionIdleTimeout} and
 * {@code keepAliveInterval} unset and {@code maxSessions} at 100,000, which allows disconnected client sessions that
 * never send {@code DELETE /mcp} to accumulate in heap until {@code OutOfMemoryError}.
 */
@Configuration(proxyBeanMethods = false)
@Conditional({McpServerStdioDisabledCondition.class, McpServerAutoConfiguration.EnabledStreamableServerCondition.class})
public class McpTransportConfiguration {

    @Bean
    WebMvcStreamableServerTransportProvider webMvcStreamableServerTransportProvider(
            @Qualifier("mcpServerJsonMapper") JsonMapper jsonMapper,
            McpServerStreamableHttpProperties streamableProperties, LedgerMcpProperties mcpProperties) {
        Duration keepAlive = resolveKeepAlive(streamableProperties.getKeepAliveInterval(), mcpProperties);
        return WebMvcStreamableServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(jsonMapper))
                .mcpEndpoint(streamableProperties.getMcpEndpoint())
                .disallowDelete(streamableProperties.isDisallowDelete())
                .keepAliveInterval(keepAlive)
                .sessionIdleTimeout(mcpProperties.sessionIdleTimeout())
                .maxSessions(mcpProperties.maxSessions())
                .build();
    }

    /**
     * Resolves and validates the effective keep-alive interval from either Spring AI's override property or
     * {@code ledger.mcp.keep-alive-interval} (where {@link Duration#ZERO} explicitly disables keep-alive pings).
     */
    public static Duration resolveKeepAlive(Duration streamableOverride, LedgerMcpProperties mcpProperties) {
        Duration keepAlive;
        if (streamableOverride != null) {
            if (streamableOverride.isNegative() || streamableOverride.isZero()) {
                throw new IllegalArgumentException(
                        "spring.ai.mcp.server.streamable-http.keep-alive-interval must be positive");
            }
            keepAlive = streamableOverride;
        } else {
            keepAlive = mcpProperties.keepAliveInterval().isZero() ? null : mcpProperties.keepAliveInterval();
        }
        if (keepAlive != null && mcpProperties.sessionIdleTimeout().compareTo(keepAlive) <= 0) {
            throw new IllegalArgumentException(
                    "ledger.mcp.session-idle-timeout must be greater than the effective keep-alive interval");
        }
        return keepAlive;
    }
}
