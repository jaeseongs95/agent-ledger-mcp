// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.cli;

import org.veridger.cli.deploy.HostDatabaseUpdate;
import org.veridger.database.time.JvmZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;

/**
 * The command line tool of the owner. The ledger database exists only when a usable JDBC driver is registered (see
 * {@code org.veridger.cli.database}); {@code secret-set} and {@code driver-register} run without it.
 *
 * <p>Spring Boot's {@code DataSourceAutoConfiguration} is excluded because the DataSource of the product comes from the
 * registered driver and must not be guessed when there is none.
 */
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
@ConfigurationPropertiesScan
public class LedgerCliApplication {

    public static void main(String[] args) {
        JvmZone.pinUtc();
        if (args.length > 0 && "host-update".equals(args[0])) {
            System.exit(HostDatabaseUpdate.run(args));
        }
        System.exit(SpringApplication.exit(SpringApplication.run(LedgerCliApplication.class, args)));
    }
}
