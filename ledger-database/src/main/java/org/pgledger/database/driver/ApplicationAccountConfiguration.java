package org.pgledger.database.driver;

import javax.sql.DataSource;
import org.pgledger.database.repository.SchemaHistoryRepository;
import org.pgledger.database.schema.ApplicationAccountAccess;
import org.pgledger.database.schema.SchemaProperties;
import org.pgledger.database.schema.SchemaVerifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The check whether the application account can use the ledger schema ({@link ApplicationAccountAccess}), for the tool that runs
 * as the owner account. It needs the registered driver of {@link LedgerDataSourceConfiguration} and the settings of the
 * application account ({@link ApplicationAccountSettings}, a bean of the tool).
 *
 * <p>The check asks over the connections of the application account, a second data source that is genuinely needed (principle
 * 3.9: the run itself is connected as the owner account, so the ledger data source cannot answer). Its {@code JdbcClient} and its
 * history repository are therefore made here, in the one {@code @Bean} method of a configuration, over that one data source
 * ({@code JdbcClient.create} opens no connection and owns no pool); nothing else uses them, and no other class creates one.
 */
@Configuration(proxyBeanMethods = false)
public class ApplicationAccountConfiguration {

    /** A {@code @Bean} method because the type is made from settings that the tool binds, over a data source that no component is. */
    @Bean
    ApplicationAccountAccess applicationAccountAccess(RegisteredDriver driver, ApplicationAccountSettings account,
            ResourcePatternResolver resolver, SchemaProperties schema) {
        if (!account.configured()) {
            return ApplicationAccountAccess.notConfigured();
        }
        LedgerJdbcProperties application = account.settings();
        DataSource connections = DriverDataSources.applicationAccountDataSource(driver, application, schema.name());
        return new ApplicationAccountAccess(application.username(), new SchemaVerifier(
                new SchemaHistoryRepository(JdbcClient.create(connections), connections), resolver, schema));
    }
}
