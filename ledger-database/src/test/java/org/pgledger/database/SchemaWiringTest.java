package org.pgledger.database;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.database.repository.DatabaseEncodingRepository;
import org.pgledger.database.repository.SchemaHistoryRepository;
import org.pgledger.database.repository.SessionClockRepository;
import org.pgledger.database.schema.GrantApplier;
import org.pgledger.database.schema.SchemaFileApplier;
import org.pgledger.database.schema.LedgerSchemaConfiguration;
import org.pgledger.database.schema.LedgerSchemaGuardConfiguration;
import org.pgledger.database.schema.SchemaGuard;
import org.pgledger.database.schema.SchemaMigrator;
import org.pgledger.database.schema.SchemaVerifier;
import org.pgledger.database.schema.UtcSessionCheck;
import org.pgledger.database.support.Databases;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * SCH-031: the schema services are components found by the scan of their configuration (not {@code @Bean} methods), and
 * the startup guard exists only for an application that imports it: the command line tool imports the services and checks
 * on demand, the web applications import the guard and refuse to start on a schema that is not up to date. Tested with the
 * Spring Boot {@code ApplicationContextRunner} on the PostgreSQL of the test PC (a schema of its own per test).
 */
class SchemaWiringTest {

    private static ApplicationContextRunner runner(DataSource pool, Class<?> configuration) {
        return new ApplicationContextRunner().withUserConfiguration(configuration)
                // the pool belongs to the test, which runs several contexts on it: a context must not close it with itself
                .withBean(DataSource.class, () -> pool, definition -> definition.setDestroyMethodName(""))
                // the JdbcClient and the transaction manager are the ones Spring Boot makes for an application
                .withBean(JdbcClient.class, () -> JdbcClient.create(pool))
                .withConfiguration(AutoConfigurations.of(DataSourceTransactionManagerAutoConfiguration.class,
                        TransactionAutoConfiguration.class))
                .withBean(Clock.class, Clock::systemUTC)
                .withPropertyValues("ledger.schema.require-utc=false");
    }

    @Test
    @DisplayName("SCH-031 the schema configuration makes the four services and no guard")
    void theServicesAreComponentsAndThereIsNoGuard() {
        try (Databases databases = new Databases()) {
            DataSource pool = databases.newDataSource();
            runner(pool, LedgerSchemaConfiguration.class).run(context -> {
                assertThat(context).hasNotFailed().hasSingleBean(SchemaMigrator.class)
                        .hasSingleBean(GrantApplier.class).hasSingleBean(SchemaVerifier.class)
                        .hasSingleBean(UtcSessionCheck.class).doesNotHaveBean(SchemaGuard.class)
                        .hasSingleBean(SchemaFileApplier.class).hasSingleBean(SchemaHistoryRepository.class)
                        .hasSingleBean(SessionClockRepository.class).hasSingleBean(DatabaseEncodingRepository.class);
                // the service that applies one file runs in the transaction of the container (the proxy of @Transactional)
                assertThat(AopUtils.isAopProxy(context.getBean(SchemaFileApplier.class))).isTrue();
                // so does the grant step (TXF-005): several GRANT statements are one transaction, a service method
                assertThat(AopUtils.isAopProxy(context.getBean(GrantApplier.class))).isTrue();
            });
        }
    }

    @Test
    @DisplayName("SCH-031 the guard configuration adds the guard: it stops the start on a database that was never migrated (SCHEMA_HISTORY_MISSING) and lets an up-to-date schema start")
    void theGuardStopsOrLetsTheApplicationStart() {
        try (Databases databases = new Databases()) {
            DataSource pool = databases.newDataSource();
            runner(pool, LedgerSchemaGuardConfiguration.class).run(context -> assertThat(context).hasFailed()
                    .getFailure().hasStackTraceContaining("SCHEMA_HISTORY_MISSING"));

            runner(pool, LedgerSchemaConfiguration.class).run(context -> context.getBean(SchemaMigrator.class).migrate());

            runner(pool, LedgerSchemaGuardConfiguration.class).run(context -> assertThat(context).hasNotFailed()
                    .hasSingleBean(SchemaGuard.class).hasSingleBean(SchemaVerifier.class));
        }
    }
}
