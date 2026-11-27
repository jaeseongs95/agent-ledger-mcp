// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.mcp.security;

import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the size check strictly on the authenticated MCP endpoint, after the security chain (so unauthenticated
 * endpoints such as {@code /healthz} never buffer chunked request bodies).
 */
@Configuration(proxyBeanMethods = false)
public class McpWebConfiguration {

    /**
     * A {@code @Bean} method because a registration bean is the Spring Boot type that puts a filter into the servlet
     * container at a URL pattern and an order, and it is built from values (the pattern and the order), not injected: the
     * filter itself is the component {@link RequestSizeFilter}.
     */
    @Bean
    FilterRegistrationBean<RequestSizeFilter> requestSizeFilterRegistration(RequestSizeFilter filter,
            McpServerStreamableHttpProperties streamableProperties) {
        FilterRegistrationBean<RequestSizeFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns(streamableProperties.getMcpEndpoint());
        registration.setOrder(SecurityFilterProperties.DEFAULT_FILTER_ORDER + 1);
        return registration;
    }
}
