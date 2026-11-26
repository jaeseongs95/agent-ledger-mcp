// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.migrate1x;

import org.agentledger.database.time.JvmZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;

/**
 * The tool that moves a 1.x installation to 2.0: {@code stop-1x}, {@code import-secrets-1x}, {@code import-1x} and
 * {@code retire-1x}. It is a program of its own (its own main and its own jar, {@code ledger-migrate-1x.jar}); the 2.0 services
 * and the 2.0 command line tool know nothing of it, and it uses the repositories of the 2.0 modules to write the new ledger.
 *
 * <p>Like the command line tool of 2.0, it has a ledger database only for a command that uses one and only when a usable JDBC
 * driver is registered (see {@code org.agentledger.migrate1x.wiring}); Spring Boot's {@code DataSourceAutoConfiguration} is
 * excluded because the DataSource comes from the registered driver and must not be guessed when there is none.
 *
 * <p>The command line machinery (the runner, the refusals and their report, the texts, the secret store) is the one of
 * {@code ledger-cli}, imported class by class in {@code wiring.CommandLineKit}; nothing of it is copied. Only the packages named
 * here are scanned: the commands, the 1.x environment file, the service commands and the configurations of the database
 * ({@code wiring}). The classes of the 1.x source and of the import ({@code database}, {@code service}) are imported by those
 * configurations, under the conditions of the database.
 */
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class, scanBasePackages = {
        "org.agentledger.migrate1x.command", "org.agentledger.migrate1x.secret", "org.agentledger.migrate1x.host",
        "org.agentledger.migrate1x.wiring"})
public class Migrate1xApplication {

    public static void main(String[] args) {
        JvmZone.pinUtc();
        System.exit(SpringApplication.exit(SpringApplication.run(Migrate1xApplication.class, args)));
    }
}
