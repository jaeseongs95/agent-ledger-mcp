package org.pgledger.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.application.attachment.AttachmentCheck;
import org.pgledger.application.attachment.AttachmentCheck.Report;
import org.pgledger.application.attachment.AttachmentFlow;
import org.pgledger.application.attachment.AttachmentKeys;
import org.pgledger.application.attachment.AttachmentProperties;
import org.pgledger.application.attachment.AttachmentStore;
import org.pgledger.application.attachment.StorageKeys;
import org.pgledger.application.support.AttachmentContext;
import org.pgledger.application.support.LedgerAppContext;
import org.pgledger.application.support.Scenario;
import org.pgledger.application.support.StoredFiles;
import org.pgledger.application.support.TestClock;
import org.pgledger.application.support.Uploads;
import org.pgledger.database.repository.AttachmentFileRepository;
import org.pgledger.database.repository.AttachmentRepository;
import org.pgledger.domain.model.AttachmentFile;
import org.pgledger.domain.value.Sha256;
import org.pgledger.domain.value.StorageKey;
import org.pgledger.testing.TestOwners;
import org.springframework.test.context.TestPropertySource;

/**
 * ATA-008: the check of the folder, the keys and the rows ({@code attachments-check}, the admin page): a file that no row names is moved
 * under {@code orphans/} after {@code orphan-grace} and never deleted, a younger one stays, a quarantined file whose row exists again is
 * moved back (a restore), an attachment without its file or with a damaged file is reported and never deleted, an attachment no history
 * row points to is listed and kept, the quarantine is emptied only by {@code purgeQuarantine} and that is recorded in {@code owner_audit},
 * and the report shows no storage key and no path.
 *
 * <p>The context is a context of its own (the setting below is the default, written out, only to make the cache key differ): the test
 * counts the files of the folder, and no other test may leave one in it.
 */
@AttachmentContext
@TestPropertySource(properties = "ledger.attachments.orphan-grace=24h")
class AttachmentCheckTest {

    private static final Duration GRACE = Duration.ofHours(24);

    private static void touch(Path file, Instant modified) throws IOException {
        Files.setLastModifiedTime(file, FileTime.from(modified));
    }

    /** A file that no row names, last written at {@code modified}: what a crash between the file and its row leaves. */
    private static StorageKey stray(LedgerAppContext ctx, Instant modified) throws IOException {
        StorageKey key = ctx.bean(StorageKeys.class).next();
        byte[] chunk = Uploads.body(40, 5);
        ctx.bean(AttachmentStore.class).append(key, ctx.bean(AttachmentKeys.class).active().id(), 0, 0, chunk,
                Sha256.ofBytes(chunk));
        touch(Uploads.fileOf(ctx, key), modified);
        return key;
    }

    @Test
    @DisplayName("ATA-008: orphans after and before the grace, a restore, a missing, a damaged and an unreferenced attachment, the purge and its audit row")
    void ata008_theCheck(Scenario s) throws IOException {
        LedgerAppContext ctx = s.ctx();
        String actor = s.actorWith("defect");
        AttachmentFlow flow = s.bean(AttachmentFlow.class);
        AttachmentCheck check = s.bean(AttachmentCheck.class);
        AttachmentStore store = s.bean(AttachmentStore.class);
        AttachmentProperties properties = s.bean(AttachmentProperties.class);
        Instant now = TestClock.START;
        assertThat(properties.orphanGrace()).isEqualTo(GRACE);
        store.prepare();

        byte[] okBody = Uploads.body(300, 1);
        byte[] missingBody = Uploads.body(300, 2);
        byte[] damagedBody = Uploads.body(300, 3);
        byte[] restoredBody = Uploads.body(300, 4);
        String ok = Uploads.upload(flow, actor, okBody, 100).attachmentId();
        String missing = Uploads.upload(flow, actor, missingBody, 100).attachmentId();
        String damaged = Uploads.upload(flow, actor, damagedBody, 100).attachmentId();
        String restored = Uploads.upload(flow, actor, restoredBody, 100).attachmentId();
        // the damaged one: a byte of its first segment is changed; the missing one: its file is deleted
        Path damagedFile = Uploads.fileOf(ctx, Uploads.currentFile(ctx, damaged).storageKey());
        StoredFiles.replace(damagedFile, StoredFiles.flip(StoredFiles.readAll(damagedFile), 20));
        StoredFiles.delete(Uploads.fileOf(ctx, Uploads.currentFile(ctx, missing).storageKey()));
        // the restored one: the database was restored to a state before its row, so its file has no row (and is old)
        AttachmentFile restoredRow = Uploads.currentFile(ctx, restored);
        assertThat(ctx.jdbc().sql("DELETE FROM attachment_file WHERE attachment_id = ?").param(restored).update()).isEqualTo(1);
        touch(Uploads.fileOf(ctx, restoredRow.storageKey()), now.minus(GRACE).minusSeconds(3600));
        StorageKey oldStray = stray(ctx, now.minus(GRACE).minusSeconds(3600));
        StorageKey youngStray = stray(ctx, now.minus(GRACE).plusSeconds(3600));
        List<String> keys = List.of(oldStray.hex(), youngStray.hex(), restoredRow.storageKey().hex(),
                properties.directory().toString());

        // a read-only run reports what a run would do and moves nothing
        Report preview = check.run(false);
        assertThat(preview.orphansQuarantined()).as("the old stray and the old file of the restored one").isEqualTo(2);
        assertThat(preview.orphansWaiting()).as("the young stray").isEqualTo(1);
        assertThat(preview.replacedQuarantined()).isZero();
        assertThat(preview.restored()).isZero();
        assertThat(preview.quarantined()).isZero();
        assertThat(preview.missing()).containsExactlyInAnyOrder(missing, restored);
        assertThat(preview.damaged()).containsExactly(damaged);
        assertThat(preview.unreferenced()).as("no history row points to any of them").containsExactlyInAnyOrder(ok, missing,
                damaged, restored);
        assertThat(preview.attachments()).isEqualTo(4);
        assertThat(Uploads.fileOf(ctx, oldStray)).exists();

        // a run that moves: the old files go under orphans/, the young one stays, nothing is deleted
        Report moved = check.run(true);
        assertThat(moved.orphansQuarantined()).isEqualTo(2);
        assertThat(moved.orphansWaiting()).isEqualTo(1);
        assertThat(moved.quarantined()).isEqualTo(2);
        assertThat(Uploads.fileOf(ctx, oldStray)).doesNotExist();
        assertThat(Uploads.quarantinedFileOf(ctx, oldStray)).exists();
        assertThat(Uploads.quarantinedFileOf(ctx, restoredRow.storageKey())).exists();
        assertThat(Uploads.fileOf(ctx, youngStray)).as("younger than the grace: stays where it is").exists();
        assertThat(damagedFile).as("a damaged file is reported, never moved or deleted").exists();
        for (String id : List.of(ok, missing, damaged, restored)) {
            assertThat(ctx.bean(AttachmentRepository.class).findById(id)).as("the row of %s is kept", id).isPresent();
        }
        Report again = check.run(false);
        assertThat(again.orphansQuarantined()).isZero();
        assertThat(again.orphansWaiting()).isEqualTo(1);
        assertThat(again.quarantined()).isEqualTo(2);

        // a restore: a row with the storage key of the quarantined file exists again, so the file is moved back
        ctx.tx(() -> {
            ctx.bean(AttachmentFileRepository.class).insert(restoredRow);
            return null;
        });
        Report restoring = check.run(true);
        assertThat(restoring.restored()).isEqualTo(1);
        assertThat(restoring.quarantined()).as("only the old stray is left in the quarantine").isEqualTo(1);
        assertThat(restoring.missing()).containsExactly(missing);
        assertThat(restoring.damaged()).containsExactly(damaged);
        assertThat(Uploads.fileOf(ctx, restoredRow.storageKey())).exists();
        assertThat(Uploads.quarantinedFileOf(ctx, restoredRow.storageKey())).doesNotExist();
        assertThat(Uploads.readAll(flow, actor, restored, 1000)).as("the restored attachment reads again").isEqualTo(restoredBody);

        // the purge is the only way the quarantine is emptied, and it is audited with a count and no storage key
        assertThat(check.purgeQuarantine(TestOwners.OWNER)).isEqualTo(1);
        assertThat(store.listQuarantined()).isEmpty();
        assertThat(Uploads.quarantinedFileOf(ctx, oldStray)).doesNotExist();
        assertThat(Uploads.fileOf(ctx, youngStray)).exists();
        List<Map<String, Object>> audit = ctx.jdbc()
                .sql("SELECT actor, subject, detail FROM owner_audit WHERE operation = 'ORPHANS_PURGED'").query().listOfRows();
        assertThat(audit).hasSize(1);
        assertThat(audit.getFirst().get("actor")).isEqualTo(TestOwners.OWNER.value());
        assertThat(audit.getFirst().get("detail")).isEqualTo("1 files");
        assertThat(audit.getFirst().toString()).doesNotContain(oldStray.hex());

        // no report shows a storage key or a path
        for (Report report : List.of(preview, moved, again, restoring)) {
            for (String secret : keys) {
                assertThat(report.toString()).doesNotContain(secret);
            }
        }
    }
}
