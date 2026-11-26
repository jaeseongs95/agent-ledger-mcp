// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.migrate1x.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.function.Consumer;
import javax.sql.DataSource;
import org.veridger.application.support.LedgerAppContext;
import org.veridger.application.support.TestApplication;
import org.veridger.apptest.LedgerTestInfrastructure;
import org.veridger.apptest.LedgerTestSettings;
import org.veridger.migrate1x.database.LegacySourceConfiguration;
import org.veridger.migrate1x.database.LegacySourceWiring;
import org.veridger.migrate1x.database.LegacyTargetRepository;
import org.veridger.migrate1x.fixture.LegacyLedger;
import org.veridger.migrate1x.service.ImportServices;
import org.veridger.testing.database.TestSchemaInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The application context of the import tests: a fresh installation of 2.0 (a new, migrated schema, no categories: the state the
 * import needs) in the real application context of the services, with the services of the tool ({@link ImportServices} and the
 * write side {@link LegacyTargetRepository}) added, whose 1.x source is read through {@code source} (the read-only account of
 * {@code legacy}, or a wrapper of it) in the schema of {@code legacy}. The context is closed when the test body returns, and the
 * schema of the context is dropped then ({@link TestSchemaInitializer}).
 */
public final class ImportRunners {

    private ImportRunners() {
    }

    /**
     * @param configurations extra configurations replace beans of the context (a test that injects a failure into the write side
     *                       of the import)
     */
    public static void importing(LegacyLedger legacy, DataSource source, Consumer<LedgerAppContext> test,
            Class<?>... configurations) {
        new ApplicationContextRunner().withUserConfiguration(TestApplication.class)
                .withPropertyValues(LedgerTestSettings.QUIET_BANNER, LedgerTestSettings.QUIET_LOG,
                        LedgerTestSettings.NO_JMX)
                .withInitializer(new TestSchemaInitializer())
                .withUserConfiguration(LedgerTestInfrastructure.class)
                .withUserConfiguration(ImportServices.class, LegacyTargetRepository.class)
                .withUserConfiguration(configurations)
                // the settings the wiring reads; the connection is the bean below, so the url is never opened
                .withPropertyValues("ledger.import.source.url=jdbc:unused:test",
                        "ledger.import.source.schema=" + legacy.schema())
                // the 1.x data source is a bean of the context, as the pool is in production (LegacySourceConfiguration); the
                // test owns it, so the context does not close it. The 1.x JdbcClient, its transaction manager and the
                // repository are the production ones (LegacySourceWiring).
                .withBean(LegacySourceConfiguration.DATA_SOURCE, DataSource.class, () -> source,
                        definition -> definition.setDestroyMethodName(""))
                .withUserConfiguration(LegacySourceWiring.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    test.accept(new LedgerAppContext(context));
                });
    }
}
