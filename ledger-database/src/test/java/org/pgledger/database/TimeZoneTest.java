package org.pgledger.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pgledger.database.repository.AttachmentFileRepository;
import org.pgledger.database.repository.AttachmentRepository;
import org.pgledger.database.repository.AttachmentStorageRepository;
import org.pgledger.database.repository.Counter;
import org.pgledger.database.repository.DeliverableRepository;
import org.pgledger.database.repository.GrantStateRepository;
import org.pgledger.database.repository.HistoryRepository;
import org.pgledger.database.repository.IdAllocator;
import org.pgledger.database.repository.IssueCaseRepository;
import org.pgledger.database.repository.IssueRepository;
import org.pgledger.database.repository.OwnerAuditRepository;
import org.pgledger.database.repository.PasskeyCodeRepository;
import org.pgledger.database.repository.PasskeyRepository;
import org.pgledger.database.repository.ReceiptRepository;
import org.pgledger.database.repository.SessionGrantRepository;
import org.pgledger.database.repository.SessionKeyRepository;
import org.pgledger.database.repository.SessionKeyStateRepository;
import org.pgledger.database.repository.SessionTokenRepository;
import org.pgledger.database.repository.SessionTokenStateRepository;
import org.pgledger.database.repository.SpecRepository;
import org.pgledger.database.repository.TaskRepository;
import org.pgledger.database.repository.UploadStagingRepository;
import org.pgledger.database.schema.LedgerSchemaGuardConfiguration;
import org.pgledger.database.schema.UtcSessionCheck;
import org.pgledger.database.support.Databases;
import org.pgledger.database.support.LedgerDatabaseExtension;
import org.pgledger.database.support.LedgerTestContext;
import org.pgledger.database.support.RecordingDataSource.Binding;
import org.pgledger.database.support.Seed;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;
import org.pgledger.domain.model.Attachment;
import org.pgledger.domain.model.AttachmentFile;
import org.pgledger.domain.model.AttachmentStorage;
import org.pgledger.domain.model.Deliverable;
import org.pgledger.domain.model.HistoryEntry;
import org.pgledger.domain.model.Issue;
import org.pgledger.domain.model.IssueCase;
import org.pgledger.domain.model.OwnerAuditEntry;
import org.pgledger.domain.model.OwnerPasskey;
import org.pgledger.domain.model.Project;
import org.pgledger.domain.model.Receipt;
import org.pgledger.domain.model.ReceiptOperation;
import org.pgledger.domain.model.SessionGrant;
import org.pgledger.domain.model.SessionPublicKey;
import org.pgledger.domain.model.SessionToken;
import org.pgledger.domain.model.Spec;
import org.pgledger.domain.model.SpecFields;
import org.pgledger.domain.model.Task;
import org.pgledger.domain.model.TaskFields;
import org.pgledger.domain.model.UploadStaging;
import org.pgledger.domain.rule.ActorAssurance;
import org.pgledger.domain.rule.Channel;
import org.pgledger.domain.rule.CheckResult;
import org.pgledger.domain.rule.DeliverableTarget;
import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.rule.HistoryOp;
import org.pgledger.domain.rule.OwnerAction;
import org.pgledger.domain.rule.Scope;
import org.pgledger.domain.rule.UploadState;
import org.pgledger.domain.value.AuthorId;
import org.pgledger.domain.value.CodeValue;
import org.pgledger.domain.value.JsonDocument;
import org.pgledger.domain.value.OperationKey;
import org.pgledger.domain.value.Patch;
import org.pgledger.domain.value.Sha256;
import org.pgledger.domain.value.SpecId;
import org.pgledger.domain.value.StorageKey;
import org.pgledger.domain.value.TaskId;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

/** REPO-029, REPO-030, SVC-TZ-01, SVC-TZ-02, SCH-019: every time value is UTC whatever the zones around it say. */
@ExtendWith(LedgerDatabaseExtension.class)
class TimeZoneTest {

    private static final Instant I = Instant.parse("2026-11-05T10:00:00.123456Z");

    private static <T> T inJvmZone(String zone, java.util.function.Supplier<T> work) {
        TimeZone saved = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone(zone));
        try {
            return work.get();
        } finally {
            TimeZone.setDefault(saved);
        }
    }

    private static LocalDateTime wall(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private LocalDateTime rawWall(LedgerTestContext ctx, String sql, Object... values) {
        return JdbcClient.create(ctx.pool()).sql(sql).params(values)
                .query((rs, row) -> rs.getObject(1, LocalDateTime.class)).single();
    }

    private record Stored(Instant instant, LocalDateTime wall) {
    }

    @ParameterizedTest
    @ValueSource(strings = {"Asia/Seoul", "America/New_York"})
    void repo029_svcTz02_theStoredValueIsUtcAndReadsBackEqualInOtherJvmAndSessionZones(String zone, Databases databases) {
        List<Instant> instants = List.of(I, Instant.parse("2026-03-08T07:30:00Z"),
                Instant.parse("2026-11-01T06:30:00Z"));
        try (LedgerTestContext ctx = LedgerTestContext.create(databases, zone)) {
            List<Stored> stored = inJvmZone(zone, () -> {
                Seed seed = new Seed(ctx);
                Project project = seed.project();
                return IntStream.range(0, instants.size()).mapToObj(i -> {
                    Issue issue = seed.issue(project, i + 1, "zone " + i, instants.get(i));
                    Issue read = ctx.bean(IssueRepository.class).findById(issue.id()).orElseThrow();
                    return new Stored(read.createdAt(), rawWall(ctx, "SELECT created_at FROM issue WHERE id = ?",
                            issue.id().value()));
                }).toList();
            });
            assertThat(stored).isEqualTo(instants.stream().map(i -> new Stored(i, wall(i))).toList());
            assertThat(stored.getFirst().wall().toString()).isEqualTo("2026-11-05T10:00:00.123456");
        }
    }

    @Test
    void svcTz01_aSessionThatIsNotUtcStopsTheApplicationAndUtcStartsIt(Databases databases) {
        try (LedgerTestContext seoul = LedgerTestContext.create(databases, "Asia/Seoul")) {
            UtcSessionCheck check = org.pgledger.testing.database.TestSchemaServices.utcCheck(seoul.pool(),
                    Clock.systemUTC());
            assertThatThrownBy(check::verify).isInstanceOfSatisfying(InfrastructureException.class, e -> {
                assertThat(e.code()).isEqualTo(InfrastructureCode.SESSION_ZONE_NOT_UTC);
                assertThat(e.args()).hasSize(1);
                assertThat(e.getMessage()).doesNotContain("jdbc:");
            });
            assertThatThrownBy(() -> startWithGuard(seoul)).isInstanceOf(BeanCreationException.class)
                    .hasStackTraceContaining("SESSION_ZONE_NOT_UTC");
        }
        try (LedgerTestContext utc = LedgerTestContext.create(databases, "UTC")) {
            org.pgledger.testing.database.TestSchemaServices.utcCheck(utc.pool(), Clock.systemUTC()).verify();
            startWithGuard(utc).close();
        }
        UtcSessionCheck.verifyJvmZone();
        inJvmZone("Asia/Seoul", () -> assertThatThrownBy(UtcSessionCheck::verifyJvmZone)
                .isInstanceOfSatisfying(InfrastructureException.class,
                        e -> assertThat(e.code()).isEqualTo(InfrastructureCode.JVM_ZONE_NOT_UTC)));
    }

    private AnnotationConfigApplicationContext startWithGuard(LedgerTestContext ctx) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getBeanFactory().registerSingleton("dataSource", ctx.pool());
        context.getBeanFactory().registerSingleton("jdbcClient",
                org.springframework.jdbc.core.simple.JdbcClient.create(ctx.pool()));
        context.getBeanFactory().registerSingleton("clock", Clock.systemUTC());
        context.register(LedgerSchemaGuardConfiguration.class);
        context.refresh();
        return context;
    }

    @Test
    void sch019_everyTimestampColumnIsWrittenThroughTheSingleUtcConversion(Databases databases) {
        String zone = "Asia/Seoul";
        try (LedgerTestContext ctx = LedgerTestContext.create(databases, zone)) {
            inJvmZone(zone, () -> {
                writeAllTimestampColumns(ctx);
                return null;
            });
            List<Binding> bound = ctx.spy().bindings();
            Class<?>[] forbidden = {java.sql.Timestamp.class, java.util.Date.class, Instant.class,
                    java.time.OffsetDateTime.class, java.time.ZonedDateTime.class};
            assertThat(bound).as("no timestamp is bound in any other way").noneMatch(
                    b -> b.type() != null && List.of(forbidden).contains(b.type()));
            assertThat(bound).noneMatch(b -> "setTimestamp".equals(b.setter()));
            assertThat(bound.stream().filter(b -> b.type() == LocalDateTime.class).count())
                    .isGreaterThanOrEqualTo(15);
            Map<String, Instant> expected = new LinkedHashMap<>();
            expected.put("issue.created_at", I);
            expected.put("issue.updated_at", I);
            expected.put("spec.created_at", I);
            expected.put("spec.updated_at", I);
            expected.put("task.created_at", I);
            expected.put("task.updated_at", I);
            expected.put("deliverable.checked_at", I.plusSeconds(1));
            expected.put("deliverable.recorded_at", I);
            expected.put("issue_case.observed_at", I.plusSeconds(2));
            expected.put("issue_case.recorded_at", I);
            expected.put("session_grant.granted_at", I);
            expected.put("session_grant.expires_at", I.plusSeconds(3600));
            expected.put("session_grant.revoked_at", I.plusSeconds(5));
            expected.put("session_grant.last_used_at", I.plusSeconds(4));
            expected.put("session_key.registered_at", I);
            expected.put("session_key.revoked_at", I.plusSeconds(6));
            expected.put("session_token.issued_at", I);
            expected.put("session_token.expires_at", I.plusSeconds(3600));
            expected.put("session_token.revoked_at", I.plusSeconds(7));
            expected.put("session_token.last_used_at", I.plusSeconds(4));
            expected.put("owner_audit.occurred_at", I);
            expected.put("history.occurred_at", I);
            expected.put("request_receipt.created_at", I);
            expected.put("schema_history.applied_at", LedgerTestContext.START);
            expected.put("owner_passkey.registered_at", I);
            expected.put("owner_passkey_revocation.revoked_at", I.plusSeconds(9));
            expected.put("owner_passkey_code.issued_at", I);
            expected.put("owner_passkey_code.expires_at", I.plusSeconds(900));
            expected.put("owner_passkey_code.used_at", I.plusSeconds(8));
            expected.put("upload_staging.expires_at", I.plusSeconds(600));
            expected.put("upload_staging.created_at", I);
            expected.put("upload_staging.updated_at", I.plusSeconds(10));
            expected.put("attachment.created_at", I);
            expected.put("attachment_storage.created_at", I.plusSeconds(11));
            assertThat(expected.keySet()).containsExactlyInAnyOrderElementsOf(SchemaCatalogTest.TIMESTAMP_COLUMNS);
            Map<String, LocalDateTime> actual = expected.keySet().stream().collect(Collectors.toMap(
                    column -> column, column -> rawWall(ctx, "SELECT MAX(" + column.substring(column.indexOf('.') + 1)
                            + ") FROM " + column.substring(0, column.indexOf('.')))));
            assertThat(actual).isEqualTo(expected.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,
                    e -> wall(e.getValue()))));
        }
    }

    private void writeAllTimestampColumns(LedgerTestContext ctx) {
        Seed seed = new Seed(ctx);
        IdAllocator ids = ctx.bean(IdAllocator.class);
        Project project = seed.project();
        Issue issue = seed.issue(project, 1, "all columns", I);
        LedgerTestContext.as(Seed.ALICE, () -> ctx.tx(() -> ctx.bean(SpecRepository.class).save(
                Spec.create(SpecId.issue(project.code(), 1),
                        SpecFields.of(Patch.set(project.id().value()), Patch.absent(), Patch.set("s"), Patch.set("d"),
                                Patch.set("ac"), Patch.absent(), Patch.absent(), Patch.absent(), Patch.absent()), I))));
        LedgerTestContext.as(Seed.ALICE, () -> ctx.tx(() -> ctx.bean(TaskRepository.class).save(
                Task.plan(TaskId.issue(project.code(), 1),
                        TaskFields.of(Patch.set(project.id().value()), Patch.set("t"), Patch.set("p"),
                                Patch.set("s"), Patch.absent(), Patch.absent(), Patch.absent()), I))));
        ctx.tx(() -> ctx.bean(DeliverableRepository.class).insert(Deliverable.record(ids.next(Counter.DELIVERABLE),
                DeliverableTarget.ISSUE, issue.id().value(), CodeValue.of("CODE_PATCH"), "f".repeat(40), null,
                "deliverable", "ref", CheckResult.PASSED, I.plusSeconds(1), null, Seed.ALICE, I)));
        ctx.tx(() -> ctx.bean(IssueCaseRepository.class).insert(IssueCase.observe(ids.next(Counter.ISSUE_CASE),
                issue.id(), "seen", null, null, I.plusSeconds(2), Seed.ALICE, I)));
        SessionGrant grant = ctx.tx(() -> ctx.bean(SessionGrantRepository.class).insert(SessionGrant.issue(
                ids.next(Counter.SESSION_GRANT), Seed.ALICE, project.id(), Scope.ISSUE, AuthorId.OWNER, I,
                I.plusSeconds(3600), null)));
        GrantStateRepository grants = ctx.bean(GrantStateRepository.class);
        ctx.tx(() -> grants.markUsed(Seed.ALICE, project.id(), Scope.ISSUE, I.plusSeconds(4)));
        ctx.tx(() -> grants.revoke(grant.id(), I.plusSeconds(5)));
        SessionPublicKey key = ctx.tx(() -> ctx.bean(SessionKeyRepository.class).insert(SessionPublicKey.register(
                ids.next(Counter.SESSION_KEY), Seed.ALICE, "MCowBQYDK2VwAyEA" + "A".repeat(43), "c".repeat(64),
                AuthorId.OWNER, I, null)));
        ctx.tx(() -> ctx.bean(SessionKeyStateRepository.class).close(key.id(), I.plusSeconds(6)));
        SessionToken token = ctx.tx(() -> ctx.bean(SessionTokenRepository.class).insert(SessionToken.issue(
                ids.next(Counter.SESSION_TOKEN), Seed.ALICE, "d".repeat(64), "pgtk_tz", AuthorId.OWNER, I,
                I.plusSeconds(3600), null)));
        SessionTokenStateRepository tokenStates = ctx.bean(SessionTokenStateRepository.class);
        ctx.tx(() -> {
            tokenStates.touchLastUsed(token.id(), I.plusSeconds(4));
            tokenStates.revoke(token.id(), I.plusSeconds(7));
            return null;
        });
        ctx.tx(() -> ctx.bean(OwnerAuditRepository.class).insert(new OwnerAuditEntry(
                ids.next(Counter.OWNER_AUDIT), I, "owner", OwnerAction.GRANT_ISSUED, "claude:a|p", null)));
        ctx.tx(() -> ctx.bean(HistoryRepository.class).insert(new HistoryEntry(ids.next(Counter.HISTORY), I,
                Seed.ALICE, Channel.AUTHOR.login(), ActorAssurance.DECLARED, HistoryEntity.ISSUE,
                issue.id().value(), HistoryOp.CREATE, null, null, JsonDocument.EMPTY_OBJECT, null,
                "a reason of enough length", null, null, null, new OperationKey(UUID.randomUUID().toString()),
                null, null)));
        ctx.tx(() -> ctx.bean(ReceiptRepository.class).insert(Receipt.reserve(
                new OperationKey(UUID.randomUUID().toString()), ReceiptOperation.SAVE, Sha256.of("a".repeat(64)),
                Seed.ALICE, Channel.AUTHOR, I)));
        PasskeyRepository passkeys = ctx.bean(PasskeyRepository.class);
        ctx.tx(() -> {
            passkeys.insert(new OwnerPasskey("credential-1", "owner", "public-key", 0, 1, 0, 0, null, "attestation",
                    "laptop", "AAAA-BBBB-CCCC-DDDD", "owner", I));
            passkeys.revoke("credential-1", "owner", I.plusSeconds(9));
            return null;
        });
        PasskeyCodeRepository codes = ctx.bean(PasskeyCodeRepository.class);
        ctx.tx(() -> {
            codes.insert("h".repeat(64), "owner", I, I.plusSeconds(900));
            return codes.spend("h".repeat(64), I.plusSeconds(8));
        });
        String attachmentId = UUID.randomUUID().toString();
        String uploadId = UUID.randomUUID().toString();
        StorageKey uploadKey = StorageKey.of("a".repeat(32));
        StorageKey fileKey = StorageKey.of("b".repeat(32));
        Sha256 sha = Sha256.of("c".repeat(64));
        ctx.tx(() -> {
            ctx.bean(UploadStagingRepository.class).insert(new UploadStaging(uploadId, attachmentId, 0, Seed.ALICE,
                    "op-tz", "f.txt", "text/plain", 1, sha, 0, 0, 0, null, uploadKey, "k1", UploadState.STAGING,
                    I.plusSeconds(600), I, I.plusSeconds(10)));
            ctx.bean(AttachmentRepository.class).insert(new Attachment(attachmentId, Seed.ALICE, I));
            ctx.bean(AttachmentFileRepository.class).insert(new AttachmentFile(attachmentId, 0, "f.txt", "text/plain",
                    1, sha));
            ctx.bean(AttachmentStorageRepository.class).insert(new AttachmentStorage(attachmentId, 0, 0, fileKey, "k1",
                    I.plusSeconds(11)));
            return null;
        });
        assertThat(JdbcClient.create(ctx.pool()).sql("SELECT COUNT(*) FROM issue").query(Integer.class).single())
                .isEqualTo(1);
    }
}
