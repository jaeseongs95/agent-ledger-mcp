// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.database.schema;

import org.pgledger.database.repository.DatabaseEncodingRepository;
import org.pgledger.database.repository.SchemaHistoryRepository;
import org.pgledger.database.repository.SessionClockRepository;
import org.pgledger.database.repository.SqlBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

/**
 * The schema services: the migrator, the service that applies one file, and the grant applier (for the operator's CLI), the
 * verifier and the UTC check (for the applications). They are components of this package, found by the scan below, together
 * with the three repositories they read through ({@link SchemaHistoryRepository}, {@link SessionClockRepository},
 * {@link DatabaseEncodingRepository}) and the {@link SqlBuilder} whose dialect names the long text type and the encoding probe (the same classes the scan of the repository package finds, with the
 * same bean names, so an application that has both scans gets each once). Nothing runs at startup here: the startup guard ({@link SchemaGuard}) is left out of the scan on purpose, and is
 * added by {@link LedgerSchemaGuardConfiguration} for the applications that want it. The {@code JdbcClient}, the
 * {@code DataSource} and the transaction manager are the ones of the application (Spring Boot).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SchemaProperties.class)
@ComponentScan(basePackageClasses = SchemaMigrator.class, excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE, classes = {SchemaGuard.class, LedgerSchemaGuardConfiguration.class}))
@ComponentScan(basePackageClasses = SchemaHistoryRepository.class, useDefaultFilters = false,
        includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                classes = {SchemaHistoryRepository.class, SessionClockRepository.class, DatabaseEncodingRepository.class,
                        SqlBuilder.class}))
public class LedgerSchemaConfiguration {
}
