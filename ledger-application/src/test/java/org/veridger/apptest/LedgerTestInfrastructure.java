// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.apptest;

import java.time.Clock;
import java.util.List;
import javax.sql.DataSource;
import org.veridger.application.support.LedgerTestFixtures;
import org.veridger.application.support.RecordingDataSource;
import org.veridger.application.support.TestClock;
import org.veridger.testing.database.TestSchemaServices;
import org.veridger.database.schema.SchemaProperties;
import org.veridger.testing.database.TestDataSourceConfiguration;
import org.veridger.testing.database.TestSchemaInitializer;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

/**
 * What the service tests add to the application under test: the injected UTC clock, the recording of the SQL
 * texts at the data source, and the schema, built by the real {@code db/V*.sql} files and the real migrator before
 * the first test. The database is the PostgreSQL of the test PC: {@link TestDataSourceConfiguration} supplies the
 * {@link DataSource} (the shared pool, every connection in the schema of the context), and the schema is the one that
 * {@code TestSchemaInitializer} named with the product setting {@code ledger.schema.name}; the real migrator creates
 * it and the tables in it.
 *
 * <p>This class lives outside {@code org.veridger.application} on purpose: the application scans its own package
 * for components, and a test configuration that it found by itself would be part of every context.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import(TestDataSourceConfiguration.class)
public class LedgerTestInfrastructure {

    /** The versions that the migration applied to the database of the context. */
    public record SchemaMigrated(List<Integer> versions) {
    }

    @Bean
    static BeanPostProcessor recordingDataSource() {
        return new RecordingDataSource.Installer();
    }

    @Bean
    TestClock clock() {
        return new TestClock(TestClock.START);
    }

    @Bean
    LedgerTestFixtures ledgerTestFixtures() {
        return new LedgerTestFixtures();
    }

    @Bean
    SchemaMigrated schemaMigrated(DataSource dataSource, Clock clock, Environment environment) {
        return new SchemaMigrated(TestSchemaServices.migrator(dataSource,
                new SchemaProperties(SchemaProperties.DEFAULT_LOCATION, true,
                        environment.getRequiredProperty(TestSchemaInitializer.SCHEMA_PROPERTY)), clock).migrate());
    }
}
