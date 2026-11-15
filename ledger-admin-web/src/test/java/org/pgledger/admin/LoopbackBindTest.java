package org.pgledger.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.pgledger.admin.app.LoopbackOnly;
import org.pgledger.admin.support.AdminTestInfrastructure;
import org.pgledger.admin.support.AdminTestSettings;
import org.pgledger.testing.database.TestDatabaseScanFilter;
import org.pgledger.testing.database.TestDriverRegistration;
import org.pgledger.testing.database.TestSchemaInitializer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.filter.annotation.TypeExcludeFilters;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.server.ConfigurableWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.test.context.ContextConfiguration;

/**
 * PCS-011, PCS-012: the admin web listens on loopback ({@code 127.0.0.1}) only, whatever {@code server.address} says. The application
 * of this class is started with {@code server.address=0.0.0.0} (the setting that would open it to the network) and still refuses every
 * connection to an address of this host that is not loopback; the customizer is last in order, so a setting that is applied at the
 * order of the Spring Boot customizers cannot win.
 */
@ContextConfiguration(initializers = {TestSchemaInitializer.class, TestDriverRegistration.class})
@SpringBootTest(classes = LedgerAdminApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {AdminTestSettings.TOKEN_PROPERTY, AdminTestSettings.AGENT_TOKEN_PROPERTY, AdminTestSettings.INSECURE_COOKIE,
            AdminTestSettings.NO_GLOBAL_CAP, AdminTestSettings.QUIET_BANNER, AdminTestSettings.QUIET_LOG,
            AdminTestSettings.NO_JMX, "server.address=0.0.0.0"})
@TypeExcludeFilters(TestDatabaseScanFilter.class)
@Import(AdminTestInfrastructure.class)
class LoopbackBindTest {

    @LocalServerPort
    private int port;

    @Autowired
    private LoopbackOnly loopbackOnly;

    /** A customizer at the order of the one of Spring Boot that applies {@code server.address}: it sets the address of the setting. */
    private record Setting(InetAddress address) implements WebServerFactoryCustomizer<ConfigurableWebServerFactory>, Ordered {

        @Override
        public void customize(ConfigurableWebServerFactory factory) {
            factory.setAddress(address);
        }

        @Override
        public int getOrder() {
            return 0;
        }
    }

    @Test
    @DisplayName("PCS-011: whatever address a setting gives (every interface, an address of the network, IPv6), the customizer sets 127.0.0.1 last")
    void pcs011_theCustomizerIsLastAndLoopback() throws Exception {
        for (String configured : List.of("0.0.0.0", "::", "192.0.2.1")) {
            List<WebServerFactoryCustomizer<ConfigurableWebServerFactory>> customizers = new ArrayList<>();
            customizers.add(loopbackOnly);
            customizers.add(new Setting(InetAddress.getByName(configured)));
            AnnotationAwareOrderComparator.sort(customizers);
            ConfigurableWebServerFactory factory = mock(ConfigurableWebServerFactory.class);

            customizers.forEach(customizer -> customizer.customize(factory));

            ArgumentCaptor<InetAddress> address = forClass(InetAddress.class);
            InOrder order = inOrder(factory);
            order.verify(factory).setAddress(address.capture());
            order.verify(factory).setAddress(address.capture());
            assertThat(address.getAllValues().getFirst().getHostAddress()).as(configured).isEqualTo(
                    InetAddress.getByName(configured).getHostAddress());
            assertThat(address.getAllValues().getLast().getHostAddress()).as(configured).isEqualTo("127.0.0.1");
        }
        assertThat(loopbackOnly.getOrder()).isEqualTo(Ordered.LOWEST_PRECEDENCE);
        assertThat(LoopbackOnly.address().getAddress()).containsExactly((byte) 127, 0, 0, 1);
    }

    @Test
    @DisplayName("PCS-012: application.yml has no server.address (one place decides, in code, and no default that a setting overrides)")
    void pcs012_noAddressInTheSettings() throws IOException {
        assertThat(java.nio.file.Files.readString(java.nio.file.Path.of("src", "main", "resources", "application.yml")))
                .doesNotContainPattern("(?m)^\\s*address:");
    }

    @Test
    @DisplayName("PCS-012: with server.address=0.0.0.0 the admin web accepts a connection on 127.0.0.1 and refuses one on every other address of this host (skipped, an assumption, when the host has no other IPv4 address)")
    void pcs012_onlyLoopbackAccepts() throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(LoopbackOnly.address(), port), 2000);
            assertThat(socket.isConnected()).isTrue();
        }
        List<InetAddress> others = NetworkInterface.networkInterfaces().filter(network -> {
            try {
                return network.isUp() && !network.isLoopback();
            } catch (IOException e) {
                return false;
            }
        }).flatMap(NetworkInterface::inetAddresses).filter(address -> address instanceof Inet4Address && !address.isLoopbackAddress()
                && !address.isLinkLocalAddress()).toList();
        assumeFalse(others.isEmpty(), "this host has no IPv4 address besides loopback: the refusal on another address cannot be tried");

        for (InetAddress other : others) {
            assertThatThrownBy(() -> {
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(other, port), 2000);
                }
            }).as("a connection to %s", other).isInstanceOf(IOException.class);
        }
    }
}
