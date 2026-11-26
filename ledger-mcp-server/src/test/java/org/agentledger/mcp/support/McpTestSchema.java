// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.mcp.support;

import java.time.Clock;
import java.util.List;
import javax.sql.DataSource;
import org.agentledger.testing.database.TestSchemaServices;
import org.agentledger.database.schema.SchemaProperties;
import org.agentledger.testing.database.TestDataSourceConfiguration;
import org.agentledger.testing.database.TestSchemaInitializer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * The schema of the test database, built by the real {@code db/V*.sql} files and the real migrator before the server
 * registers its tools. The database itself is the PostgreSQL of the test PC: the {@code DataSource} comes from
 * {@link TestDataSourceConfiguration} (the shared pool, every connection in the schema {@code ledger.schema.name} that
 * a {@code TestSchemaInitializer} gave the context), and the product migrator creates that schema and its tables. The
 * installation of the categories is not part of it ({@link McpTestInfrastructure}), so a context can start the way a
 * fresh installation does.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import(TestDataSourceConfiguration.class)
public class McpTestSchema {

    /** Present once the schema is built. */
    public record SchemaMigrated(List<Integer> versions) {
    }

    @Bean
    ProjectNumbers projectNumbers() {
        return new ProjectNumbers();
    }

    @Bean
    SchemaMigrated schemaMigrated(DataSource dataSource, Clock clock, Environment environment) {
        return new SchemaMigrated(TestSchemaServices.migrator(dataSource,
                new SchemaProperties(SchemaProperties.DEFAULT_LOCATION, true,
                        environment.getRequiredProperty(TestSchemaInitializer.SCHEMA_PROPERTY)), clock).migrate());
    }
}
