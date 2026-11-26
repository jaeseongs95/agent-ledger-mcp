// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.migrate1x.wiring;

import org.agentledger.cli.owner.OwnerAccountPropertySource;
import org.agentledger.database.driver.ApplicationAccountSettings;
import org.agentledger.database.driver.LedgerDataSourceConfiguration;
import org.agentledger.database.driver.LedgerJdbcProperties;
import org.agentledger.migrate1x.database.LegacySourceConfiguration;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MutablePropertySources;

/**
 * The databases of a run: the DataSource of the registered JDBC driver (settings {@code ledger.jdbc.*}, the 2.0 ledger that the
 * import writes; the password arrives through the secret store, a configtree property source) and, when
 * {@code ledger.import.source.url} is set, the 1.x ledger it reads ({@link LegacySourceConfiguration}). Like
 * {@link LedgerConfiguration} it exists only when {@link DatabaseCondition} holds, so a machine without a driver never tries to
 * connect. The schema guard of the server applications is not imported: the tool checks the schema per command
 * ({@code SchemaReadiness} of {@code ledger-cli}).
 *
 * <p>The account of the run: a run of {@code import-1x} connects as the owner account (the DDL account,
 * {@link OwnerAccountPropertySource}), which shadows {@code ledger.jdbc.username} and {@code ledger.jdbc.password}. The accounts of
 * the 2.0 settings are handed to {@code retire-1x}, so that a role of 1.x with the name of one of them is never dropped; the
 * settings of the application account are bound from the property sources without the owner account (the same settings the
 * servers connect with).
 */
@Configuration(proxyBeanMethods = false)
@Conditional(DatabaseCondition.class)
@Import({LedgerDataSourceConfiguration.class, LegacySourceConfiguration.class})
public class DriverDataSourceConfiguration {

    private static final String ATTACHED_VIEW = "configurationProperties";

    /** A {@code @Bean} method because the settings are bound from a filtered view of the environment, which no annotation can say. */
    @Bean
    ApplicationAccountSettings applicationAccountSettings(ConfigurableEnvironment environment) {
        MutablePropertySources sources = new MutablePropertySources(environment.getPropertySources());
        sources.remove(OwnerAccountPropertySource.NAME);
        // the attached view of the environment would show the owner account again (Boot names it "configurationProperties",
        // ConfigurationPropertySources.attach, the name itself is not public)
        sources.remove(ATTACHED_VIEW);
        Binder binder = new Binder(ConfigurationPropertySources.from(sources), new PropertySourcesPlaceholdersResolver(sources));
        return binder.bind("ledger.jdbc", LedgerJdbcProperties.class).map(ApplicationAccountSettings::new)
                .orElse(ApplicationAccountSettings.NOT_CONFIGURED);
    }
}
