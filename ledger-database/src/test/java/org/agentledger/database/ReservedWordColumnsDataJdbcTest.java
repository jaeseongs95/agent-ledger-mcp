// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.agentledger.database.jdbc.LedgerJdbcConfiguration;
import org.agentledger.database.repository.HistoryRepository;
import org.agentledger.database.repository.OwnerAuditRepository;
import org.agentledger.database.repository.SessionGrantRepository;
import org.agentledger.database.schema.LedgerSchemaConfiguration;
import org.agentledger.database.schema.SchemaMigrator;
import org.agentledger.domain.model.HistoryEntry;
import org.agentledger.domain.model.OwnerAuditEntry;
import org.agentledger.domain.model.SessionGrant;
import org.agentledger.domain.rule.ActorAssurance;
import org.agentledger.domain.rule.HistoryEntity;
import org.agentledger.domain.rule.HistoryOp;
import org.agentledger.domain.rule.OwnerAction;
import org.agentledger.domain.rule.Scope;
import org.agentledger.domain.value.AuthorId;
import org.agentledger.domain.value.JsonDocument;
import org.agentledger.domain.value.OperationKey;
import org.agentledger.domain.value.ProjectId;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.agentledger.testing.database.TestDataSourceConfiguration;
import org.agentledger.testing.database.TestSchemaInitializer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ContextConfiguration;

/**
 * REPO-061 (F10), as a {@code @DataJdbcTest} slice (principle 7.2: a Spring Data JDBC repository is tested with the Data
 * JDBC slice on the PostgreSQL of the test PC): the three records whose column names are not the names of their components
 * ({@code at} is stored in {@code occurred_at}, {@code scope} in {@code grant_scope}, because AT and SCOPE are reserved
 * words of SQL:2003) are written and read through their repositories, and the text of the columns is what the schema files
 * say. The schema (one of its own, {@code ledger.schema.name}) is built by the real {@code db/V*.sql} files when the
 * context starts.
 */
// The DataSource is the one of the test database support (the shared pool, every connection in the schema of this context), so
// Boot's own replacement of the database is switched off (Replace.NONE).
@DataJdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = ReservedWordColumnsDataJdbcTest.Slice.class, initializers = TestSchemaInitializer.class)
class ReservedWordColumnsDataJdbcTest {

    private static final Instant WHEN = Instant.parse("2026-11-05T10:00:00.123456Z");

    /** The slice: the ledger's Spring Data JDBC configuration, the schema services, and the clock and client they need. */
    @Configuration(proxyBeanMethods = false)
    @Import({LedgerJdbcConfiguration.class, LedgerSchemaConfiguration.class, TestDataSourceConfiguration.class})
    static class Slice {

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }

        @Bean
        JdbcClient jdbcClient(DataSource dataSource) {
            return JdbcClient.create(dataSource);
        }

        /** Builds the schema once, when the context starts (DDL is not part of the rolled back test transaction). */
        @Bean
        SmartInitializingSingleton schemaIsMigrated(SchemaMigrator migrator) {
            return migrator::migrate;
        }
    }

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private SessionGrantRepository grants;

    @Autowired
    private HistoryRepository history;

    @Autowired
    private OwnerAuditRepository audit;

    @Test
    @DisplayName("REPO-061 a grant keeps its scope in grant_scope, a history row and an audit row their time in occurred_at")
    void theRenamedColumnsAreWrittenAndRead() {
        jdbc.sql("INSERT INTO project (id, code, title, version, created_by) VALUES ('P1', 'P1', 'p', 1, 'user:owner')")
                .update();

        grants.insert(SessionGrant.issue(1L, AuthorId.of("claude:a"), ProjectId.of("P1"), Scope.DELIVERABLE,
                AuthorId.OWNER, WHEN, null, null));
        history.insert(new HistoryEntry(1L, WHEN, AuthorId.of("claude:a"), "ledger_author", ActorAssurance.DECLARED,
                HistoryEntity.ISSUE, "P1-I-0001", HistoryOp.CREATE, null, null, JsonDocument.EMPTY_OBJECT, null,
                "a reason of enough length", null, null, null, new OperationKey(UUID.randomUUID().toString()),
                null, null));
        audit.insert(new OwnerAuditEntry(1L, WHEN, "token:owner", OwnerAction.GRANT_ISSUED, "claude:a|P1", null));

        assertThat(jdbc.sql("SELECT grant_scope FROM session_grant WHERE id = 1").query(String.class).single())
                .isEqualTo("DELIVERABLE");
        assertThat(grants.findById(1L)).get().extracting(SessionGrant::scope).isEqualTo(Scope.DELIVERABLE);
        assertThat(history.findById(1L)).get().extracting(HistoryEntry::at).isEqualTo(WHEN);
        assertThat(audit.findBySubjectOrderById("claude:a|P1")).extracting(OwnerAuditEntry::at).containsExactly(WHEN);
        assertThat(jdbc.sql("SELECT occurred_at FROM history WHERE id = 1").query(java.time.LocalDateTime.class)
                .single()).hasToString("2026-11-05T10:00:00.123456");
        assertThat(jdbc.sql("SELECT occurred_at FROM owner_audit WHERE id = 1").query(java.time.LocalDateTime.class)
                .single()).hasToString("2026-11-05T10:00:00.123456");
    }
}
