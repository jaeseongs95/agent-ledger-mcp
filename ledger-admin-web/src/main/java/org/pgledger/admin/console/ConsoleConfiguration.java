// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.admin.console;

import java.net.http.HttpClient;
import java.time.Duration;
import org.pgledger.admin.app.LedgerAdminProperties;
import org.pgledger.admin.passkey.PasskeyConfiguration;
import org.pgledger.application.LedgerApplicationConfiguration;
import org.pgledger.database.driver.DriverRegisteredCondition;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * The console: the ledger services and the pages that read and change the ledger. It exists only when
 * {@link DriverRegisteredCondition} holds; otherwise none of its beans (and no DataSource) is created.
 *
 * <p>The scan has the {@link TypeExcludeFilter} that {@code @SpringBootApplication} puts on its own scan: it is the extension
 * point of Spring Boot, and the beans of the context (and the filters of Spring Boot's test support) decide what it leaves
 * out; without such a bean, as in a deployment, it leaves out nothing. The product has no switch of its own for it.
 */
@Configuration(proxyBeanMethods = false)
@Conditional(DriverRegisteredCondition.class)
@Import({LedgerApplicationConfiguration.class, PasskeyConfiguration.class})
@ComponentScan(basePackageClasses = ConsoleConfiguration.class, excludeFilters = @ComponentScan.Filter(
        type = FilterType.CUSTOM, classes = TypeExcludeFilter.class))
public class ConsoleConfiguration {

    /**
     * Shared {@link RestClient} bean for {@link McpHealthClient}, configured once with the configured connect and read
     * timeouts over HTTP/1.1.
     */
    @Bean("mcpHealthRestClient")
    public static RestClient mcpHealthRestClient(LedgerAdminProperties properties, RestClient.Builder builder) {
        Duration timeout = properties.mcp().timeout();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(timeout).build());
        factory.setReadTimeout(timeout);
        return builder.requestFactory(factory).build();
    }
}
