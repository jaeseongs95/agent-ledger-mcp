package org.pgledger.migrate1x;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;

/**
 * The tool that moves a 1.x installation to 2.0: {@code stop-1x}, {@code import-secrets-1x}, {@code import-1x} and
 * {@code retire-1x}. It is a program of its own (its own main and its own jar, {@code ledger-migrate-1x.jar}); the 2.0 services
 * and the 2.0 command line tool know nothing of it, and it uses the repositories of the 2.0 modules to write the new ledger.
 *
 * <p>Like the command line tool of 2.0, it has a ledger database only for a command that uses one and only when a usable JDBC
 * driver is registered (see {@code org.pgledger.migrate1x.wiring}); Spring Boot's {@code DataSourceAutoConfiguration} is
 * excluded because the DataSource comes from the registered driver and must not be guessed when there is none.
 *
 * <p>Only the packages named here are scanned: the command line machinery ({@code tool}), the commands, the secret store, the
 * service commands and the configurations of the database ({@code wiring}). The classes of the 1.x source and of the import
 * ({@code database}, {@code service}) are imported by those configurations, under the conditions of the database.
 */
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class, scanBasePackages = {
        "org.pgledger.migrate1x.tool", "org.pgledger.migrate1x.command", "org.pgledger.migrate1x.secret",
        "org.pgledger.migrate1x.host", "org.pgledger.migrate1x.wiring"})
@ConfigurationPropertiesScan("org.pgledger.migrate1x.secret")
public class Migrate1xApplication {

    public static void main(String[] args) {
        System.exit(SpringApplication.exit(SpringApplication.run(Migrate1xApplication.class, args)));
    }
}
