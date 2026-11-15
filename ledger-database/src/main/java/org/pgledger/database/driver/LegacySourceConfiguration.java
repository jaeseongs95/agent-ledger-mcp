package org.pgledger.database.driver;

import javax.sql.DataSource;
import org.pgledger.database.repository.LegacySourceRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * The read side of the legacy import, from the settings {@code ledger.import.source.*}: it exists only when
 * {@code ledger.import.source.url} is set, so an application without those settings never opens a second connection, and
 * a command that needs the legacy ledger finds no {@link LegacySourceRepository} and says so. It needs the registered
 * driver of {@link LedgerDataSourceConfiguration}; the pool closes before the driver's class loader. The repository is a
 * component that this configuration imports (and so only exists with the settings): it is left out of the scan of the
 * repository package for that reason.
 *
 * <p>Why there is a second {@link DataSource} (principle 3.9, a second data source only where it is genuinely needed): the
 * legacy ledger is another database, with another account, that the import reads while it writes the new schema, so the
 * ledger {@link DataSource} cannot serve it. It is one {@code @Bean} of this configuration, a pool built around the
 * registered driver like the ledger pool ({@link DriverDataSources}), and the container closes it
 * ({@code destroyMethod}); no class holds a pool of its own. It is the one bean that the repository asks for by name
 * ({@link #DATA_SOURCE}); the ledger {@link DataSource} is {@code @Primary}
 * ({@link LedgerDataSourceConfiguration#dataSource}), so every other injection point, and the auto-configuration of Spring
 * Boot that wants a single candidate, still get the ledger database. There is no second {@code JdbcClient} or transaction
 * manager bean, for the same reason: Boot makes them for the one primary {@link DataSource} and backs off when one exists.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "ledger.import.source", name = "url")
@EnableConfigurationProperties(LegacySourceProperties.class)
@Import(LegacySourceRepository.class)
public class LegacySourceConfiguration {

    /** The name of the legacy {@link DataSource} bean, which the repository of the legacy source takes by name. */
    public static final String DATA_SOURCE = "legacySourceDataSource";

    /** Two connections: one for the read-only snapshot and one for the metadata of the legacy schema. */
    private static final int CONNECTIONS = 2;

    /** A pool around the registered driver: an external type (Hikari) whose driver is not on the class path of Boot. */
    @Bean(name = DATA_SOURCE, destroyMethod = "close")
    DataSource legacySourceDataSource(RegisteredDriver driver, LegacySourceProperties properties) {
        return DriverDataSources.create(driver, new LedgerJdbcProperties(properties.url(), properties.username(),
                properties.password(), CONNECTIONS, null), null);
    }
}
