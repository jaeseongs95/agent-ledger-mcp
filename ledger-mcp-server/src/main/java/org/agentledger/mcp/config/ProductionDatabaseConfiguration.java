// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.mcp.config;

import org.agentledger.database.driver.LedgerDataSourceConfiguration;
import org.agentledger.database.schema.LedgerSchemaGuardConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * The database of a deployment: the DataSource of the registered JDBC driver (without a registration the context
 * does not start, see {@code DriverFailureAnalyzer}) and the startup guard that needs an up-to-date schema and UTC.
 */
@Configuration(proxyBeanMethods = false)
@Import({LedgerDataSourceConfiguration.class, LedgerSchemaGuardConfiguration.class})
public class ProductionDatabaseConfiguration {
}
