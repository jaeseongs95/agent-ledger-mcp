package org.pgledger.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pgledger.database.schema.GrantApplier;
import org.pgledger.database.schema.LedgerSchemaConfiguration;
import org.pgledger.database.schema.SchemaProperties;
import org.pgledger.database.support.Databases;
import org.pgledger.database.support.LedgerDatabaseExtension;
import org.pgledger.testing.database.TestDatabase;
import org.pgledger.testing.database.TestSchema;
import org.pgledger.testing.database.TestSchemaServices;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * TXF-005 (principle 4.4): the grant step is a {@code @Transactional} method of a service, taken from the container as a
 * transaction proxy, and it can be run again: the second run succeeds and leaves the account with the same privileges (the
 * step is repeated by every {@code migrate} and {@code setup}, which finishes what a DBMS that commits a GRANT at once left
 * after a failure in the middle). Over the test PostgreSQL; the privileges are read from the information schema of SQL.
 */
@ExtendWith(LedgerDatabaseExtension.class)
class GrantStepTest {

    /** {@code table:PRIVILEGE} of every right that the account has on a table of the schema. */
    private static Set<String> privileges(DataSource dataSource, String account, String schema) throws SQLException {
        Set<String> rights = new TreeSet<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("SELECT table_name, privilege_type FROM "
                        + "information_schema.table_privileges WHERE grantee = ? AND table_schema = ?")) {
            statement.setString(1, account.toLowerCase(Locale.ROOT));
            statement.setString(2, schema.toLowerCase(Locale.ROOT));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    rights.add(rows.getString(1) + ":" + rows.getString(2));
                }
            }
        }
        return rights;
    }

    @Test
    @DisplayName("TXF-005 the grant step is a transaction proxy of the container; run twice it succeeds and leaves the account with the same privileges, which include the key table of the driver audit")
    void txf005_theGrantStepIsATransactionAndRunsAgain(Databases databases) {
        TestDatabase database = databases.database();
        TestSchema schema = databases.newSchema();
        SchemaProperties named = new SchemaProperties(SchemaProperties.DEFAULT_LOCATION, true,
                schema.name());
        DataSource pool = schema.dataSource();
        TestSchemaServices.migrator(pool, named, Clock.systemUTC()).migrate();
        String account = database.settings().appUser();
        schema.allowUsage(account);

        new ApplicationContextRunner().withUserConfiguration(LedgerSchemaConfiguration.class)
                .withBean(DataSource.class, () -> pool, definition -> definition.setDestroyMethodName(""))
                .withBean(JdbcClient.class, () -> JdbcClient.create(pool))
                .withConfiguration(AutoConfigurations.of(DataSourceTransactionManagerAutoConfiguration.class,
                        TransactionAutoConfiguration.class))
                .withBean(Clock.class, Clock::systemUTC)
                .withPropertyValues("ledger.schema.require-utc=false")
                .run(context -> {
                    GrantApplier applier = context.getBean(GrantApplier.class);
                    assertThat(AopUtils.isAopProxy(applier)).as("the grant step is a transaction proxy").isTrue();

                    applier.apply(account);
                    Set<String> once = privileges(pool, account, schema.name());
                    applier.apply(account);

                    assertThat(once).contains("issue:SELECT", "issue:INSERT", "issue:UPDATE",
                            "spec:SELECT", "spec:INSERT", "spec:UPDATE",
                            "deliverable:SELECT", "deliverable:INSERT", "deliverable:UPDATE",
                            "session_token:SELECT", "session_token:INSERT", "session_token:UPDATE",
                            "driver_audit_key:INSERT", "history:SELECT", "history:INSERT");
                    assertThat(once).contains("attachment:SELECT", "attachment:INSERT", "attachment_file:SELECT",
                            "attachment_file:INSERT", "upload_staging:UPDATE", "upload_staging:DELETE");
                    assertThat(once).doesNotContain("history:UPDATE", "history:DELETE", "attachment:UPDATE",
                            "attachment:DELETE", "attachment_file:UPDATE", "attachment_file:DELETE");
                    assertThat(privileges(pool, account, schema.name())).as("the second run changes nothing")
                            .isEqualTo(once);
                });
    }
}
