// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.admin;

import org.agentledger.admin.console.ConsoleConfiguration;
import org.agentledger.database.time.JvmZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * The admin web of the ledger: server-rendered pages (Spring MVC and Thymeleaf) over the application services, behind
 * one Spring Security chain. The pages that need the ledger database live in the {@code console} package, which is not
 * scanned here: {@link ConsoleConfiguration} brings it in only when a usable JDBC driver is registered. Without one
 * the application starts in setup mode (login and driver registration only).
 *
 * <p>Spring Boot's {@code DataSourceAutoConfiguration} is excluded because the DataSource of the product comes from the
 * registered driver (console mode) and must not be guessed in setup mode.
 */
@SpringBootApplication(scanBasePackages = {"org.agentledger.admin.app", "org.agentledger.admin.security",
    "org.agentledger.admin.web", "org.agentledger.admin.setup"}, exclude = DataSourceAutoConfiguration.class)
@ConfigurationPropertiesScan("org.agentledger.admin")
@Import(ConsoleConfiguration.class)
public class LedgerAdminApplication {

    public static void main(String[] arguments) {
        JvmZone.pinUtc();
        SpringApplication.run(LedgerAdminApplication.class, arguments);
    }
}
