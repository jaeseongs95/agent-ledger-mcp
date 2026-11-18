package org.pgledger.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.application.attachment.AttachmentFlow;
import org.pgledger.application.attachment.AttachmentMaintenanceService;
import org.pgledger.application.attachment.AttachmentReaper;
import org.pgledger.application.attachment.AttachmentStore;
import org.pgledger.application.attachment.UploadCommands.Begun;
import org.pgledger.application.attachment.UploadCommands.Finished;
import org.pgledger.application.attachment.UploadCommands.Received;
import org.pgledger.application.attachment.UploadCommands.Status;
import org.pgledger.application.attachment.UploadService;
import org.pgledger.application.attachment.UploadService.ChunkPlan;
import org.pgledger.application.attachment.UploadService.ClosedFile;
import org.pgledger.application.attachment.UploadService.FinishPlan;
import org.pgledger.application.support.AttachmentContext;
import org.pgledger.application.support.HeldTransaction;
import org.pgledger.application.support.LedgerAppContext;
import org.pgledger.testing.database.LockWaits;
import org.pgledger.application.support.Parallel;
import org.pgledger.application.support.Scenario;
import org.pgledger.application.support.TestClock;
import org.pgledger.application.support.Uploads;
import org.pgledger.database.repository.AttachmentRepository;
import org.pgledger.database.repository.UploadStagingRepository;
import org.pgledger.domain.error.LookupCode;
import org.pgledger.domain.error.LookupException;
import org.pgledger.domain.model.UploadStaging;
import org.pgledger.domain.rule.UploadState;
import org.pgledger.domain.value.Sha256;
import org.pgledger.domain.value.StorageKey;
import org.springframework.test.context.TestPropertySource;

/**
 * ATA-007 (the expiry is counted from the last chunk; the reaper removes the row and then the file) and ATA-011 (the reaper's conditional
 * DELETE against a chunk and against a finish, two connections of the test PostgreSQL, many rounds). The context is a context of its
 * own (the setting below is the default, written out, only to make the cache key differ): the reaper removes every expired upload of the
 * context, and no other test may have one.
 *
 * <p>ATA-011 is made deterministic by the database: one side is a transaction on its own connection that does its statement and stays
 * open ({@link HeldTransaction}), the other side is started and the test waits until PostgreSQL shows its statement waiting for the lock
 * of the first ({@link LockWaits}, the lock wait query), then lets the first commit. Both orders are run, for the chunk and for the
 * finish. The file work of the flow ({@code AttachmentFlow}) is done by the same components in the same order, written out step by step
 * so that the service steps can be held; the invariant is asserted by the effect: no row without its file, no attachment without its
 * file, and no upload that completed is gone.
 */
@AttachmentContext
@TestPropertySource(properties = "ledger.attachments.reaper-interval=1m")
class AttachmentReaperTest {

    private static final int ROUNDS = 10;
    private static final Duration TTL = Duration.ofMinutes(10);

    @BeforeEach
    void noOpenUploads(LedgerAppContext ctx) {
        drain(ctx);
    }

    /** Removes every upload and finished row of the context (the clock is moved far ahead for the reaper and then back). */
    private static void drain(LedgerAppContext ctx) {
        ctx.clock().set(TestClock.START.plus(Duration.ofDays(30)));
        AttachmentReaper reaper = ctx.bean(AttachmentReaper.class);
        int removed;
        do {
            removed = reaper.reap();
        } while (removed > 0);
        ctx.clock().set(TestClock.START);
    }

    // ------------------------------------------------------------------------------------------------ ATA-007

    @Test
    @DisplayName("ATA-007: the expiry is counted from the last chunk; before it the reaper leaves the row and the file, after it one conditional DELETE removes the row and then the file")
    void ata007_theExpiryIsCountedFromTheLastChunk(Scenario s) {
        LedgerAppContext ctx = s.ctx();
        String actor = s.actorWith("issue");
        AttachmentFlow flow = s.bean(AttachmentFlow.class);
        AttachmentReaper reaper = s.bean(AttachmentReaper.class);
        UploadStagingRepository uploads = s.bean(UploadStagingRepository.class);
        Instant begin = TestClock.START;
        byte[] body = Uploads.body(400, 1);
        List<byte[]> parts = Uploads.parts(body, 200);

        Begun begun = flow.begin(actor, Uploads.begin(body));
        String uploadId = begun.files().getFirst().uploadId();
        assertThat(begun.expiresAt()).isEqualTo(begin.plus(TTL));
        ctx.clock().set(begin.plus(Duration.ofMinutes(5)));
        Uploads.send(flow, actor, uploadId, 0, parts.get(0));

        UploadStaging row = Uploads.row(ctx, uploadId);
        assertThat(row.expiresAt()).as("counted again from the last chunk, not from the begin").isEqualTo(begin.plus(Duration.ofMinutes(15)));
        assertThat(Uploads.fileOf(ctx, row.storageKey())).exists();

        ctx.clock().set(begin.plus(Duration.ofMinutes(11)));
        assertThat(reaper.reap()).as("past the TTL of the begin, before the TTL of the last chunk").isZero();
        assertThat(uploads.findById(uploadId)).isPresent();
        assertThat(Uploads.fileOf(ctx, row.storageKey())).exists();

        ctx.clock().set(begin.plus(Duration.ofMinutes(16)));
        assertThat(reaper.reap()).isEqualTo(1);
        assertThat(uploads.findById(uploadId)).as("the row is removed").isEmpty();
        assertThat(Uploads.fileOf(ctx, row.storageKey())).as("and then its file").doesNotExist();
    }

    @Test
    @DisplayName("ATA-007: the row of a finished upload is removed by the reaper and the file stays, because it belongs to the attachment")
    void ata007_aFinishedUploadKeepsItsFile(Scenario s) {
        LedgerAppContext ctx = s.ctx();
        String actor = s.actorWith("issue");
        AttachmentFlow flow = s.bean(AttachmentFlow.class);
        byte[] body = Uploads.body(300, 2);
        Begun begun = Uploads.sendAll(flow, actor, body, 100);
        String uploadId = begun.files().getFirst().uploadId();
        Finished finished = flow.finish(actor, begun.attachmentId());
        StorageKey storageKey = Uploads.currentStorage(ctx, finished.attachmentId()).storageKey();
        assertThat(Uploads.row(ctx, uploadId).state()).isEqualTo(UploadState.COMPLETED);

        s.bean(AttachmentReaper.class).reap();

        assertThat(s.bean(UploadStagingRepository.class).findById(uploadId)).isEmpty();
        assertThat(s.bean(AttachmentRepository.class).findById(finished.attachmentId())).isPresent();
        assertThat(Uploads.fileOf(ctx, storageKey)).exists();
        assertThat(Uploads.readAll(flow, actor, finished.attachmentId(), 64)).isEqualTo(body);
        Status status = flow.attachmentStatus(actor, finished.attachmentId());
        assertThat(status.state()).isEqualTo("COMPLETED");
        assertThat(status.expiresAt()).as("an attachment has no expiry").isNull();
    }

    // ------------------------------------------------------------------------------------------------ ATA-011

    /** An upload of 400 bytes in two chunks of 200 whose first chunk is recorded. */
    private record Prepared(String actor, String id, List<byte[]> parts, UploadStaging row) {
    }

    private Prepared prepared(Scenario s, int round, int chunksSent) {
        String actor = s.actorWith("issue");
        AttachmentFlow flow = s.bean(AttachmentFlow.class);
        byte[] body = Uploads.body(400, 1000L + round);
        List<byte[]> parts = Uploads.parts(body, 200);
        String id = flow.begin(actor, Uploads.begin(body)).files().getFirst().uploadId();
        for (int sequence = 0; sequence < chunksSent; sequence++) {
            Uploads.send(flow, actor, id, sequence, parts.get(sequence));
        }
        return new Prepared(actor, id, parts, Uploads.row(s.ctx(), id));
    }

    /** What a chunk does before its service step: the plan of the service and the append to the file (step 1 to 3 of design 4.4). */
    private long appended(Scenario s, Prepared p, int sequence) {
        byte[] part = p.parts().get(sequence);
        Sha256 sha = Sha256.ofBytes(part);
        UploadService.ChunkPlan plan = s.bean(UploadService.class).planChunk(p.actor(), p.id(), sequence, part.length, sha);
        UploadStaging row = ((ChunkPlan.Append) plan).row();
        return s.bean(AttachmentStore.class).append(row.storageKey(), row.keyId(), row.storedBytes(), sequence, part, sha);
    }

    /** No row of an open upload without its file, no attachment without a file, no file without storage, no finished upload without its attachment. */
    private static void assertConsistent(LedgerAppContext ctx) {
        List<Map<String, Object>> open = ctx.jdbc().sql("SELECT id, storage_key FROM upload_staging WHERE state = 'STAGING'")
                .query().listOfRows();
        for (Map<String, Object> row : open) {
            assertThat(Uploads.fileOf(ctx, StorageKey.of((String) row.get("storage_key")))).as("the file of the open upload %s", row.get("id"))
                    .exists();
        }
        List<Map<String, Object>> storages = ctx.jdbc().sql("SELECT attachment_id, storage_key FROM attachment_storage").query().listOfRows();
        for (Map<String, Object> row : storages) {
            assertThat(Uploads.fileOf(ctx, StorageKey.of((String) row.get("storage_key")))).as("the file of the attachment %s", row.get("attachment_id"))
                    .exists();
        }
        assertThat(ctx.jdbc().sql("SELECT COUNT(*) FROM attachment a WHERE NOT EXISTS (SELECT 1 FROM attachment_file f "
                + "WHERE f.attachment_id = a.id)").query(Long.class).single()).as("attachments without a file row").isZero();
        assertThat(ctx.jdbc().sql("SELECT COUNT(*) FROM attachment_file f WHERE NOT EXISTS (SELECT 1 FROM attachment_storage s "
                + "WHERE s.attachment_id = f.attachment_id AND s.seq = f.seq)").query(Long.class).single()).as("files without a storage row").isZero();
        assertThat(ctx.jdbc().sql("SELECT COUNT(*) FROM upload_staging u WHERE u.state = 'COMPLETED' AND NOT EXISTS "
                + "(SELECT 1 FROM attachment a WHERE a.id = u.attachment_id)").query(Long.class).single())
                .as("completed uploads without their attachment").isZero();
    }

    private static <T> T value(Future<T> future) throws Exception {
        return future.get(60, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("ATA-011 (a): the reaper's DELETE is first and a chunk second: the chunk waits for the delete, the row is gone, then the file is gone, and the chunk answers that the upload does not exist")
    void ata011_theReaperDeletesBeforeTheChunk(Scenario s) throws Exception {
        LedgerAppContext ctx = s.ctx();
        UploadService service = s.bean(UploadService.class);
        AttachmentMaintenanceService maintenance = s.bean(AttachmentMaintenanceService.class);
        for (int round = 0; round < ROUNDS; round++) {
            Prepared p = prepared(s, round, 1);
            long stored = appended(s, p, 1);
            Instant expiry = p.row().expiresAt();
            ctx.clock().set(expiry);
            Sha256 sha = Sha256.ofBytes(p.parts().get(1));

            HeldTransaction<Boolean> reaping = HeldTransaction.start(ctx, () -> maintenance.removeExpired(p.id(), expiry));
            int holder = reaping.awaitHeld();
            Future<Received> chunk = Parallel.start(() -> service.recordChunk(p.actor(), p.row(), stored, 200, sha));
            LockWaits.awaitWaitingFor(ctx.jdbc(), holder);
            assertThat(reaping.release()).as("one row deleted: the reaper may remove the file").isTrue();
            s.bean(AttachmentStore.class).remove(p.row().storageKey());

            assertThat(failureOf(chunk)).isInstanceOfSatisfying(LookupException.class,
                    lost -> assertThat(lost.code()).isEqualTo(LookupCode.ARTIFACT_NOT_FOUND));
            assertThat(s.bean(UploadStagingRepository.class).findById(p.id())).as("round %d", round).isEmpty();
            assertThat(Uploads.fileOf(ctx, p.row().storageKey())).doesNotExist();
            assertConsistent(ctx);
            drain(ctx);
        }
    }

    @Test
    @DisplayName("ATA-011 (b): a chunk is first and the reaper's DELETE second: the delete waits for the chunk, sees the moved expiry and affects no row; the row and the file stay")
    void ata011_theChunkRecordsBeforeTheReaper(Scenario s) throws Exception {
        LedgerAppContext ctx = s.ctx();
        UploadService service = s.bean(UploadService.class);
        AttachmentReaper reaper = s.bean(AttachmentReaper.class);
        for (int round = 0; round < ROUNDS; round++) {
            Prepared p = prepared(s, round, 1);
            long stored = appended(s, p, 1);
            ctx.clock().set(p.row().expiresAt());
            Sha256 sha = Sha256.ofBytes(p.parts().get(1));

            HeldTransaction<Received> chunk = HeldTransaction.start(ctx,
                    () -> service.recordChunk(p.actor(), p.row(), stored, 200, sha));
            int holder = chunk.awaitHeld();
            Future<Integer> reaping = Parallel.start(reaper::reap);
            LockWaits.awaitWaitingFor(ctx.jdbc(), holder);
            assertThat(chunk.release()).isEqualTo(new Received(1, 400));

            assertThat(value(reaping)).as("the expiry moved: the delete affected no row (round %d)", round).isZero();
            UploadStaging row = Uploads.row(ctx, p.id());
            assertThat(row.nextSequence()).isEqualTo(2);
            assertThat(row.state()).isEqualTo(UploadState.STAGING);
            assertThat(Files.size(Uploads.fileOf(ctx, row.storageKey()))).as("the file is what the row says").isEqualTo(row.storedBytes());
            assertConsistent(ctx);
            drain(ctx);
        }
    }

    @Test
    @DisplayName("ATA-011 (c): a finish is first and the reaper's DELETE second: the delete waits, sees the upload completed and affects no row; the attachment exists with its file and reads")
    void ata011_theFinishCompletesBeforeTheReaper(Scenario s) throws Exception {
        LedgerAppContext ctx = s.ctx();
        UploadService service = s.bean(UploadService.class);
        AttachmentReaper reaper = s.bean(AttachmentReaper.class);
        AttachmentFlow flow = s.bean(AttachmentFlow.class);
        for (int round = 0; round < ROUNDS; round++) {
            Prepared p = prepared(s, round, 2);
            String attachmentId = p.row().attachmentId();
            FinishPlan.Close close = (FinishPlan.Close) service.planFinish(p.actor(), attachmentId);
            UploadStaging row = close.rows().getFirst();
            long length = s.bean(AttachmentStore.class).finish(row.storageKey(), row.keyId(), row.storedBytes(),
                    row.nextSequence(), row.declaredSize(), row.declaredSha256());
            ctx.clock().set(Uploads.row(ctx, p.id()).expiresAt());

            HeldTransaction<Finished> finish = HeldTransaction.start(ctx,
                    () -> service.completeUpload(p.actor(), attachmentId, List.of(new ClosedFile(row.id(), length))));
            int holder = finish.awaitHeld();
            Future<Integer> reaping = Parallel.start(reaper::reap);
            LockWaits.awaitWaitingFor(ctx.jdbc(), holder);
            Finished finished = finish.release();

            assertThat(value(reaping)).as("no expired upload was removed (round %d)", round).isZero();
            assertThat(finished.attachmentId()).isEqualTo(attachmentId);
            assertThat(s.bean(AttachmentRepository.class).findById(attachmentId)).as("a completed upload is not gone").isPresent();
            assertThat(Uploads.fileOf(ctx, row.storageKey())).exists();
            assertThat(Uploads.readAll(flow, p.actor(), attachmentId, 150)).hasSize(400);
            assertConsistent(ctx);
            drain(ctx);
        }
    }

    @Test
    @DisplayName("ATA-011 (d): the reaper's DELETE is first and a finish second: the finish waits, finds the row gone and answers that the upload does not exist; no attachment, no row, no file")
    void ata011_theReaperDeletesBeforeTheFinish(Scenario s) throws Exception {
        LedgerAppContext ctx = s.ctx();
        UploadService service = s.bean(UploadService.class);
        AttachmentMaintenanceService maintenance = s.bean(AttachmentMaintenanceService.class);
        for (int round = 0; round < ROUNDS; round++) {
            Prepared p = prepared(s, round, 2);
            String attachmentId = p.row().attachmentId();
            FinishPlan.Close close = (FinishPlan.Close) service.planFinish(p.actor(), attachmentId);
            UploadStaging row = close.rows().getFirst();
            long length = s.bean(AttachmentStore.class).finish(row.storageKey(), row.keyId(), row.storedBytes(),
                    row.nextSequence(), row.declaredSize(), row.declaredSha256());
            Instant expiry = Uploads.row(ctx, p.id()).expiresAt();
            ctx.clock().set(expiry);

            HeldTransaction<Boolean> reaping = HeldTransaction.start(ctx, () -> maintenance.removeExpired(p.id(), expiry));
            int holder = reaping.awaitHeld();
            Future<Finished> finish = Parallel.start(
                    () -> service.completeUpload(p.actor(), attachmentId, List.of(new ClosedFile(row.id(), length))));
            LockWaits.awaitWaitingFor(ctx.jdbc(), holder);
            assertThat(reaping.release()).isTrue();
            s.bean(AttachmentStore.class).remove(row.storageKey());

            assertThat(failureOf(finish)).isInstanceOfSatisfying(LookupException.class,
                    lost -> assertThat(lost.code()).isEqualTo(LookupCode.ARTIFACT_NOT_FOUND));
            assertThat(s.bean(AttachmentRepository.class).findById(attachmentId)).as("round %d", round).isEmpty();
            assertThat(s.bean(UploadStagingRepository.class).findById(p.id())).isEmpty();
            assertThat(Uploads.fileOf(ctx, row.storageKey())).doesNotExist();
            assertConsistent(ctx);
            drain(ctx);
        }
    }

    /** The failure a future ended with (the cause of the execution failure); the future is expected to fail. */
    private static Throwable failureOf(Future<?> future) throws InterruptedException {
        try {
            future.get(60, TimeUnit.SECONDS);
        } catch (ExecutionException failure) {
            return failure.getCause();
        } catch (TimeoutException timeout) {
            throw new IllegalStateException("the future did not end", timeout);
        }
        throw new IllegalStateException("the future was expected to fail");
    }
}
