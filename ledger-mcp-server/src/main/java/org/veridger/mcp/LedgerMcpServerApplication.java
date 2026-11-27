// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.mcp;

import org.veridger.application.LedgerApplicationConfiguration;
import org.veridger.database.LedgerDatabaseConfiguration;
import org.veridger.database.time.JvmZone;
import org.springframework.ai.mcp.server.webmvc.autoconfigure.McpServerStreamableHttpWebMvcAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The ledger MCP server: the ledger services of the application module behind MCP tools (Streamable HTTP at
 * {@code /mcp}). The database comes from {@code ProductionDatabaseConfiguration} (the registered driver).
 */
@SpringBootApplication(exclude = McpServerStreamableHttpWebMvcAutoConfiguration.class)
@ConfigurationPropertiesScan
@EnableScheduling
@Import({LedgerApplicationConfiguration.class, LedgerDatabaseConfiguration.class})
public class LedgerMcpServerApplication {

    public static void main(String[] arguments) {
        JvmZone.pinUtc();
        SpringApplication.run(LedgerMcpServerApplication.class, arguments);
    }
}
