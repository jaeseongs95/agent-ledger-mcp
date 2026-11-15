package org.pgledger.migrate1x.database;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * What is made around the legacy {@link DataSource} bean ({@link LegacySourceConfiguration#DATA_SOURCE}): its {@code JdbcClient},
 * its transaction manager and the repository that reads through them. The data source is a bean of the context; in production
 * {@link LegacySourceConfiguration} makes it (a pool), and a test brings a database of its own under the same name.
 *
 * <p>The legacy ledger is genuinely a second database (principle 3.9), so the service that reads it declares its snapshot with
 * {@code @Transactional(transactionManager = LegacySourceRepository.TRANSACTION_MANAGER, readOnly = true)}, and its repository
 * runs through the {@code JdbcClient} of that data source; both are beans here, qualified by name.
 *
 * <p>Why the ledger's own {@code JdbcClient} and transaction manager are declared here as well: Spring Boot makes them only
 * while no bean of the type exists ({@code JdbcClientAutoConfiguration} is {@code @ConditionalOnMissingBean(JdbcClient.class)},
 * {@code DataSourceTransactionManagerAutoConfiguration} is {@code @ConditionalOnMissingBean(TransactionManager.class)}; Spring
 * Boot reference, "Data Access", and the Javadoc of those classes). A second bean of either type would make it back off and
 * leave the ledger without its client or with an ambiguous transaction manager, so the two for the ledger data source, which
 * is {@code @Primary}, are stated explicitly and are {@code @Primary} as well; they are what Boot makes, with the same names
 * ({@code transactionManager}, which Spring Data JDBC looks up by that name, and {@code jdbcClient} over Boot's
 * {@link NamedParameterJdbcTemplate}). A context without a legacy source does not import this class and keeps Boot's own.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LegacySourceProperties.class)
@Import({LegacySourceRepository.class, LegacySchemaRepository.class})
public class LegacySourceWiring {

    /** A {@code @Bean} method because the type is Spring's and the data source is picked by name. */
    @Bean(name = LegacySourceRepository.JDBC_CLIENT)
    JdbcClient legacySourceJdbcClient(@Qualifier(LegacySourceConfiguration.DATA_SOURCE) DataSource legacy) {
        return JdbcClient.create(legacy);
    }

    /** The manager of the read-only snapshot of the legacy database; the one transaction manager over the legacy data source. */
    @Bean(name = LegacySourceRepository.TRANSACTION_MANAGER)
    PlatformTransactionManager legacySourceTransactionManager(
            @Qualifier(LegacySourceConfiguration.DATA_SOURCE) DataSource legacy) {
        return new DataSourceTransactionManager(legacy);
    }

    /** The transaction manager of the ledger data source (the {@code @Primary} one), as Boot makes it; see the class comment. */
    @Bean
    @Primary
    PlatformTransactionManager transactionManager(DataSource ledger) {
        return new DataSourceTransactionManager(ledger);
    }

    /** The {@code JdbcClient} of the ledger data source, as Boot makes it, over Boot's template; see the class comment. */
    @Bean
    @Primary
    JdbcClient jdbcClient(NamedParameterJdbcTemplate template) {
        return JdbcClient.create(template);
    }
}
