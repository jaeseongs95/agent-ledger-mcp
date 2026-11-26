// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.cli.database;

import org.agentledger.application.LedgerApplicationConfiguration;
import org.agentledger.cli.driver.PendingDriverAudit;
import org.agentledger.database.schema.LedgerSchemaConfiguration;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * The ledger services of the CLI and the doors of the commands to them. It exists only when
 * {@link CliDatabaseCondition} holds, exactly like the console of the admin web; otherwise none of its beans (and no
 * DataSource) is created and the commands that need the database find no {@link CliLedger} and no migrator.
 *
 * <p>The services and the Spring Data JDBC setup come from {@link LedgerApplicationConfiguration}, the migrator and the
 * schema checks from {@link LedgerSchemaConfiguration}; the DataSource is in
 * {@link CliDriverDataSourceConfiguration}. The UTC {@code Clock} is the one of the tool ({@code CliClockConfiguration}),
 * which has no condition, so this does not import the clock configuration of the database module. The schema is not checked
 * at startup (the command {@code migrate} must start on a database that is not up to date): every other command checks it
 * first through {@link SchemaReadiness}.
 *
 * <p>The classes of the tool that need the ledger services ({@code CliLedger}, the audit row of a pending driver
 * registration) are imported here as components, and are not annotated with {@code @Component}: component scanning would
 * make them without the condition, and without a database there is no service to give them.
 */
@Configuration(proxyBeanMethods = false)
@Conditional(CliDatabaseCondition.class)
@Import({LedgerApplicationConfiguration.class, LedgerSchemaConfiguration.class, SchemaReadiness.class, CliLedger.class,
        PendingDriverAudit.class})
public class CliLedgerConfiguration {
}
