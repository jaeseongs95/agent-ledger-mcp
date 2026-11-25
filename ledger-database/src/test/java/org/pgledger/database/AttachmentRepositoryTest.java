// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.pgledger.database.repository.AttachmentFileRepository;
import org.pgledger.database.repository.AttachmentRepository;
import org.pgledger.database.repository.AttachmentStorageRepository;
import org.pgledger.database.repository.UploadStagingRepository;
import org.pgledger.database.support.DatabaseTest;
import org.pgledger.database.support.LedgerTestContext;
import org.pgledger.domain.model.Attachment;
import org.pgledger.domain.model.AttachmentFile;
import org.pgledger.domain.model.AttachmentStorage;
import org.pgledger.domain.model.UploadStaging;
import org.pgledger.domain.rule.UploadState;
import org.pgledger.domain.value.AuthorId;
import org.pgledger.domain.value.Sha256;
import org.pgledger.domain.value.StorageKey;
import org.springframework.dao.DuplicateKeyException;

/**
 * Schema and repository checks for the four attachment bundle tables ({@code upload_staging}, {@code attachment},
 * {@code attachment_file}, {@code attachment_storage}): bundle uniqueness, conditional advance, bundle expiry extension,
 * highest-rev lookup, replacement detection, and ATA-033 primary key violation on {@code (attachment_id, seq, rev)}.
 */
@DatabaseTest
class AttachmentRepositoryTest {

    private static final AuthorId AUTHOR = AuthorId.of("claude:repo-test");
    private static final Instant T0 = Instant.parse("2026-11-05T10:00:00Z");
    private static final Sha256 SHA_A = Sha256.of("a".repeat(64));
    private static final Sha256 SHA_B = Sha256.of("b".repeat(64));

    private static UploadStaging staging(String id, String attachmentId, int seq, String opKey, StorageKey storageKey) {
        return new UploadStaging(id, attachmentId, seq, AUTHOR, opKey, "file-" + seq + ".txt", "text/plain",
                100L + seq, SHA_A, 0L, 0L, 0L, null, storageKey, "key-a", UploadState.STAGING,
                T0.plusSeconds(600), T0, T0);
    }

    @Test
    void bundleStagingTracksPerFileRowsExtendsBundleExpiryAndEnforcesOperationKeyUniqueness(LedgerTestContext ctx) {
        UploadStagingRepository uploads = ctx.bean(UploadStagingRepository.class);
        String bundleId = "aaaaaaaa-0000-0000-0000-000000000001";
        String up0 = "aaaaaaaa-0000-0000-0000-000000000010";
        String up1 = "aaaaaaaa-0000-0000-0000-000000000011";
        UploadStaging row0 = staging(up0, bundleId, 0, "op-bundle", StorageKey.of("1".repeat(32)));
        UploadStaging row1 = staging(up1, bundleId, 1, "op-bundle", StorageKey.of("2".repeat(32)));

        ctx.tx(() -> {
            uploads.insert(row0);
            uploads.insert(row1);
            return null;
        });

        assertThat(uploads.findByOwnerAndOperationKey(AUTHOR.value(), "op-bundle")).containsExactly(row0, row1);
        assertThat(uploads.findByAttachmentId(bundleId)).containsExactly(row0, row1);
        assertThat(uploads.countOpenBundles(AUTHOR.value())).isEqualTo(1L);
        assertThat(uploads.declaredOpenBytes()).isEqualTo(201L);

        Instant extended = T0.plusSeconds(700);
        Instant updated = T0.plusSeconds(100);
        assertThat(ctx.tx(() -> uploads.advance(up0, 0L, 128L, 100L, SHA_B, extended, updated))).isTrue();
        ctx.tx(() -> {
            uploads.extendBundle(bundleId, extended, updated);
            return null;
        });

        assertThat(uploads.findById(up1)).hasValueSatisfying(r -> assertThat(r.expiresAt()).isEqualTo(extended));
        assertThat(uploads.advance(up0, 0L, 128L, 100L, SHA_B, extended, updated))
                .as("stale next_sequence fails conditional advance").isFalse();

        // Duplicate (owner, operation_key, seq) violates uniqueness
        UploadStaging dup = staging("aaaaaaaa-0000-0000-0000-000000000012", "aaaaaaaa-0000-0000-0000-000000000099",
                0, "op-bundle", StorageKey.of("3".repeat(32)));
        assertThatThrownBy(() -> ctx.tx(() -> {
            uploads.insert(dup);
            return null;
        })).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void storageCopiesTrackCurrentRevisionAndReplacementGrace(LedgerTestContext ctx) {
        AttachmentRepository attachments = ctx.bean(AttachmentRepository.class);
        AttachmentFileRepository files = ctx.bean(AttachmentFileRepository.class);
        AttachmentStorageRepository storages = ctx.bean(AttachmentStorageRepository.class);
        UploadStagingRepository uploads = ctx.bean(UploadStagingRepository.class);
        String attachmentId = "bbbbbbbb-0000-0000-0000-000000000002";
        AttachmentStorage first = new AttachmentStorage(attachmentId, 0, 0, StorageKey.of("4".repeat(32)), "key-a", T0);
        AttachmentStorage second = new AttachmentStorage(attachmentId, 0, 1, StorageKey.of("5".repeat(32)), "key-b",
                T0.plusSeconds(30));

        ctx.tx(() -> {
            attachments.insert(new Attachment(attachmentId, AUTHOR, T0));
            files.insert(new AttachmentFile(attachmentId, 0, "report.md", "text/markdown", 120L, SHA_A));
            files.insert(new AttachmentFile(attachmentId, 1, "log.txt", "text/plain", 80L, SHA_B));
            storages.insert(first);
            storages.insert(new AttachmentStorage(attachmentId, 1, 0, StorageKey.of("6".repeat(32)), "key-a", T0));
            return null;
        });

        assertThat(files.ofAttachment(attachmentId)).hasSize(2);
        assertThat(files.totalBytes()).isEqualTo(200L);
        assertThat(storages.current(attachmentId, 0)).contains(first);
        assertThat(storages.currentFilesOfKey("key-a")).isEqualTo(2L);

        ctx.tx(() -> {
            storages.insert(second);
            return null;
        });

        assertThat(storages.current(attachmentId, 0)).contains(second);
        assertThat(storages.highestRev(attachmentId, 0)).isEqualTo(1);
        assertThat(storages.isReplaced(first.storageKey())).isTrue();
        assertThat(storages.isReplaced(second.storageKey())).isFalse();
        assertThat(storages.holdsStorageKey(first.storageKey())).isTrue();
        assertThat(uploads.holdsStorageKey(first.storageKey())).isFalse();
        assertThat(storages.currentKeyIds()).containsExactlyInAnyOrder("key-a", "key-b");
        assertThat(storages.replacedAt(first.storageKey())).contains(second.createdAt());
        assertThat(storages.replacedAt(second.storageKey())).isEmpty();
    }

    /** ATA-033: two rekeys that read the same current copy insert the same (attachment_id, seq, rev); the second fails. */
    @Test
    void ata033_theSecondRekeyOfTheSameFileEndsWithThePrimaryKeyViolation(LedgerTestContext ctx) {
        AttachmentStorageRepository storages = ctx.bean(AttachmentStorageRepository.class);
        String attachmentId = "bbbbbbbb-0000-0000-0000-000000000003";
        ctx.tx(() -> {
            storages.insert(new AttachmentStorage(attachmentId, 0, 0, StorageKey.of("7".repeat(32)), "key-a", T0));
            storages.insert(new AttachmentStorage(attachmentId, 0, 1, StorageKey.of("8".repeat(32)), "key-b",
                    T0.plusSeconds(1)));
            return null;
        });
        AttachmentStorage loser = new AttachmentStorage(attachmentId, 0, 1, StorageKey.of("9".repeat(32)), "key-c",
                T0.plusSeconds(2));
        assertThatThrownBy(() -> ctx.tx(() -> {
            storages.insert(loser);
            return null;
        })).isInstanceOf(DuplicateKeyException.class);
        assertThat(storages.current(attachmentId, 0)).hasValueSatisfying(
                current -> assertThat(current.storageKey()).isEqualTo(StorageKey.of("8".repeat(32))));
    }
}
