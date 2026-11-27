// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.mcp.config;

import java.time.Duration;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerStdioDisabledCondition;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * Registers the WebMVC {@link RouterFunction} for {@link BoundedStreamableServerTransportProvider} and validates
 * effective keep-alive and idle-timeout settings.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(McpServerStreamableHttpProperties.class)
@Conditional({McpServerStdioDisabledCondition.class, McpServerAutoConfiguration.EnabledStreamableServerCondition.class})
public class McpTransportConfiguration {

    @Bean
    RouterFunction<ServerResponse> webMvcStreamableServerRouterFunction(
            BoundedStreamableServerTransportProvider provider) {
        return provider.getRouterFunction();
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
