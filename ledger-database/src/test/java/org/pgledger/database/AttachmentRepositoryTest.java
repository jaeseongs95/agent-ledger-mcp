package org.pgledger.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.pgledger.database.repository.AttachmentRepository;
import org.pgledger.database.repository.UploadStagingRepository;
import org.pgledger.database.support.DatabaseTest;
import org.pgledger.database.support.LedgerTestContext;
import org.pgledger.domain.model.Attachment;
import org.pgledger.domain.model.UploadStaging;
import org.pgledger.domain.rule.UploadState;
import org.pgledger.domain.value.AuthorId;
import org.pgledger.domain.value.Sha256;
import org.pgledger.domain.value.StorageKey;

/**
 * The two repositories of the attachments on the real database (ATA-011 at the level of the statements, ATA-015, ATA-016):
 * every change of an upload is one conditional statement whose count of rows decides, and an attachment can be inserted and
 * read, never changed.
 */
@DatabaseTest
class AttachmentRepositoryTest {

    private static final AuthorId ALICE = AuthorId.of("claude:alice");
    private static final Instant T0 = Instant.parse("2026-11-15T09:00:00Z");
    private static final Sha256 SHA = Sha256.of("a".repeat(64));

    private static UploadStaging upload(String id, String operationKey, String storageKey, Instant expiresAt) {
        return new UploadStaging(id, ALICE, operationKey, "report.md", "text/markdown", 100, SHA, 0, 0, 0, null,
                StorageKey.of(storageKey), "k1", UploadState.STAGING, expiresAt, T0, T0);
    }

    @Test
    void ata011_aChunkMovesTheRowOnlyFromTheSequenceItExpectedAndMovesTheExpiry(LedgerTestContext ctx) {
        UploadStagingRepository uploads = ctx.bean(UploadStagingRepository.class);
        ctx.tx(() -> {
            uploads.insert(upload("aaaaaaaa-0000-0000-0000-000000000001", "op-1", "1".repeat(32), T0.plusSeconds(600)));
            return null;
        });
        Instant later = T0.plusSeconds(60);
        boolean first = ctx.tx(() -> uploads.advance("aaaaaaaa-0000-0000-0000-000000000001", 0, 60, 32, SHA,
                later.plusSeconds(600), later));
        boolean again = ctx.tx(() -> uploads.advance("aaaaaaaa-0000-0000-0000-000000000001", 0, 60, 32, SHA,
                later.plusSeconds(600), later));
        assertThat(first).isTrue();
        assertThat(again).as("the same chunk number is applied once").isFalse();
        UploadStaging row = uploads.findById("aaaaaaaa-0000-0000-0000-000000000001").orElseThrow();
        assertThat(row.nextSequence()).isEqualTo(1);
        assertThat(row.storedBytes()).isEqualTo(60);
        assertThat(row.receivedBytes()).isEqualTo(32);
        assertThat(row.lastChunkSha256()).isEqualTo(SHA);
        assertThat(row.expiresAt()).as("the expiry is counted from the last chunk").isEqualTo(later.plusSeconds(600));
    }

    @Test
    void ata011_theReaperConditionIsEvaluatedOnTheRowAsItIsNow(LedgerTestContext ctx) {
        UploadStagingRepository uploads = ctx.bean(UploadStagingRepository.class);
        String id = "aaaaaaaa-0000-0000-0000-000000000002";
        ctx.tx(() -> {
            uploads.insert(upload(id, "op-2", "2".repeat(32), T0.plusSeconds(600)));
            return null;
        });
        Instant now = T0.plusSeconds(601);
        assertThat(uploads.findExpired(now, 10)).extracting(UploadStaging::id).contains(id);
        // a chunk arrives after the list was read: the expiry moves, so the delete affects no row
        assertThat(ctx.tx(() -> uploads.advance(id, 0, 28, 1, SHA, now.plusSeconds(600), now))).isTrue();
        assertThat(ctx.tx(() -> uploads.deleteExpired(id, now))).isFalse();
        assertThat(uploads.findById(id)).isPresent();
        // a finish completes it: still not deletable as an expired upload, and the finished row is removed by its own statement
        assertThat(ctx.tx(() -> uploads.complete(id, 1, 56, now))).isTrue();
        assertThat(ctx.tx(() -> uploads.deleteExpired(id, now.plusSeconds(10_000)))).isFalse();
        assertThat(ctx.tx(() -> uploads.deleteCompleted(id))).isTrue();
        assertThat(uploads.findById(id)).isEmpty();
    }

    @Test
    void ata011_anExpiredRowIsDeletedOnceAndLimitsAreCounted(LedgerTestContext ctx) {
        UploadStagingRepository uploads = ctx.bean(UploadStagingRepository.class);
        String id = "aaaaaaaa-0000-0000-0000-000000000003";
        ctx.tx(() -> {
            uploads.insert(upload(id, "op-3", "3".repeat(32), T0.plusSeconds(600)));
            return null;
        });
        assertThat(uploads.openCount(ALICE)).isGreaterThanOrEqualTo(1);
        assertThat(uploads.openDeclaredBytes()).isGreaterThanOrEqualTo(100);
        assertThat(ctx.tx(() -> uploads.deleteExpired(id, T0.plusSeconds(599)))).as("not expired yet").isFalse();
        assertThat(ctx.tx(() -> uploads.deleteExpired(id, T0.plusSeconds(600)))).isTrue();
        assertThat(ctx.tx(() -> uploads.deleteExpired(id, T0.plusSeconds(600)))).as("a second delete finds no row").isFalse();
        assertThat(uploads.findByOperation(ALICE, "op-3")).isEmpty();
    }

    @Test
    void ata016_anAttachmentIsInsertedAndReadAndItsStorageKeyIsKnown(LedgerTestContext ctx) {
        AttachmentRepository attachments = ctx.bean(AttachmentRepository.class);
        UploadStagingRepository uploads = ctx.bean(UploadStagingRepository.class);
        Attachment attachment = new Attachment("bbbbbbbb-0000-0000-0000-000000000001", "report.md", "text/markdown", 100,
                SHA, StorageKey.of("4".repeat(32)), "k1", ALICE, T0);
        ctx.tx(() -> {
            attachments.insert(attachment);
            return null;
        });
        assertThat(attachments.findById(attachment.id())).contains(attachment);
        assertThat(attachments.holdsStorageKey(attachment.storageKey())).isTrue();
        assertThat(uploads.holdsStorageKey(attachment.storageKey())).isFalse();
        assertThat(attachments.keyIdsInUse()).contains("k1");
        assertThat(attachments.isReferenced(attachment.id())).as("no history row points to it").isFalse();
        assertThat(attachments.page("", 10)).extracting(Attachment::id).contains(attachment.id());
    }
}
