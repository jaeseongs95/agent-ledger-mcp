// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.veridger.application.attachment.AttachmentFlow;
import org.veridger.application.attachment.AttachmentMaintenanceService;
import org.veridger.application.attachment.AttachmentProperties;
import org.veridger.application.attachment.AttachmentStore;
import org.veridger.application.attachment.StorageKeys;
import org.veridger.application.attachment.UploadCommands.Begin;
import org.veridger.application.attachment.UploadCommands.BeginFile;
import org.veridger.application.attachment.UploadCommands.Begun;
import org.veridger.application.attachment.UploadCommands.Finished;
import org.veridger.application.attachment.UploadCommands.Received;
import org.veridger.application.attachment.UploadService;
import org.veridger.application.attachment.UploadService.ChunkPlan;
import org.veridger.application.support.AttachmentContext;
import org.veridger.application.support.Expect;
import org.veridger.application.support.HeldTransaction;
import org.veridger.application.support.LedgerAppContext;
import org.veridger.testing.database.LockWaits;
import org.veridger.application.support.Parallel;
import org.veridger.application.support.Parallel.Outcome;
import org.veridger.application.support.Scenario;
import org.veridger.application.support.Uploads;
import org.veridger.database.repository.UploadStagingRepository;
import org.veridger.domain.error.ConflictCode;
import org.veridger.domain.error.ConflictException;
import org.veridger.domain.model.AttachmentStorage;
import org.veridger.domain.model.UploadStaging;
import org.veridger.domain.value.Sha256;
import org.veridger.domain.value.StorageKey;
import org.veridger.testing.TestOwners;
import org.springframework.dao.DuplicateKeyException;

/**
 * ATA-009 (two requests for the same chunk of the same upload) and ATA-033 (two rekeys of the same current file) on the PostgreSQL of
 * the test PC with real threads and two connections. Each case runs twice. First deterministic: one side is a transaction on its own
 * connection that stays open ({@link HeldTransaction}), the other side is started, and the test waits until PostgreSQL shows a statement
 * waiting for a lock of the first connection ({@link LockWaits}) before it lets the first commit. Then at one barrier
 * ({@link Parallel#all}), many rounds, with no claim about who was first: what is asserted is the effect (one chunk applied once, the
 * file as long as the row says, the loser's answer one of the allowed ones, the finish giving the declared hash).
 */
@AttachmentContext
class AttachmentRaceTest {

    private static final int ROUNDS = 12;
    private static final int CHUNK = 200;

    /** An upload of 400 bytes in two chunks of 200 whose first chunk is recorded; the second chunk is the one that is raced for. */
    private record Upload(String actor, String id, byte[] body, List<byte[]> parts) {
    }

    private static Upload started(Scenario s, long seed) {
        String actor = s.actorWith("issue");
        AttachmentFlow flow = s.bean(AttachmentFlow.class);
        byte[] body = Uploads.body(2 * CHUNK, seed);
        List<byte[]> parts = Uploads.parts(body, CHUNK);
        String id = flow.begin(actor, Uploads.begin(body)).files().getFirst().uploadId();
        Uploads.send(flow, actor, id, 0, parts.get(0));
        return new Upload(actor, id, body, parts);
    }

    /** Steps 1 to 3 of design 4.4 for chunk 1: the plan of the service and the append to the file; what the database is to record. */
    private static long appended(Scenario s, Upload u, byte[] part) {
        Sha256 sha = Sha256.ofBytes(part);
        UploadStaging row = ((ChunkPlan.Append) s.bean(UploadService.class).planChunk(u.actor(), u.id(), 1, part.length, sha)).row();
        return s.bean(AttachmentStore.class).append(row.storageKey(), row.keyId(), row.storedBytes(), 1, part, sha);
    }

    private static void assertAppliedOnce(Scenario s, Upload u) throws IOException {
        UploadStaging row = Uploads.row(s.ctx(), u.id());
        assertThat(row.nextSequence()).as("chunk 1 is applied once").isEqualTo(2);
        assertThat(row.receivedBytes()).isEqualTo(2 * CHUNK);
        assertThat(Files.size(Uploads.fileOf(s.ctx(), row.storageKey()))).as("no segment was appended twice")
                .isEqualTo(row.storedBytes());
    }

    // ------------------------------------------------------------------------------------------------ ATA-009

    @Test
    @DisplayName("ATA-009 (a): the same chunk twice, the first not committed yet: the second waits for it and answers idempotently with the current state; the chunk is applied once and the finish gives the declared SHA-256")
    void ata009_theSameChunkTwice(Scenario s) throws Exception {
        LedgerAppContext ctx = s.ctx();
        AttachmentFlow flow = s.bean(AttachmentFlow.class);
        UploadStagingRepository uploads = s.bean(UploadStagingRepository.class);
        for (int round = 0; round < ROUNDS; round++) {
            Upload u = started(s, 100L + round);
            byte[] part = u.parts().get(1);
            long stored = appended(s, u, part);
            Sha256 sha = Sha256.ofBytes(part);
            Instant now = ctx.clock().instant();

            // the first request: its segment is in the file and its conditional UPDATE has moved the row, not committed yet
            HeldTransaction<Boolean> first = HeldTransaction.start(ctx,
                    () -> uploads.advance(u.id(), 1, stored, 2 * CHUNK, sha, now.plus(Duration.ofMinutes(10)), now));
            int holder = first.awaitHeld();
            Future<Received> second = Parallel.start(() -> Uploads.send(flow, u.actor(), u.id(), 1, part));
            LockWaits.awaitWaitingFor(ctx.jdbc(), holder);
            assertThat(first.release()).as("the first request moved the row").isTrue();

            Received applied = new Received(1, 2 * CHUNK);
            assertThat(second.get(60, TimeUnit.SECONDS)).as("the loser answers with the state (round %d)", round).isEqualTo(applied);
            assertAppliedOnce(s, u);
            Finished finished = flow.finish(u.actor(), Uploads.row(ctx, u.id()).attachmentId());
            assertThat(finished.files().getFirst().sha256()).isEqualTo(Uploads.sha(u.body()));
            assertThat(Uploads.readAll(flow, u.actor(), finished.attachmentId(), 150)).isEqualTo(u.body());
        }
    }

    @Test
    @DisplayName("ATA-009 (b): another content for the chunk that another request has appended and not recorded yet: ARTIFACT_CHUNK_CONFLICT, and the winner's chunk is applied and finishes with the declared SHA-256")
    void ata009_anotherContentForTheSameChunk(Scenario s) throws Exception {
        LedgerAppContext ctx = s.ctx();
        AttachmentFlow flow = s.bean(AttachmentFlow.class);
        UploadStagingRepository uploads = s.bean(UploadStagingRepository.class);
        for (int round = 0; round < ROUNDS; round++) {
            Upload u = started(s, 200L + round);
            byte[] part = u.parts().get(1);
            byte[] other = Uploads.body(CHUNK, 300L + round);
            long stored = appended(s, u, part);
            Sha256 sha = Sha256.ofBytes(part);
            Instant now = ctx.clock().instant();

            HeldTransaction<Boolean> first = HeldTransaction.start(ctx,
                    () -> uploads.advance(u.id(), 1, stored, 2 * CHUNK, sha, now.plus(Duration.ofMinutes(10)), now));
            first.awaitHeld();
            Expect.failure(() -> Uploads.send(flow, u.actor(), u.id(), 1, other), ConflictCode.ARTIFACT_CHUNK_CONFLICT);
            assertThat(first.release()).isTrue();

            Expect.failure(() -> Uploads.send(flow, u.actor(), u.id(), 1, other), ConflictCode.ARTIFACT_CHUNK_CONFLICT);
            assertAppliedOnce(s, u);
            Finished finished = flow.finish(u.actor(), Uploads.row(ctx, u.id()).attachmentId());
            assertThat(finished.files().getFirst().sha256()).isEqualTo(Uploads.sha(u.body()));
        }
    }

    @Test
    @DisplayName("ATA-009 (c): two requests with the same chunk at one barrier, many rounds: the chunk is applied once; the answers are the state or ARTIFACT_BUSY, never two segments")
    void ata009_atOneBarrierTheSameContent(Scenario s) throws Exception {
        AttachmentFlow flow = s.bean(AttachmentFlow.class);
        for (int round = 0; round < ROUNDS; round++) {
            Upload u = started(s, 400L + round);
            byte[] part = u.parts().get(1);

            List<Outcome<Received>> outcomes = Parallel.<Received>all(List.of(() -> Uploads.send(flow, u.actor(), u.id(), 1, part),
                    () -> Uploads.send(flow, u.actor(), u.id(), 1, part)));

            long successes = outcomes.stream().filter(Outcome::succeeded).count();
            assertThat(successes).as("at least one request applied or found the chunk (round %d)", round).isGreaterThanOrEqualTo(1);
            for (Outcome<Received> outcome : outcomes) {
                if (outcome.succeeded()) {
                    assertThat(outcome.value()).isEqualTo(new Received(1, 2 * CHUNK));
                } else {
                    assertThat(outcome.failure()).isInstanceOfSatisfying(ConflictException.class,
                            failure -> assertThat(failure.code()).isEqualTo(ConflictCode.ARTIFACT_BUSY));
                }
            }
            if (successes < outcomes.size()) {
                assertThat(Uploads.send(flow, u.actor(), u.id(), 1, part)).as("the busy one sent again is idempotent")
                        .isEqualTo(new Received(1, 2 * CHUNK));
            }
            assertAppliedOnce(s, u);
            Finished finished = flow.finish(u.actor(), Uploads.row(s.ctx(), u.id()).attachmentId());
            assertThat(finished.files().getFirst().sha256()).isEqualTo(Uploads.sha(u.body()));
        }
    }

    @Test
    @DisplayName("ATA-009 (d): two requests with different content for the same chunk at one barrier, many rounds: exactly one is applied, the other is ARTIFACT_CHUNK_CONFLICT or ARTIFACT_BUSY; the finish agrees with the winner")
    void ata009_atOneBarrierOtherContent(Scenario s) throws Exception {
        AttachmentFlow flow = s.bean(AttachmentFlow.class);
        for (int round = 0; round < ROUNDS; round++) {
            Upload u = started(s, 500L + round);
            byte[] declared = u.parts().get(1);
            byte[] other = Uploads.body(CHUNK, 600L + round);

            List<Outcome<Received>> outcomes = Parallel.<Received>all(List.of(() -> Uploads.send(flow, u.actor(), u.id(), 1, declared),
                    () -> Uploads.send(flow, u.actor(), u.id(), 1, other)));

            long successes = outcomes.stream().filter(Outcome::succeeded).count();
            assertThat(successes).as("exactly one chunk is applied (round %d)", round).isEqualTo(1);
            Outcome<Received> loser = outcomes.stream().filter(outcome -> !outcome.succeeded()).findFirst().orElseThrow();
            assertThat(loser.failure()).isInstanceOfSatisfying(ConflictException.class,
                    failure -> assertThat(failure.code()).isIn(ConflictCode.ARTIFACT_CHUNK_CONFLICT, ConflictCode.ARTIFACT_BUSY));
            assertAppliedOnce(s, u);
            String attachmentId = Uploads.row(s.ctx(), u.id()).attachmentId();
            if (outcomes.getFirst().succeeded()) {
                Finished finished = flow.finish(u.actor(), attachmentId);
                assertThat(finished.files().getFirst().sha256()).as("the declared content won").isEqualTo(Uploads.sha(u.body()));
            } else {
                Expect.failure(() -> flow.finish(u.actor(), attachmentId), ConflictCode.ARTIFACT_HASH_MISMATCH);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ ATA-033

    private static final class Rekeyed {

        private final String attachmentId;
        private final AttachmentStorage first;

        Rekeyed(String attachmentId, AttachmentStorage first) {
            this.attachmentId = attachmentId;
            this.first = first;
        }
    }

    private static Rekeyed attachment(Scenario s, long seed) {
        String actor = s.actorWith("issue");
        String id = Uploads.upload(s.bean(AttachmentFlow.class), actor, Uploads.body(250, seed)).attachmentId();
        return new Rekeyed(id, Uploads.currentStorage(s.ctx(), id));
    }

    @Test
    @DisplayName("ATA-033: two rekeys of the same current file, the first not committed yet: the second waits, then ends with the translated DuplicateKeyException; the current file is the winner's and the audit row is one")
    void ata033_theLoserOfTwoRekeysEndsWithDuplicateKey(Scenario s) throws Exception {
        LedgerAppContext ctx = s.ctx();
        AttachmentMaintenanceService maintenance = s.bean(AttachmentMaintenanceService.class);
        String keyId = s.bean(AttachmentProperties.class).activeKeyId();
        for (int round = 0; round < 3; round++) {
            Rekeyed target = attachment(s, 700L + round);
            StorageKey winnerKey = s.bean(StorageKeys.class).next();
            StorageKey loserKey = s.bean(StorageKeys.class).next();

            HeldTransaction<Boolean> winner = HeldTransaction.start(ctx,
                    () -> maintenance.addRekeyedFile(target.attachmentId, 0, target.first.storageKey(), winnerKey,
                            keyId, TestOwners.OWNER));
            int holder = winner.awaitHeld();
            Future<Boolean> loser = Parallel.start(
                    () -> maintenance.addRekeyedFile(target.attachmentId, 0, target.first.storageKey(), loserKey,
                            keyId, TestOwners.OWNER));
            LockWaits.awaitWaitingFor(ctx.jdbc(), holder);
            assertThat(winner.release()).isTrue();

            assertThatThrownBy(() -> loser.get(60, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(DuplicateKeyException.class);
            AttachmentStorage current = Uploads.currentStorage(ctx, target.attachmentId);
            assertThat(current.rev()).isEqualTo(1);
            assertThat(current.storageKey()).as("the current file is the winner's").isEqualTo(winnerKey);
            assertThat(s.count("SELECT COUNT(*) FROM attachment_storage WHERE attachment_id = ?", target.attachmentId)).isEqualTo(2);
            assertThat(s.count("SELECT COUNT(*) FROM owner_audit WHERE operation = 'ATTACHMENT_REKEY' AND subject = ?",
                    target.attachmentId)).as("the failed rekey left no audit row").isEqualTo(1);
        }
    }

    @Test
    @DisplayName("ATA-033: two rekeys of the same current file at one barrier, many rounds: exactly one adds the row; the other answers false or ends with DuplicateKeyException; never two rows of the same rev")
    void ata033_atOneBarrier(Scenario s) throws Exception {
        LedgerAppContext ctx = s.ctx();
        AttachmentMaintenanceService maintenance = s.bean(AttachmentMaintenanceService.class);
        String keyId = s.bean(AttachmentProperties.class).activeKeyId();
        for (int round = 0; round < ROUNDS; round++) {
            Rekeyed target = attachment(s, 800L + round);
            StorageKey firstKey = s.bean(StorageKeys.class).next();
            StorageKey secondKey = s.bean(StorageKeys.class).next();

            List<Outcome<Boolean>> outcomes = Parallel.<Boolean>all(List.of(
                    () -> maintenance.addRekeyedFile(target.attachmentId, 0, target.first.storageKey(), firstKey,
                            keyId, TestOwners.OWNER),
                    () -> maintenance.addRekeyedFile(target.attachmentId, 0, target.first.storageKey(), secondKey,
                            keyId, TestOwners.OWNER)));

            long winners = outcomes.stream().filter(outcome -> outcome.succeeded() && Boolean.TRUE.equals(outcome.value())).count();
            assertThat(winners).as("exactly one rekey added its row (round %d)", round).isEqualTo(1);
            for (Outcome<Boolean> outcome : outcomes) {
                if (!outcome.succeeded()) {
                    assertThat(outcome.failure()).isInstanceOf(DuplicateKeyException.class);
                } else if (!Boolean.TRUE.equals(outcome.value())) {
                    assertThat(outcome.value()).as("a rekey that found the current copy changed adds nothing").isFalse();
                }
            }
            StorageKey winner = Boolean.TRUE.equals(outcomes.getFirst().value()) ? firstKey : secondKey;
            assertThat(Uploads.currentStorage(ctx, target.attachmentId).storageKey()).isEqualTo(winner);
            assertThat(s.count("SELECT COUNT(*) FROM attachment_storage WHERE attachment_id = ?", target.attachmentId)).isEqualTo(2);
            assertThat(s.count("SELECT COUNT(*) FROM owner_audit WHERE operation = 'ATTACHMENT_REKEY' AND subject = ?",
                    target.attachmentId)).isEqualTo(1);
        }
    }

    // ------------------------------------------------------------------------------------------------ R4-F3

    @Test
    @DisplayName("R4-F3 (a): concurrent artifact_begin with the same (owner, operationKey) and identical files replays idempotently without INTEGRITY_VIOLATION")
    void r4F3_concurrentBeginSameOperationKeyIdempotent(Scenario s) {
        AttachmentFlow flow = s.bean(AttachmentFlow.class);
        for (int round = 0; round < ROUNDS; round++) {
            String actor = s.actorWith("issue");
            String opKey = "r4f3-idem-" + round + "-" + Scenario.uuid();
            byte[] body = Uploads.body(256, 900L + round);
            Begin command = Uploads.begin(opKey, body);
            List<Outcome<Begun>> outcomes = Parallel.all(List.of(
                    () -> flow.begin(actor, command),
                    () -> flow.begin(actor, command),
                    () -> flow.begin(actor, command),
                    () -> flow.begin(actor, command)), 4);
            assertThat(outcomes).allSatisfy(o -> assertThat(o.failure()).isNull());
            Begun expected = outcomes.getFirst().value();
            assertThat(outcomes).allSatisfy(o -> {
                assertThat(o.value().attachmentId()).isEqualTo(expected.attachmentId());
                assertThat(o.value().files()).isEqualTo(expected.files());
            });
            assertThat(s.count("SELECT COUNT(*) FROM upload_staging WHERE owner = ? AND operation_key = ?",
                    actor, opKey)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("R4-F3 (b): concurrent artifact_begin with the same (owner, operationKey) and different files fails ARTIFACT_OPERATION_CONFLICT, never INTEGRITY_VIOLATION")
    void r4F3_concurrentBeginSameOperationKeyDifferentFilesConflicts(Scenario s) {
        AttachmentFlow flow = s.bean(AttachmentFlow.class);
        for (int round = 0; round < ROUNDS; round++) {
            String actor = s.actorWith("issue");
            String opKey = "r4f3-conf-" + round + "-" + Scenario.uuid();
            Begin first = Uploads.begin(opKey, Uploads.body(128, 1000L + round));
            Begin second = Uploads.begin(opKey, Uploads.body(192, 2000L + round));
            List<Outcome<Begun>> outcomes = Parallel.all(List.of(
                    () -> flow.begin(actor, first),
                    () -> flow.begin(actor, second)));
            assertThat(outcomes.stream().filter(Outcome::succeeded)).hasSize(1);
            Outcome<Begun> loser = outcomes.stream().filter(o -> !o.succeeded()).findFirst().orElseThrow();
            assertThat(loser.failure()).isInstanceOfSatisfying(ConflictException.class,
                    ex -> assertThat(ex.code()).isEqualTo(ConflictCode.ARTIFACT_OPERATION_CONFLICT));
            assertThat(s.count("SELECT COUNT(*) FROM upload_staging WHERE owner = ? AND operation_key = ?",
                    actor, opKey)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("R4-F3 (c): concurrent artifact_begin burst enforces max-open-per-owner and staging-budget-bytes quotas without overshooting")
    void r4F3_concurrentBeginRespectsQuotaLimits(Scenario s) {
        AttachmentFlow flow = s.bean(AttachmentFlow.class);
        UploadStagingRepository uploads = s.bean(UploadStagingRepository.class);
        AttachmentProperties properties = s.bean(AttachmentProperties.class);

        // 1) max-open-per-owner serialization under concurrent begins
        String actor = s.actorWith("issue");
        int maxOpen = properties.maxOpenPerOwner();
        int burst = maxOpen + 4;
        List<java.util.concurrent.Callable<Begun>> openCalls = new java.util.ArrayList<>(burst);
        for (int i = 0; i < burst; i++) {
            int idx = i;
            openCalls.add(() -> flow.begin(actor, Uploads.begin("r4f3-open-" + idx + "-" + Scenario.uuid(),
                    Uploads.body(64, 3000L + idx))));
        }
        List<Outcome<Begun>> openOutcomes = Parallel.all(openCalls, burst);
        assertThat(openOutcomes.stream().filter(Outcome::succeeded)).hasSize(maxOpen);
        assertThat(openOutcomes.stream().filter(o -> !o.succeeded())).allSatisfy(o ->
                assertThat(o.failure()).isInstanceOfSatisfying(ConflictException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ConflictCode.ARTIFACT_LIMIT)));
        assertThat(uploads.countOpenBundles(actor)).isEqualTo(maxOpen);

        // 2) staging-budget-bytes serialization across distinct owners (multi-file bundles exceeding half remaining budget)
        long stagingBudget = properties.stagingBudgetBytes().toBytes();
        long maxFile = properties.maxFileBytes().toBytes();
        long existingOpen = uploads.declaredOpenBytes();
        long remaining = stagingBudget - existingOpen;
        long targetBundleBytes = (remaining / 2L) + 1L;
        List<BeginFile> bundleFiles = new java.util.ArrayList<>();
        String sha = "a".repeat(64);
        long left = targetBundleBytes;
        int fileIdx = 0;
        while (left > 0 && fileIdx < UploadService.MAX_FILES) {
            long size = Math.min(maxFile, left);
            bundleFiles.add(new BeginFile("part-" + fileIdx + ".bin", "application/octet-stream", size, sha));
            left -= size;
            fileIdx++;
        }
        assertThat(left).as("bundle fits within MAX_FILES").isZero();
        String ownerA = s.actorWith("issue");
        String ownerB = s.actorWith("issue");
        Begin beginA = new Begin("r4f3-stage-a-" + Scenario.uuid(), List.copyOf(bundleFiles), null);
        Begin beginB = new Begin("r4f3-stage-b-" + Scenario.uuid(), List.copyOf(bundleFiles), null);
        List<Outcome<Begun>> budgetOutcomes = Parallel.all(List.of(
                () -> flow.begin(ownerA, beginA),
                () -> flow.begin(ownerB, beginB)));
        try {
            assertThat(budgetOutcomes.stream().filter(Outcome::succeeded)).hasSize(1);
            Outcome<Begun> budgetLoser = budgetOutcomes.stream().filter(o -> !o.succeeded()).findFirst().orElseThrow();
            assertThat(budgetLoser.failure()).isInstanceOfSatisfying(ConflictException.class,
                    ex -> assertThat(ex.code()).isEqualTo(ConflictCode.ARTIFACT_LIMIT));
            assertThat(uploads.declaredOpenBytes()).isLessThanOrEqualTo(stagingBudget);
        } finally {
            for (Outcome<Begun> outcome : budgetOutcomes) {
                if (outcome.succeeded()) {
                    s.ctx().jdbc().sql("DELETE FROM upload_staging WHERE attachment_id = ?")
                            .param(outcome.value().attachmentId()).update();
                }
            }
        }
    }
}
