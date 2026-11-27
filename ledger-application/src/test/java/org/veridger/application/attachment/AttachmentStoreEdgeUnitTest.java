// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.veridger.application.attachment.AttachmentStore.Page;
import org.veridger.application.support.StoreFixture;
import org.veridger.application.support.StoreFixture.Written;
import org.veridger.domain.error.ConflictCode;
import org.veridger.domain.error.ConflictException;
import org.veridger.domain.error.InfrastructureCode;
import org.veridger.domain.error.InfrastructureException;
import org.veridger.domain.error.ValidationCode;
import org.veridger.domain.error.ValidationException;
import org.veridger.domain.value.Sha256;
import org.veridger.domain.value.StorageKey;
import org.veridger.files.FileModes;

/**
 * Exhaustive unit tests for {@link AttachmentStore} edge, validation, corruption, tail-recovery, rekey, and quarantine branches.
 */
class AttachmentStoreEdgeUnitTest {

    private StoreFixture fixture;
    private AttachmentStore store;

    @BeforeEach
    void setUp() {
        fixture = StoreFixture.create();
        store = fixture.store();
    }

    @AfterEach
    void tearDown() {
        fixture.close();
    }

    @Test
    @DisplayName("AST-01 Page value semantics and encryptedBytesUpperBound overflow boundaries")
    void pageSemanticsAndUpperBoundMath() {
        Page a = new Page(new byte[] {1, 2, 3}, 10L, 100L);
        Page b = new Page(new byte[] {1, 2, 3}, 10L, 100L);
        Page diffOffset = new Page(new byte[] {1, 2, 3}, 11L, 100L);
        Page diffTotal = new Page(new byte[] {1, 2, 3}, 10L, 101L);
        Page diffData = new Page(new byte[] {1, 2, 4}, 10L, 100L);

        boolean reflexive = a.equals(a);
        assertThat(reflexive).isTrue();
        assertThat(a)
                .isEqualTo(b)
                .hasSameHashCodeAs(b)
                .isNotEqualTo(diffOffset)
                .isNotEqualTo(diffTotal)
                .isNotEqualTo(diffData)
                .isNotEqualTo("not-a-page");
        assertThat(a.toString()).contains("offset=10").contains("totalSize=100");

        assertThat(AttachmentStore.encryptedBytesUpperBound(0L, 1)).isEqualTo(SegmentCipher.OVERHEAD);
        assertThat(AttachmentStore.encryptedBytesUpperBound(-5L, 0)).isEqualTo(SegmentCipher.OVERHEAD);
        assertThat(AttachmentStore.encryptedBytesUpperBound(4096L, 1)).isGreaterThan(4096L);
        assertThat(AttachmentStore.encryptedBytesUpperBound(Long.MAX_VALUE, 1)).isEqualTo(Long.MAX_VALUE);
        assertThat(AttachmentStore.encryptedBytesUpperBound(Long.MAX_VALUE - 5L, 1)).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    @DisplayName("AST-02 append validates data, handles missing/truncated/closed files, tail mismatches, and overlapping locks")
    void appendEdgeAndFailureBranches() throws IOException {
        StorageKey key = fixture.storageKeys().next();
        byte[] chunk = "chunk-data".getBytes(StandardCharsets.UTF_8);
        Sha256 chunkSha = Sha256.ofBytes(chunk);

        // Invalid plain payloads
        assertThatThrownBy(() -> store.append(key, fixture.keyId(), 0L, 0L, null, chunkSha))
                .isInstanceOfSatisfying(ValidationException.class,
                        e -> assertThat(e.code()).isEqualTo(ValidationCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> store.append(key, fixture.keyId(), 0L, 0L, new byte[0], chunkSha))
                .isInstanceOfSatisfying(ValidationException.class,
                        e -> assertThat(e.code()).isEqualTo(ValidationCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> store.append(key, fixture.keyId(), 0L, 0L,
                new byte[AttachmentProperties.CHUNK_BYTES + 1], chunkSha))
                .isInstanceOfSatisfying(ValidationException.class,
                        e -> assertThat(e.code()).isEqualTo(ValidationCode.VALIDATION_FAILED));

        // Missing file for sequence > 0 -> ARTIFACT_CORRUPTED
        assertThatThrownBy(() -> store.append(key, fixture.keyId(), 100L, 1L, chunk, chunkSha))
                .isInstanceOfSatisfying(InfrastructureException.class,
                        e -> assertThat(e.code()).isEqualTo(InfrastructureCode.ARTIFACT_CORRUPTED));

        // First append succeeds; retry with same tail returns existing size
        long size1 = store.append(key, fixture.keyId(), 0L, 0L, chunk, chunkSha);
        assertThat(store.append(key, fixture.keyId(), 0L, 0L, chunk, chunkSha)).isEqualTo(size1);

        // Retry tail with mismatched SHA-256 -> ARTIFACT_CHUNK_CONFLICT
        Sha256 otherSha = Sha256.ofBytes("other-data".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> store.append(key, fixture.keyId(), 0L, 0L, chunk, otherSha))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.code()).isEqualTo(ConflictCode.ARTIFACT_CHUNK_CONFLICT));

        // Truncated file (size < storedBytes) -> ARTIFACT_CORRUPTED
        assertThatThrownBy(() -> store.append(key, fixture.keyId(), size1 + 10L, 1L, chunk, chunkSha))
                .isInstanceOfSatisfying(InfrastructureException.class,
                        e -> assertThat(e.code()).isEqualTo(InfrastructureCode.ARTIFACT_CORRUPTED));

        // Overlapping lock held in same JVM -> ARTIFACT_BUSY
        Path file = fixture.fileOf(key);
        try (FileChannel locker = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE);
                FileLock lock = locker.tryLock(Long.MAX_VALUE - 1, 1, false)) {
            assertThat(lock).isNotNull();
            assertThatThrownBy(() -> store.append(key, fixture.keyId(), size1, 1L, chunk, chunkSha))
                    .isInstanceOfSatisfying(ConflictException.class,
                            e -> assertThat(e.code()).isEqualTo(ConflictCode.ARTIFACT_BUSY));
            assertThatThrownBy(() -> store.finish(key, fixture.keyId(), size1, 1L, chunk.length, chunkSha))
                    .isInstanceOfSatisfying(ConflictException.class,
                            e -> assertThat(e.code()).isEqualTo(ConflictCode.ARTIFACT_BUSY));
        }

        // Tail mismatch cases: wrong length header, corrupted body (tag failure), tail > MAX_SEGMENT
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.WRITE)) {
            ch.write(ByteBuffer.allocate(8).putInt(999).putInt(0).flip(), size1);
        }
        assertThatThrownBy(() -> store.append(key, fixture.keyId(), size1, 1L, chunk, chunkSha))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.code()).isEqualTo(ConflictCode.ARTIFACT_CHUNK_CONFLICT));

        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.WRITE)) {
            ch.truncate(size1);
            byte[] badTail = new byte[4 + SegmentCipher.IV_BYTES + SegmentCipher.TAG_BYTES + 4];
            ByteBuffer.wrap(badTail).putInt(badTail.length - 4);
            ch.write(ByteBuffer.wrap(badTail), size1);
        }
        assertThatThrownBy(() -> store.append(key, fixture.keyId(), size1, 1L, chunk, chunkSha))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.code()).isEqualTo(ConflictCode.ARTIFACT_CHUNK_CONFLICT));

        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.WRITE)) {
            ch.truncate(size1);
            ch.write(ByteBuffer.allocate(AttachmentProperties.CHUNK_BYTES + SegmentCipher.OVERHEAD + 5), size1);
        }
        assertThatThrownBy(() -> store.append(key, fixture.keyId(), size1, 1L, chunk, chunkSha))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.code()).isEqualTo(ConflictCode.ARTIFACT_CHUNK_CONFLICT));

        // Append on an already-finished file -> endSegmentFollows detects closed file -> ARTIFACT_CHUNK_CONFLICT
        Written finished = fixture.write(fixture.keyId(), chunk);
        long finishedBodyLen = Files.size(fixture.fileOf(finished.key())) - SegmentCipher.OVERHEAD;
        assertThatThrownBy(() -> store.append(finished.key(), fixture.keyId(), finishedBodyLen, 1L, chunk, chunkSha))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.code()).isEqualTo(ConflictCode.ARTIFACT_CHUNK_CONFLICT));
    }

    @Test
    @DisplayName("AST-03 finish handles missing/truncated/incomplete/mismatched/bad-endMark and idempotent sealed retries")
    void finishEdgeAndFailureBranches() throws IOException {
        StorageKey missingKey = fixture.storageKeys().next();
        Sha256 emptySha = Sha256.ofBytes(new byte[0]);
        assertThatThrownBy(() -> store.finish(missingKey, fixture.keyId(), 0L, 1L, 0L, emptySha))
                .isInstanceOfSatisfying(InfrastructureException.class,
                        e -> assertThat(e.code()).isEqualTo(InfrastructureCode.ARTIFACT_CORRUPTED));

        StorageKey key = fixture.storageKeys().next();
        byte[] chunk = "payload-123".getBytes(StandardCharsets.UTF_8);
        Sha256 chunkSha = Sha256.ofBytes(chunk);
        long size1 = store.append(key, fixture.keyId(), 0L, 0L, chunk, chunkSha);

        // Truncated (size < storedBytes) -> ARTIFACT_CORRUPTED
        assertThatThrownBy(() -> store.finish(key, fixture.keyId(), size1 + 1L, 1L, chunk.length, chunkSha))
                .isInstanceOfSatisfying(InfrastructureException.class,
                        e -> assertThat(e.code()).isEqualTo(InfrastructureCode.ARTIFACT_CORRUPTED));

        // Incomplete declared size (plainBytes < declaredSize) -> ARTIFACT_INCOMPLETE
        assertThatThrownBy(() -> store.finish(key, fixture.keyId(), size1, 1L, chunk.length + 5L, chunkSha))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.code()).isEqualTo(ConflictCode.ARTIFACT_INCOMPLETE));

        // Larger than declared size (plainBytes > declaredSize) -> ARTIFACT_HASH_MISMATCH
        assertThatThrownBy(() -> store.finish(key, fixture.keyId(), size1, 1L, chunk.length - 1L, chunkSha))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.code()).isEqualTo(ConflictCode.ARTIFACT_HASH_MISMATCH));

        // Wrong declared SHA-256 -> ARTIFACT_HASH_MISMATCH
        assertThatThrownBy(() -> store.finish(key, fixture.keyId(), size1, 1L, chunk.length, emptySha))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.code()).isEqualTo(ConflictCode.ARTIFACT_HASH_MISMATCH));

        // walk position != limit (nextSequence=0 when storedBytes > 0) -> ARTIFACT_CORRUPTED
        assertThatThrownBy(() -> store.finish(key, fixture.keyId(), size1, 0L, chunk.length, chunkSha))
                .isInstanceOfSatisfying(InfrastructureException.class,
                        e -> assertThat(e.code()).isEqualTo(InfrastructureCode.ARTIFACT_CORRUPTED));

        // Unrecorded tail with size == OVERHEAD but bad length header / bad GCM tag -> isEndMark false -> ARTIFACT_INCOMPLETE
        Path file = fixture.fileOf(key);
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.WRITE)) {
            byte[] wrongLenEnd = new byte[SegmentCipher.OVERHEAD];
            ByteBuffer.wrap(wrongLenEnd).putInt(1);
            ch.write(ByteBuffer.wrap(wrongLenEnd), size1);
        }
        assertThatThrownBy(() -> store.finish(key, fixture.keyId(), size1, 1L, chunk.length, chunkSha))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.code()).isEqualTo(ConflictCode.ARTIFACT_INCOMPLETE));

        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.WRITE)) {
            byte[] wrongTagEnd = new byte[SegmentCipher.OVERHEAD];
            ByteBuffer.wrap(wrongTagEnd).putInt(SegmentCipher.IV_BYTES + SegmentCipher.TAG_BYTES);
            ch.write(ByteBuffer.wrap(wrongTagEnd), size1);
        }
        assertThatThrownBy(() -> store.finish(key, fixture.keyId(), size1, 1L, chunk.length, chunkSha))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.code()).isEqualTo(ConflictCode.ARTIFACT_INCOMPLETE));

        // Truncate back to clean storedBytes, finish once, and retry finish idempotently on sealed file
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.WRITE)) {
            ch.truncate(size1);
        }
        long finishedSize = store.finish(key, fixture.keyId(), size1, 1L, chunk.length, chunkSha);
        assertThat(finishedSize).isEqualTo(size1 + SegmentCipher.OVERHEAD);
        assertThat(store.finish(key, fixture.keyId(), size1, 1L, chunk.length, chunkSha)).isEqualTo(finishedSize);
    }

    @Test
    @DisplayName("AST-04 stream, read, verify, and rekey validate inputs, reject empty/missing/tampered files, and clean up failed rekeys")
    void streamReadVerifyAndRekeyBranches() throws IOException {
        StorageKey missing = fixture.storageKeys().next();
        assertThatThrownBy(() -> store.stream(missing, fixture.keyId(), new ByteArrayOutputStream()))
                .isInstanceOfSatisfying(InfrastructureException.class,
                        e -> assertThat(e.code()).isEqualTo(InfrastructureCode.ARTIFACT_CORRUPTED));
        assertThatThrownBy(() -> store.read(missing, fixture.keyId(), -1L, 10))
                .isInstanceOfSatisfying(ValidationException.class,
                        e -> assertThat(e.code()).isEqualTo(ValidationCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> store.read(missing, fixture.keyId(), 0L, 0))
                .isInstanceOfSatisfying(ValidationException.class,
                        e -> assertThat(e.code()).isEqualTo(ValidationCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> store.read(missing, fixture.keyId(), 0L, AttachmentProperties.CHUNK_BYTES + 1))
                .isInstanceOfSatisfying(ValidationException.class,
                        e -> assertThat(e.code()).isEqualTo(ValidationCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> store.read(missing, fixture.keyId(), 0L, 10))
                .isInstanceOfSatisfying(InfrastructureException.class,
                        e -> assertThat(e.code()).isEqualTo(InfrastructureCode.ARTIFACT_CORRUPTED));
        assertThatThrownBy(() -> store.verify(missing, fixture.keyId()))
                .isInstanceOfSatisfying(InfrastructureException.class,
                        e -> assertThat(e.code()).isEqualTo(InfrastructureCode.ARTIFACT_CORRUPTED));

        String newKeyId = fixture.newKey();
        StorageKey rekeyTarget = fixture.storageKeys().next();
        assertThatThrownBy(() -> store.rekey(missing, fixture.keyId(), rekeyTarget, newKeyId))
                .isInstanceOfSatisfying(InfrastructureException.class,
                        e -> assertThat(e.code()).isEqualTo(InfrastructureCode.ARTIFACT_CORRUPTED));

        // 0-byte file on disk triggers ARTIFACT_CORRUPTED across stream/read/verify/rekey (with target cleanup)
        StorageKey zeroKey = fixture.storageKeys().next();
        Path zeroFile = fixture.fileOf(zeroKey);
        Files.createDirectories(zeroFile.getParent());
        Files.createFile(zeroFile);
        assertThatThrownBy(() -> store.stream(zeroKey, fixture.keyId(), new ByteArrayOutputStream()))
                .isInstanceOfSatisfying(InfrastructureException.class,
                        e -> assertThat(e.code()).isEqualTo(InfrastructureCode.ARTIFACT_CORRUPTED));
        assertThatThrownBy(() -> store.read(zeroKey, fixture.keyId(), 0L, 16))
                .isInstanceOfSatisfying(InfrastructureException.class,
                        e -> assertThat(e.code()).isEqualTo(InfrastructureCode.ARTIFACT_CORRUPTED));
        assertThatThrownBy(() -> store.verify(zeroKey, fixture.keyId()))
                .isInstanceOfSatisfying(InfrastructureException.class,
                        e -> assertThat(e.code()).isEqualTo(InfrastructureCode.ARTIFACT_CORRUPTED));
        assertThatThrownBy(() -> store.rekey(zeroKey, fixture.keyId(), rekeyTarget, newKeyId))
                .isInstanceOfSatisfying(InfrastructureException.class,
                        e -> assertThat(e.code()).isEqualTo(InfrastructureCode.ARTIFACT_CORRUPTED));
        assertThat(store.exists(rekeyTarget)).isFalse();

        // Non-empty segment marked last=true -> ARTIFACT_CORRUPTED across stream/read/verify/rekey
        StorageKey badLastKey = fixture.storageKeys().next();
        Path badLastFile = fixture.fileOf(badLastKey);
        Files.createDirectories(badLastFile.getParent());
        AttachmentKeys.Key aes = fixture.keys().byId(fixture.keyId());
        byte[] nonEmptyLast = fixture.cipher().seal(aes, badLastKey, 0L, true,
                "not-empty".getBytes(StandardCharsets.UTF_8));
        Files.write(badLastFile, nonEmptyLast);
        assertThatThrownBy(() -> store.stream(badLastKey, fixture.keyId(), new ByteArrayOutputStream()))
                .isInstanceOfSatisfying(InfrastructureException.class,
                        e -> assertThat(e.code()).isEqualTo(InfrastructureCode.ARTIFACT_CORRUPTED));
        assertThatThrownBy(() -> store.read(badLastKey, fixture.keyId(), 0L, 16))
                .isInstanceOfSatisfying(InfrastructureException.class,
                        e -> assertThat(e.code()).isEqualTo(InfrastructureCode.ARTIFACT_CORRUPTED));
        assertThatThrownBy(() -> store.verify(badLastKey, fixture.keyId()))
                .isInstanceOfSatisfying(InfrastructureException.class,
                        e -> assertThat(e.code()).isEqualTo(InfrastructureCode.ARTIFACT_CORRUPTED));
        assertThatThrownBy(() -> store.rekey(badLastKey, fixture.keyId(), rekeyTarget, newKeyId))
                .isInstanceOfSatisfying(InfrastructureException.class,
                        e -> assertThat(e.code()).isEqualTo(InfrastructureCode.ARTIFACT_CORRUPTED));

        // Truncated 2-byte file -> readLength < 4 bytes -> ARTIFACT_CORRUPTED
        StorageKey shortKey = fixture.storageKeys().next();
        Path shortFile = fixture.fileOf(shortKey);
        Files.createDirectories(shortFile.getParent());
        Files.write(shortFile, new byte[] {0, 1});
        assertThatThrownBy(() -> store.read(shortKey, fixture.keyId(), 0L, 16))
                .isInstanceOfSatisfying(InfrastructureException.class,
                        e -> assertThat(e.code()).isEqualTo(InfrastructureCode.ARTIFACT_CORRUPTED));

        // Valid multi-segment stream, high-offset read clamping, and successful rekey + duplicate target failure
        Written valid = fixture.write(fixture.keyId(),
                "hello-".getBytes(StandardCharsets.UTF_8), "world".getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(store.stream(valid.key(), fixture.keyId(), out)).isEqualTo(valid.plain().length);
        assertThat(out.toByteArray()).isEqualTo(valid.plain());
        Page pastEnd = store.read(valid.key(), fixture.keyId(), Long.MAX_VALUE - 2L, 10);
        assertThat(pastEnd.data()).isEmpty();
        assertThat(pastEnd.totalSize()).isEqualTo(valid.plain().length);

        store.rekey(valid.key(), fixture.keyId(), rekeyTarget, newKeyId);
        assertThat(store.verify(rekeyTarget, newKeyId).sizeBytes()).isEqualTo(valid.plain().length);
        assertThatThrownBy(() -> store.rekey(valid.key(), fixture.keyId(), rekeyTarget, newKeyId))
                .isInstanceOfSatisfying(InfrastructureException.class,
                        e -> assertThat(e.code()).isEqualTo(InfrastructureCode.ARTIFACT_STORAGE_IO_ERROR));
    }

    @Test
    @DisplayName("AST-05 list, quarantine, restore, purgeQuarantine, remove, and PrivateFiles unsafe-directory checks")
    void folderOperationsAndPrivateFilesGuard() throws IOException {
        Written a = fixture.write(fixture.keyId(), "alpha".getBytes(StandardCharsets.UTF_8));
        Written b = fixture.write(fixture.keyId(), "beta".getBytes(StandardCharsets.UTF_8));

        // Non-StorageKey file inside a bucket folder is ignored by list()
        Path stray = fixture.fileOf(a.key()).getParent().resolve("not-a-storage-key.tmp");
        Files.writeString(stray, "stray");
        assertThat(store.list().stream().map(AttachmentStore.StoredFile::key).toList())
                .containsExactlyInAnyOrder(a.key(), b.key());

        // Quarantine, duplicate quarantine target rejection, restore, selective purge, missing remove
        store.quarantine(a.key());
        assertThat(store.exists(a.key())).isFalse();
        assertThat(store.listQuarantined()).extracting(AttachmentStore.StoredFile::key).containsExactly(a.key());
        // No-op quarantine when source does not exist
        store.quarantine(a.key());

        // Restore a, then create duplicate in quarantine and confirm move refuses overwrite
        store.restore(a.key());
        assertThat(store.exists(a.key())).isTrue();
        Path dupQ = fixture.properties().directory().resolve("orphans").resolve(a.key().hex());
        Files.writeString(dupQ, "collision");
        assertThatThrownBy(() -> store.quarantine(a.key()))
                .isInstanceOfSatisfying(InfrastructureException.class,
                        e -> assertThat(e.code()).isEqualTo(InfrastructureCode.ARTIFACT_STORAGE_IO_ERROR));
        FileModes.makeWritable(dupQ);
        Files.delete(dupQ);

        store.quarantine(a.key());
        store.quarantine(b.key());
        assertThat(store.purgeQuarantine(k -> k.equals(a.key()))).isEqualTo(1);
        assertThat(store.listQuarantined()).extracting(AttachmentStore.StoredFile::key).containsExactly(b.key());

        StorageKey neverCreated = fixture.storageKeys().next();
        assertThat(store.remove(neverCreated)).isFalse();
        store.restore(b.key());
        assertThat(store.remove(b.key())).isTrue();
        assertThat(store.exists(b.key())).isFalse();

        // PrivateFiles.ensureDirectory rejects regular file with ARTIFACT_STORAGE_UNSAFE
        Path regularFile = fixture.root().resolve("not-a-dir");
        Files.writeString(regularFile, "file");
        assertThatThrownBy(() -> fixture.files().ensureDirectory(regularFile))
                .isInstanceOfSatisfying(InfrastructureException.class,
                        e -> assertThat(e.code()).isEqualTo(InfrastructureCode.ARTIFACT_STORAGE_UNSAFE));
    }
}
