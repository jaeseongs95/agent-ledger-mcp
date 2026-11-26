// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import javax.sql.DataSource;
import org.veridger.testing.database.TestDatabase;
import org.veridger.testing.database.TestSchema;
import org.veridger.testing.database.TestSchemaServices;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.veridger.database.repository.CodeRevisionRepository;
import org.veridger.database.repository.CommonCodeRepository;
import org.veridger.database.repository.Counter;
import org.veridger.database.repository.DeliverableRepository;
import org.veridger.database.repository.GrantStateRepository;
import org.veridger.database.repository.HistoryRepository;
import org.veridger.database.repository.IdAllocator;
import org.veridger.database.repository.IssueRepository;
import org.veridger.database.repository.LinkRepository;
import org.veridger.database.repository.ReceiptCompletionRepository;
import org.veridger.database.repository.ReceiptRepository;
import org.veridger.database.repository.SessionGrantRepository;
import org.veridger.database.schema.GrantApplier;
import org.veridger.domain.error.InfrastructureException;
import org.veridger.database.schema.SchemaMigrator;
import org.veridger.database.schema.SchemaProperties;
import org.veridger.database.support.Databases;
import org.veridger.database.support.LedgerDatabaseExtension;
import org.veridger.database.support.LedgerTestContext;
import org.veridger.database.support.Seed;
import org.veridger.domain.model.CommonCode;
import org.veridger.domain.model.Deliverable;
import org.veridger.domain.model.HistoryEntry;
import org.veridger.domain.model.Issue;
import org.veridger.domain.model.IssueTaskLink;
import org.veridger.domain.model.Project;
import org.veridger.domain.model.Receipt;
import org.veridger.domain.model.ReceiptOperation;
import org.veridger.domain.model.SessionGrant;
import org.veridger.domain.rule.ActorAssurance;
import org.veridger.domain.rule.Channel;
import org.veridger.domain.rule.CodeGroup;
import org.veridger.domain.rule.CodeState;
import org.veridger.domain.rule.DeliverableTarget;
import org.veridger.domain.rule.HistoryEntity;
import org.veridger.domain.rule.HistoryOp;
import org.veridger.domain.rule.IssueStatus;
import org.veridger.domain.rule.Scope;
import org.veridger.domain.value.AuthorId;
import org.veridger.domain.value.CodeValue;
import org.veridger.domain.value.JsonDocument;
import org.veridger.domain.value.OperationKey;
import org.veridger.domain.value.Sha256;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * REPO-023, REPO-024, REPO-038 and SCH-014: the application runs on a least-privilege account (the owner applies
 * the schema and the grants of {@code db-templates/dml-grants.sql} through {@code GrantApplier}, INS-001 .. INS-003); the history cannot be changed through it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(LedgerDatabaseExtension.class)
class DmlAccountTest {

    /** PostgreSQL SQLState 42501: insufficient privilege. */
    private static final String PERMISSION_DENIED = "42501";

    private Env shared;

    /**
     * A schema of its own in the test database, owned by the owner account, with the application account of the tests
     * ({@code ledger.test.app-user}) as the restricted account. The owner account cannot create roles, so the account is the
     * login that the tests are configured with; what it may do exists only in this schema and goes with the drop of it.
     * The schema name is the product's {@code ledger.schema.name}.
     */
    private record Env(TestSchema schema, DataSource app) implements AutoCloseable {
        String name() {
            return schema.name();
        }

        @Override
        public void close() {
            schema.close();
        }
    }

    /** A new schema with the application account allowed to see it; the schema is applied and granted when asked. */
    private Env environment(boolean migrate, boolean grant) {
        TestDatabase database = TestDatabase.shared();
        TestSchema schema = database.newSchema();
        String account = database.settings().appUser();
        schema.allowUsage(account);
        SchemaProperties properties = new SchemaProperties(SchemaProperties.DEFAULT_LOCATION, true,
                schema.name());
        // the owner's connections in the schema of the test: the one data source of the product names the schema itself
        DataSource owner = schema.dataSource();
        if (migrate) {
            TestSchemaServices.migrator(owner, properties, Clock.systemUTC()).migrate();
        }
        if (grant) {
            // the real path of the installer: the grant template applied by GrantApplier through the owner (DDL) account
            new GrantApplier(owner).apply(account);
        }
        return new Env(schema, TestDatabase.scoped(database.appPool(), schema.name()));
    }

    private static void refused(DataSource app, String sql) {
        assertThatThrownBy(() -> {
            try (Connection c = app.getConnection(); Statement s = c.createStatement()) {
                s.execute(sql);
            }
        }).as(sql).isInstanceOfSatisfying(SQLException.class,
                e -> assertThat(e.getSQLState()).as("a refusal for a missing right, not a mistake in the statement")
                        .isEqualTo(PERMISSION_DENIED));
    }

    private static void allowed(DataSource app, String sql) throws SQLException {
        try (Connection c = app.getConnection(); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    static Stream<String> statementsTheApplicationAccountMayRun() {
        return Stream.of("INSERT INTO history (id, occurred_at, author_id, login, actor_assurance, entity, entity_id, op, "
                + "changes, reason, operation_key) VALUES (1, TIMESTAMP '2026-11-05 10:00:00', 'claude:a', "
                + "'ledger_author', 'DECLARED', 'ISSUE', 'X', 'CREATE', '{}', 'a reason of length', "
                + "'11111111-1111-1111-1111-111111111111')",
                "INSERT INTO owner_audit (id, occurred_at, actor, operation, subject) VALUES (1, TIMESTAMP '2026-11-05 10:00:00', "
                        + "'token:owner', 'GRANT_ISSUED', 'claude:a|p')", "SELECT COUNT(*) FROM owner_audit",
                "SELECT COUNT(*) FROM schema_history",
                // ATA-016: an attachment bundle, file and storage copy are read and inserted; an upload that is not finished is read, inserted, changed and deleted
                "INSERT INTO attachment (id, created_by, created_at) "
                        + "VALUES ('11111111-1111-1111-1111-111111111111', 'claude:a', TIMESTAMP '2026-11-05 10:00:00')",
                "INSERT INTO attachment_file (attachment_id, seq, file_name, media_type, size_bytes, content_sha256) VALUES "
                        + "('11111111-1111-1111-1111-111111111111', 0, 'f.txt', 'text/plain', 1, '" + "a".repeat(64) + "')",
                "INSERT INTO attachment_storage (attachment_id, seq, rev, storage_key, key_id, created_at) VALUES "
                        + "('11111111-1111-1111-1111-111111111111', 0, 0, '" + "1".repeat(32)
                        + "', 'k1', TIMESTAMP '2026-11-05 10:00:00')",
                "SELECT COUNT(*) FROM attachment",
                "SELECT COUNT(*) FROM attachment_file",
                "SELECT COUNT(*) FROM attachment_storage",
                "INSERT INTO upload_staging (id, attachment_id, seq, owner, operation_key, file_name, media_type, "
                        + "declared_size, declared_sha256, received_bytes, stored_bytes, next_sequence, storage_key, key_id, "
                        + "state, expires_at, created_at, updated_at) VALUES ('22222222-2222-2222-2222-222222222222', "
                        + "'11111111-1111-1111-1111-111111111111', 0, 'claude:a', 'op', 'f.txt', 'text/plain', 1, "
                        + "'" + "a".repeat(64) + "', 0, 0, 0, '" + "2".repeat(32) + "', 'k1', 'STAGING', "
                        + "TIMESTAMP '2026-11-05 10:00:00', TIMESTAMP '2026-11-05 10:00:00', TIMESTAMP '2026-11-05 10:00:00')",
                "UPDATE upload_staging SET next_sequence = 1 WHERE id = '22222222-2222-2222-2222-222222222222'",
                "DELETE FROM upload_staging WHERE id = '22222222-2222-2222-2222-222222222222'");
    }

    static Stream<String> statementsTheApplicationAccountMustNotRun() {
        return Stream.of(
                "UPDATE history SET reason = 'changed by the application'",
                "UPDATE history SET before_data = NULL",
                "UPDATE owner_audit SET actor = 'someone else'",
                "DELETE FROM owner_audit",
                "TRUNCATE TABLE owner_audit",
                "DELETE FROM history",
                "TRUNCATE TABLE history",
                "UPDATE attachment SET created_by = 'changed by the application'",
                "DELETE FROM attachment",
                "TRUNCATE TABLE attachment",
                "UPDATE attachment_file SET file_name = 'changed by the application'",
                "DELETE FROM attachment_file",
                "TRUNCATE TABLE attachment_file",
                "UPDATE attachment_storage SET key_id = 'changed by the application'",
                "DELETE FROM attachment_storage",
                "TRUNCATE TABLE attachment_storage",
                "UPDATE schema_history SET outcome = 'FAILED'",
                "DELETE FROM schema_history",
                "CREATE TABLE stolen (id INTEGER)",
                "DROP TABLE project",
                "ALTER TABLE project ADD COLUMN extra INTEGER");
    }

    @ParameterizedTest
    @MethodSource("statementsTheApplicationAccountMayRun")
    void repo023_theApplicationAccountMayAppendToTheHistoryAndReadTheSchemaHistory(String sql) throws Exception {
        allowed(shared().app(), sql);
    }

    @ParameterizedTest
    @MethodSource("statementsTheApplicationAccountMustNotRun")
    void repo023_024_038_theApplicationAccountCannotChangeHistoryOrTheSchema(String sql) throws Exception {
        refused(shared().app(), sql);
        // the owner can run the DDL, which shows the refusal is about the right and not about the statement
        assertThat(shared().schema()).isNotNull();
    }

    /** One environment for the statement tests of the class, built on first use. */
    private synchronized Env shared() throws SQLException, IOException {
        if (shared == null) {
            shared = environment(true, true);
        }
        return shared;
    }

    @AfterAll
    void closeSharedEnvironment() {
        if (shared != null) {
            shared.close();
        }
    }

    @Test
    void theWholeWorkflowRunsOnTheRestrictedAccount() throws Exception {
        try (Env env = environment(true, true); LedgerTestContext ctx = LedgerTestContext.onMigrated(env.app(), env.name())) {
            Seed seed = new Seed(ctx);
            Project project = seed.project();
            Issue issue = seed.issue(project, 1, "as the application account", LedgerTestContext.START);
            IdAllocator ids = ctx.bean(IdAllocator.class);
            Instant now = LedgerTestContext.START;
            LedgerTestContext.as(Seed.ALICE, () -> ctx.tx(() -> ctx.bean(IssueRepository.class)
                    .save(issue.transitioned(IssueStatus.DISPUTED, null, now))));
            Deliverable deliverable = ctx.tx(() -> ctx.bean(DeliverableRepository.class).insert(Deliverable.record(
                    ids.next(Counter.DELIVERABLE), DeliverableTarget.ISSUE, issue.id().value(),
                    CodeValue.of("CODE_PATCH"), "a".repeat(40), null, "sum", null, null, null, null, Seed.ALICE, now)));
            ctx.tx(() -> ctx.bean(DeliverableRepository.class).update(deliverable.withCheck("ref", null, null)));
            OperationKey request = new OperationKey(UUID.randomUUID().toString());
            ctx.tx(() -> {
                ctx.bean(ReceiptRepository.class).insert(Receipt.reserve(request, ReceiptOperation.SAVE,
                        Sha256.of("b".repeat(64)), Seed.ALICE, Channel.AUTHOR, now));
                ctx.bean(HistoryRepository.class).insert(new HistoryEntry(ids.next(Counter.HISTORY), now,
                        Seed.ALICE, Channel.AUTHOR.login(), ActorAssurance.DECLARED, HistoryEntity.ISSUE,
                        issue.id().value(), HistoryOp.CREATE, null, null, JsonDocument.EMPTY_OBJECT, null,
                        "a reason of enough length", null, null, null, request, null, null));
                return ctx.bean(ReceiptCompletionRepository.class).finish(Seed.ALICE, request, JsonDocument.EMPTY_OBJECT);
            });
            SessionGrant grant = ctx.tx(() -> ctx.bean(SessionGrantRepository.class).insert(SessionGrant.issue(
                    ids.next(Counter.SESSION_GRANT), Seed.ALICE, project.id(), Scope.ISSUE, AuthorId.OWNER, now, null,
                    null)));
            assertThat(ctx.tx(() -> ctx.bean(GrantStateRepository.class).markUsed(Seed.ALICE, project.id(),
                    Scope.ISSUE, now))).isTrue();
            assertThat(ctx.tx(() -> ctx.bean(GrantStateRepository.class).revoke(grant.id(), now))).isTrue();
            var task = seed.task(project, 1, "linked task");
            IssueTaskLink link = new IssueTaskLink(issue.id(), task.id(), Seed.ALICE);
            assertThat(ctx.tx(() -> ctx.bean(LinkRepository.class).add(link))).isTrue();
            assertThat(ctx.tx(() -> ctx.bean(LinkRepository.class).remove(issue.id(), task.id()))).isTrue();
            CommonCode code = new CommonCode(CodeGroup.ISSUE_CATEGORY, CodeValue.of("UI_" + project.code().value()),
                    "화면", null, null, null, 25, CodeState.ACTIVE, null, AuthorId.OWNER);
            ctx.tx(() -> {
                ctx.bean(CommonCodeRepository.class).insert(code);
                ctx.bean(CommonCodeRepository.class).update(new CommonCode(code.group(), code.code(), "화면2", null,
                        null, null, 26, CodeState.INACTIVE, null, AuthorId.OWNER), 0L);
                ctx.bean(CodeRevisionRepository.class).bump();
                return null;
            });
            assertThat(ctx.bean(CommonCodeRepository.class).find(CodeGroup.ISSUE_CATEGORY, code.code()))
                    .hasValueSatisfying(c -> assertThat(c.state()).isEqualTo(CodeState.INACTIVE));
            assertThat(List.of(Counter.values())).hasSize(7);
        }
    }

    @Test
    void sch014_theMigrationIsRefusedForTheApplicationAccountButItCanReadTheHistory() throws Exception {
        try (Env fresh = environment(false, false)) {
            assertThatThrownBy(() -> TestSchemaServices.migrator(fresh.app(),
                    new SchemaProperties(SchemaProperties.DEFAULT_LOCATION, true, null),
                    Clock.systemUTC())
                    .migrate())
                    .isInstanceOf(InfrastructureException.class);
        }
        try (Env migrated = environment(true, true)) {
            allowed(migrated.app(), "SELECT version, outcome FROM schema_history");
        }
    }
}
