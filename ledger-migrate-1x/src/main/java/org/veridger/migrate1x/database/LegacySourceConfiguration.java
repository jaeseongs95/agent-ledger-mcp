// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.migrate1x.database;

import javax.sql.DataSource;
import org.veridger.database.driver.DriverDataSources;
import org.veridger.database.driver.LedgerDataSourceConfiguration;
import org.veridger.database.driver.LedgerJdbcProperties;
import org.veridger.database.driver.RegisteredDriver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * The read side of the legacy import, from the settings {@code ledger.import.source.*}: it exists only when
 * {@code ledger.import.source.url} is set, so an application without those settings never opens a second connection, and
 * a command that needs the legacy ledger finds no {@code LegacySourceRepository} and says so. It needs the registered
 * driver of {@link LedgerDataSourceConfiguration}; the pool closes before the driver's class loader. What is made around the
 * pool (its {@code JdbcClient}, its transaction manager, the repository) is {@link LegacySourceWiring}.
 *
 * <p>Why there is a second {@link DataSource} (principle 3.9, a second data source only where it is genuinely needed): the
 * legacy ledger is another database, with another account, that the import reads while it writes the new schema, so the
 * ledger {@link DataSource} cannot serve it. It is one {@code @Bean} of this configuration, a pool built around the
 * registered driver like the ledger pool ({@link DriverDataSources}), and the container closes it
 * ({@code destroyMethod}); no class holds a pool of its own. It is the one bean that the legacy beans ask for by name
 * ({@link #DATA_SOURCE}); the ledger {@link DataSource} is {@code @Primary}
 * ({@link LedgerDataSourceConfiguration#dataSource}), so every other injection point, and the auto-configuration of Spring
 * Boot that wants a single candidate, still get the ledger database.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "ledger.import.source", name = "url")
@EnableConfigurationProperties(LegacySourceProperties.class)
@Import(LegacySourceWiring.class)
public class LegacySourceConfiguration {

    /** The name of the legacy {@link DataSource} bean, which the legacy beans take by name. */
    public static final String DATA_SOURCE = "legacySourceDataSource";

    /** Two connections: one for the read-only snapshot and one for the metadata of the legacy schema. */
    private static final int CONNECTIONS = 2;

    /**
     * A pool around the registered driver: an external type (Hikari) whose driver is not on the class path of Boot. It is made
     * in this one {@code @Bean} method and the container closes it (principle 3.9).
     */
    @Bean(name = DATA_SOURCE, destroyMethod = "close")
    DataSource legacySourceDataSource(RegisteredDriver driver, LegacySourceProperties properties) {
        return DriverDataSources.create(driver, new LedgerJdbcProperties(properties.url(), properties.username(),
                properties.password(), CONNECTIONS, null), null);
    }
}
