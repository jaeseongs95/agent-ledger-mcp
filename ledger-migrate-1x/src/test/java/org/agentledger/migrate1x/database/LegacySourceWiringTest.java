// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.migrate1x.database;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.agentledger.application.support.RecordingDataSource;
import org.agentledger.migrate1x.support.TestLedgerDatabase;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * TXD-012 (principles 3.9, 4.4): the legacy ledger is a second database, so it has a {@code JdbcClient} and a transaction
 * manager of its own, both beans next to its data source and qualified by name, while the ledger data source keeps the default
 * ones. Spring Boot makes its {@code JdbcClient} and transaction manager only while no bean of the type exists, so the wiring
 * states the two of the ledger explicitly ({@code @Primary}); this runs the real wiring with Boot's auto-configuration over two
 * data sources and looks at which data source each bean works on.
 */
class LegacySourceWiringTest {

    @Test
    @DisplayName("TXD-012 a legacy data source gets its own JdbcClient, transaction manager and repository; the unqualified JdbcClient and transaction manager are those of the ledger data source")
    void theLegacyBeansAreQualifiedAndTheLedgerKeepsTheDefaults() {
        try (TestLedgerDatabase ledgerDatabase = TestLedgerDatabase.empty();
                TestLedgerDatabase legacyDatabase = TestLedgerDatabase.empty()) {
            RecordingDataSource ledger = new RecordingDataSource(ledgerDatabase.dataSource());
            RecordingDataSource legacy = new RecordingDataSource(legacyDatabase.dataSource());
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class,
                            JdbcTemplateAutoConfiguration.class, JdbcClientAutoConfiguration.class,
                            DataSourceTransactionManagerAutoConfiguration.class, TransactionAutoConfiguration.class))
                    .withUserConfiguration(LegacySourceWiring.class)
                    // the data sources belong to the test: the context must not close them
                    .withBean("ledgerDataSource", DataSource.class, () -> ledger, definition -> {
                        definition.setPrimary(true);
                        definition.setDestroyMethodName("");
                    })
                    .withBean(LegacySourceConfiguration.DATA_SOURCE, DataSource.class, () -> legacy,
                            definition -> definition.setDestroyMethodName(""))
                    .withPropertyValues("ledger.import.source.url=jdbc:unused:test",
                            "ledger.import.source.schema=ledger_old")
                    .run(context -> {
                        assertThat(context).hasNotFailed();

                        // the unqualified transaction manager and the manager that a plain @Transactional gets: the ledger's
                        assertThat(((DataSourceTransactionManager) context.getBean(PlatformTransactionManager.class))
                                .getDataSource()).isSameAs(ledger);
                        assertThat(((DataSourceTransactionManager) context.getBean("transactionManager",
                                PlatformTransactionManager.class)).getDataSource()).isSameAs(ledger);
                        assertThat(((DataSourceTransactionManager) context.getBean(
                                LegacySourceRepository.TRANSACTION_MANAGER, PlatformTransactionManager.class))
                                .getDataSource()).isSameAs(legacy);

                        // the unqualified client reads the ledger, the qualified one the legacy ledger
                        context.getBean(JdbcClient.class).sql("SELECT 11").query().listOfRows();
                        context.getBean(LegacySourceRepository.JDBC_CLIENT, JdbcClient.class)
                                .sql("SELECT 22").query().listOfRows();
                        assertThat(ledger.statements()).anyMatch(sql -> sql.contains("SELECT 11"))
                                .noneMatch(sql -> sql.contains("SELECT 22"));
                        assertThat(legacy.statements()).anyMatch(sql -> sql.contains("SELECT 22"))
                                .noneMatch(sql -> sql.contains("SELECT 11"));

                        assertThat(context).hasSingleBean(LegacySourceRepository.class);
                        assertThat(context.getBean(LegacySourceRepository.class).schema()).isEqualTo("ledger_old");
                    });
        }
    }
}
