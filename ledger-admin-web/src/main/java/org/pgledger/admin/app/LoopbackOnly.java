package org.pgledger.admin.app;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.springframework.boot.web.server.ConfigurableWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

/**
 * The admin web listens on loopback ({@code 127.0.0.1}) only, and nothing can change that: it is set on the web server factory in
 * code, after every setting has been applied ({@link Ordered#LOWEST_PRECEDENCE}), so {@code server.address} in a settings file, on the
 * command line or in the environment has no effect. The personal edition is one person on their own computer or server; reaching the
 * admin web from outside is a reverse proxy and a certificate of the operator's choosing, on the same host, which connects to this
 * address and which the forwarded-header settings of {@code application.yml} trust (PCS-011, PCS-012).
 */
@Component
public final class LoopbackOnly implements WebServerFactoryCustomizer<ConfigurableWebServerFactory>, Ordered {

    @Override
    public void customize(ConfigurableWebServerFactory factory) {
        factory.setAddress(address());
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
