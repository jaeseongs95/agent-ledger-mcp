// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.admin.app;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Objects;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;
import org.springframework.boot.web.server.ConfigurableWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The admin web listens on loopback ({@code 127.0.0.1}) only, and nothing can change that: it is set on the web server factory in
 * code, after every setting has been applied ({@link Ordered#LOWEST_PRECEDENCE}), so {@code server.address} in a settings file, on the
 * command line or in the environment has no effect. The personal edition is one person on their own computer or server; reaching the
 * admin web from outside is a reverse proxy and a certificate of the operator's choosing, on the same host, which connects to this
 * address and which the forwarded-header settings of {@code application.yml} trust (PCS-011, PCS-012). The actuator shares the port of the
 * server: a port of its own is refused at the start (PCS-014).
 */
@Component
public final class LoopbackOnly implements WebServerFactoryCustomizer<ConfigurableWebServerFactory>, Ordered {

    private final Environment environment;

    public LoopbackOnly(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void customize(ConfigurableWebServerFactory factory) {
        requireOnePort();
        factory.setAddress(address());
    }

    /**
     * The actuator ({@code /healthz}) is served on the one port of the server. A port of its own ({@code management.server.port} other
     * than {@code server.port}) would be a second listener that this customizer does not cover (the management server is a child
     * context that does not take it), so the start is refused (MANAGEMENT_PORT_SEPARATE). {@code -1}, which turns the management
     * endpoints off, listens on nothing and is accepted. PCS-014.
     */
    private void requireOnePort() {
        Integer management = environment.getProperty("management.server.port", Integer.class);
        Integer server = environment.getProperty("server.port", Integer.class, 8080);
        if (management != null && management >= 0 && !Objects.equals(management, server)) {
            throw new InfrastructureException(InfrastructureCode.MANAGEMENT_PORT_SEPARATE,
                    List.of(String.valueOf(management), String.valueOf(server)));
        }
    }

    /** The address the server listens on: {@code 127.0.0.1}. */
    public static InetAddress address() {
        try {
            return InetAddress.getByAddress("localhost", new byte[] {127, 0, 0, 1});
        } catch (UnknownHostException e) {
            // a four-byte address is always valid
            throw new IllegalStateException("The loopback address has four bytes", e);
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
