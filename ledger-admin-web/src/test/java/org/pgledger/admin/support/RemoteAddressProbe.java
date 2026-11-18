// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.admin.support;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/**
 * Records the client address that the application sees for each request of {@code /api/session}: the remote address after
 * Tomcat has applied (or has not applied) the forwarding header, which is what the application would write to a log or an audit
 * row. It is a filter of the test class path in front of the security chain, so nothing is added to the product to observe it.
 */
public final class RemoteAddressProbe implements Filter {

    private final List<String> seen = new CopyOnWriteArrayList<>();

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        if (request instanceof HttpServletRequest http && http.getRequestURI().endsWith("/api/session")) {
            seen.add(http.getRemoteAddr());
        }
        chain.doFilter(request, response);
    }

    /** The addresses seen so far, in the order of the requests. */
    public List<String> seen() {
        return List.copyOf(seen);
    }

    /** Registers the probe in front of everything else. */
    @TestConfiguration(proxyBeanMethods = false)
    public static class Configuration {

        @Bean
        RemoteAddressProbe remoteAddressProbe() {
            return new RemoteAddressProbe();
        }

        @Bean
        FilterRegistrationBean<RemoteAddressProbe> remoteAddressProbeRegistration(RemoteAddressProbe probe) {
            FilterRegistrationBean<RemoteAddressProbe> registration = new FilterRegistrationBean<>(probe);
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
            return registration;
        }
    }
}
