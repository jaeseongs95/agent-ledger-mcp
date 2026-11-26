// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.migrate1x.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.veridger.application.admin.GrantService;
import org.veridger.application.admin.GrantService.GrantCommand;
import org.veridger.application.support.Expect;
import org.veridger.application.support.Fields;
import org.veridger.application.support.LedgerAppContext;
import org.veridger.application.support.Scenario;
import org.veridger.application.write.RecordCaseCommand;
import org.veridger.application.write.RecordCaseService;
import org.veridger.application.write.SaveCommand;
import org.veridger.application.write.SaveService;
import org.veridger.application.write.TransitionCommand;
import org.veridger.application.write.TransitionService;
import org.veridger.domain.error.InfrastructureCode;
import org.veridger.domain.error.LedgerException;
import org.veridger.domain.value.Sha256;
import org.veridger.migrate1x.database.LegacyRow;
import org.veridger.migrate1x.database.LegacySourceConfiguration;
import org.veridger.migrate1x.database.LegacyTable;
import org.veridger.migrate1x.database.LegacyTargetRepository;
import org.veridger.migrate1x.fixture.LegacyFixtures;
import org.veridger.migrate1x.fixture.LegacyLedger;
import org.veridger.migrate1x.support.ImportRunners;
import org.veridger.testing.TestOwners;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionAttribute;

/**
 * R9-D0064 and R9-D0070: the precondition of {@code retire-1x} (what it holds the new ledger to) and the transaction its
 * reading runs in, on the real application context over the PostgreSQL of the test PC, with the 1.x ledger of
 * {@link LegacyFixtures} (with the database objects, read as the owner account of the tests).
 */
class LegacyRetireAfterUseTest {

    private static final String SEED = LegacyFixtures.SEED;
    private static final Set<String> NO_ACCOUNTS = Set.of();

    private static void withRig(Consumer<LedgerAppContext> body, Class<?>... configurations) {
        LegacyLedger legacy = LegacyFixtures.withDatabaseObjects(LegacyFixtures.rich());
        try (legacy) {
            ImportRunners.importing(legacy, legacy.admin(), body, configurations);
        }
    }

    private static LegacyImportService importer(LedgerAppContext ctx) {
        return ctx.bean(LegacyImportService.class);
    }

    private static LegacyRetireService retirer(LedgerAppContext ctx) {
        return ctx.bean(LegacyRetireService.class);
    }

    /** What an author does in 2.0 after the move: a new issue and task, an edit, a transition, a case on an issue nobody edits. */
    private static void useTheLedger(LedgerAppContext ctx) {
        ctx.enroll(SEED);
        ctx.bean(GrantService.class).grant(new GrantCommand(SEED, "old", List.of("deliverable"), null, null), TestOwners.OWNER);
        SaveService save = ctx.bean(SaveService.class);
        Map<String, String> issue = new LinkedHashMap<>();
        issue.put("project_id", "old");
        issue.put("title", "after the import");
        issue.put("category", "OLDBUG");
        issue.put("severity", "P2");
        issue.put("symptom", "symptom");
        issue.put("root_cause", "cause");
        save.save(SEED, new SaveCommand(Scenario.uuid(), "issue", null, 0L, Fields.of(issue), Scenario.REASON));
        save.save(SEED, new SaveCommand(Scenario.uuid(), "task", null, 0L,
                Fields.of(Map.of("project_id", "old", "title", "new task", "purpose", "p", "success_condition", "s")),
                Scenario.REASON));
        save.save(SEED, new SaveCommand(Scenario.uuid(), "issue", "OLD-D-0001", 1L, Fields.of("title", "edited after the import"),
                Scenario.REASON));
        ctx.bean(TransitionService.class).transition(SEED, new TransitionCommand(Scenario.uuid(), "task", "OLD-T-1", 1L, "ACTIVE",
                Scenario.REASON, null, null, null));
        ctx.bean(RecordCaseService.class).recordCase(SEED, new RecordCaseCommand(Scenario.uuid(), "OLD-D-0003",
                "seen again after the import", Scenario.REASON, null, null, null));
    }

    private static String refusedTable(LedgerAppContext ctx) {
        LedgerException failure = Expect.failure(() -> retirer(ctx).plan(NO_ACCOUNTS), InfrastructureCode.INTEGRITY_VIOLATION);
        assertThat(failure.args()).hasSize(5);
        return failure.args().get(0);
    }

    @Test
    @DisplayName("R9-D0064 after 2.0 was used (a new issue and task, an edit, a transition, a case: new rows, a higher version, the counters of the project and of an issue moved on) the listing of retire-1x is made: the new ledger still holds what 1.x held")
    void r9D0064_retireAcceptsALedgerThatWasUsed() {
        withRig(ctx -> {
            importer(ctx).importLegacy(TestOwners.OWNER);
            useTheLedger(ctx);

            LegacyRetirePlan plan = retirer(ctx).plan(NO_ACCOUNTS);

            assertThat(plan.lines()).anyMatch(line -> line.startsWith("SCHEMA "));
        });
    }

    @Test
    @DisplayName("R9-D0064 after 2.0 was used the precondition still refuses what it is for: a row of 1.x that is gone (a deliverable), a row that never changes in 2.0 and was changed (a case, a history row), a row at its imported version that was changed (an issue nobody edited)")
    void r9D0064_retireStillRefusesALedgerThatLostWhatWasMoved() {
        withRig(ctx -> {
            importer(ctx).importLegacy(TestOwners.OWNER);
            useTheLedger(ctx);
            assertThat(retirer(ctx).plan(NO_ACCOUNTS)).isNotNull();

            String title = ctx.jdbc().sql("SELECT title FROM issue WHERE id = 'E2E-D-0100'").query(String.class).single();
            ctx.jdbc().sql("UPDATE issue SET title = 'tampered' WHERE id = 'E2E-D-0100'").update();
            assertThat(refusedTable(ctx)).isEqualTo("issue");
            ctx.jdbc().sql("UPDATE issue SET title = ? WHERE id = 'E2E-D-0100'").param(title).update();

            String observation = ctx.jdbc().sql("SELECT observation FROM issue_case WHERE id = 2").query(String.class).single();
            ctx.jdbc().sql("UPDATE issue_case SET observation = 'tampered' WHERE id = 2").update();
            assertThat(refusedTable(ctx)).isEqualTo("issue_case");
            ctx.jdbc().sql("UPDATE issue_case SET observation = ? WHERE id = 2").param(observation).update();
            assertThat(retirer(ctx).plan(NO_ACCOUNTS)).as("the rows are as they were: accepted again").isNotNull();

            ctx.jdbc().sql("DELETE FROM deliverable WHERE id = 3").update();
            assertThat(refusedTable(ctx)).isEqualTo("deliverable");
        });
    }

    /** Records the transaction of the 1.x connection in which each read of the new schema of the comparison runs. */
    public static class IsolationProbe extends LegacyTargetRepository {

        record Seen(int isolation, boolean readOnly) {
        }

        private final DataSource legacy;
        private final List<Seen> seen = new ArrayList<>();

        public IsolationProbe(JdbcClient jdbc, DataSource ledger, DataSource legacy) {
            super(jdbc, ledger);
            this.legacy = legacy;
        }

        List<Seen> seen() {
            return seen;
        }

        @Override
        public void scan(LegacyTable table, Consumer<LegacyRow> consumer) {
            Connection connection = DataSourceUtils.getConnection(legacy);
            try {
                seen.add(new Seen(connection.getTransactionIsolation(), connection.isReadOnly()));
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            } finally {
                DataSourceUtils.releaseConnection(connection, legacy);
            }
            super.scan(table, consumer);
        }

        /** Puts the probe in place of the real write side. */
        @Configuration(proxyBeanMethods = false)
        public static class Replacement {

            @Bean
            @Primary
            IsolationProbe isolationProbe(JdbcClient jdbc, DataSource ledger,
                    @Qualifier(LegacySourceConfiguration.DATA_SOURCE) DataSource legacy) {
                return new IsolationProbe(jdbc, ledger, legacy);
            }
        }
    }

    @Test
    @DisplayName("R9-D0070 the reading of the comparison runs in one read-only snapshot of the 1.x database, REPEATABLE READ, in the import and in the check that retire-1x makes (the snapshot of the service is joined by the transaction of retire-1x, so that transaction has to be the snapshot)")
    void r9D0070_theCheckOfRetireReadsOneSnapshot() {
        withRig(ctx -> {
            IsolationProbe probe = ctx.bean(IsolationProbe.class);
            importer(ctx).importLegacy(TestOwners.OWNER);
            assertThat(probe.seen()).as("the import: the control of the probe").isNotEmpty()
                    .allSatisfy(seen -> assertThat(seen.isolation()).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ));
            probe.seen().clear();

            retirer(ctx).plan(NO_ACCOUNTS);

            assertThat(probe.seen()).as("the comparison of the listing of retire-1x").isNotEmpty().allSatisfy(seen -> {
                assertThat(seen.isolation()).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
                assertThat(seen.readOnly()).as("the listing writes nothing").isTrue();
            });
        }, IsolationProbe.Replacement.class);
    }

    @Test
    @DisplayName("R9-D0070 the removal runs in the same isolation as the listing: both methods of LegacyRetireService are REPEATABLE READ on the transaction manager of the 1.x database, the listing read only")
    void r9D0070_bothMethodsAreOneSnapshot() throws NoSuchMethodException {
        AnnotationTransactionAttributeSource source = new AnnotationTransactionAttributeSource();
        Method plan = LegacyRetireService.class.getMethod("plan", Set.class);
        Method retire = LegacyRetireService.class.getMethod("retire", Sha256.class, Set.class);

        TransactionAttribute listing = source.getTransactionAttribute(plan, LegacyRetireService.class);
        TransactionAttribute removal = source.getTransactionAttribute(retire, LegacyRetireService.class);

        assertThat(listing).isNotNull();
        assertThat(listing.getIsolationLevel()).isEqualTo(TransactionAttribute.ISOLATION_REPEATABLE_READ);
        assertThat(listing.isReadOnly()).isTrue();
        assertThat(removal).isNotNull();
        assertThat(removal.getIsolationLevel()).isEqualTo(TransactionAttribute.ISOLATION_REPEATABLE_READ);
        assertThat(removal.isReadOnly()).isFalse();
    }
}
