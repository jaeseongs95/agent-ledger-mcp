package org.pgledger.migrate1x.wiring;

import org.pgledger.application.LedgerApplicationConfiguration;
import org.pgledger.cli.database.SchemaReadiness;
import org.pgledger.database.schema.LedgerSchemaConfiguration;
import org.pgledger.migrate1x.database.LegacyTargetRepository;
import org.pgledger.migrate1x.service.ImportServices;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * The ledger services of the tool and the door of the commands to them. It exists only when {@link DatabaseCondition} holds;
 * otherwise none of its beans (and no DataSource) is created and the commands that need the database find no {@link Ledger}.
 *
 * <p>The services and the Spring Data JDBC setup come from {@link LedgerApplicationConfiguration}, the schema checks from
 * {@link LedgerSchemaConfiguration}; the DataSource is in {@link DriverDataSourceConfiguration}. The UTC {@code Clock} is the
 * one of the tool ({@code CliClockConfiguration}), which has no condition. The schema is checked per command ({@code SchemaReadiness}),
 * not at startup. The write side of the import ({@link LegacyTargetRepository}) and the services of the import
 * ({@link ImportServices}) are imported here as components, so they exist exactly when the database does.
 */
@Configuration(proxyBeanMethods = false)
@Conditional(DatabaseCondition.class)
@Import({LedgerApplicationConfiguration.class, LedgerSchemaConfiguration.class, SchemaReadiness.class, Ledger.class,
        LegacyTargetRepository.class, ImportServices.class})
public class LedgerConfiguration {
}
