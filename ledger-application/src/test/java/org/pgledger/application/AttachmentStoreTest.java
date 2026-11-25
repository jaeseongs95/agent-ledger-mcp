// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.application;

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
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.application.attachment.AttachmentProperties;
import org.pgledger.application.attachment.AttachmentStore;
import org.pgledger.application.attachment.SegmentCipher;
import org.pgledger.application.support.StoreFixture;
import org.pgledger.application.support.StoreFixture.Written;
import org.pgledger.application.support.StoredFiles;
import org.pgledger.application.support.Uploads;
import org.pgledger.domain.error.ConflictCode;
import org.pgledger.domain.error.ConflictException;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;
import org.pgledger.domain.value.Sha256;
import org.pgledger.domain.value.StorageKey;

/**
 * ATA-004, ATA-005, ATA-006, ATA-027, and the store parts of ATA-029 and ATA-031: the encrypted files of the bodies on a temporary
 * folder, with the real storage, cipher and key components and no database. The files are changed on the disk the way an attacker or a
 * failing disk would change them, and the read has to report the damage and return no page.
 */
class AttachmentStoreTest {

    private static final int PAGE = AttachmentProperties.CHUNK_BYTES;

    private StoreFixture fixture;
    private AttachmentStore store;

    @BeforeEach
    void open() {
        fixture = StoreFixture.create();
        store = fixture.store();
    }

    @AfterEach
    void close() {
        fixture.close();
    }

    private Written threeChunks() {
        return fixture.write(fixture.keyId(), Uploads.body(100, 1), Uploads.body(200, 2), Uploads.body(50, 3));
    }

    private void assertCorrupted(Written written) {
        assertThatThrownBy(() -> store.read(written.key(), written.keyId(), 0, PAGE))
                .isInstanceOfSatisfying(InfrastructureException.class,
                        failure -> assertThat(failure.code()).isEqualTo(InfrastructureCode.ARTIFACT_CORRUPTED));
        assertThatThrownBy(() -> store.verify(written.key(), written.keyId()))
                .isInstanceOfSatisfying(InfrastructureException.class,
                        failure -> assertThat(failure.code()).isEqualTo(InfrastructureCode.ARTIFACT_CORRUPTED));
    }

    private static byte[] joined(List<byte[]> segments, int... indexes) {
        return StoredFiles.join(Arrays.stream(indexes).mapToObj(segments::get).toList());
    }

    // ------------------------------------------------------------------------------------------------ ATA-004

    @Test
    @DisplayName("ATA-004: the file as it was written reads back whole")
    void ata004_theUntouchedFileReads() {
        Written written = threeChunks();

        assertThat(store.read(written.key(), written.keyId(), 0, PAGE).data()).isEqualTo(written.plain());
        assertThat(store.verify(written.key(), written.keyId()).sha256()).isEqualTo(Sha256.ofBytes(written.plain()));
    }

    @Test
    @DisplayName("ATA-004: one changed byte of a ciphertext, of a tag, of an IV or of a length prefix is ARTIFACT_CORRUPTED")
    void ata004_aChangedByteIsCorrupted() {
        Written written = threeChunks();
        Path file = fixture.fileOf(written.key());
        byte[] raw = StoredFiles.readAll(file);
        int second = StoredFiles.segments(raw).getFirst().length;
        int ciphertextOfSecond = second + SegmentCipher.LENGTH_BYTES + SegmentCipher.IV_BYTES + 5;
        int ivOfSecond = second + SegmentCipher.LENGTH_BYTES + 3;
        int lengthOfSecond = second + SegmentCipher.LENGTH_BYTES - 1;
        int tagOfEndMark = raw.length - 1;

        for (int index : new int[] {ciphertextOfSecond, ivOfSecond, lengthOfSecond, tagOfEndMark, 0}) {
            StoredFiles.replace(file, StoredFiles.flip(raw, index));
            assertCorrupted(written);
        }
        StoredFiles.replace(file, raw);
        assertThat(store.read(written.key(), written.keyId(), 0, PAGE).data()).as("the original bytes read again")
                .isEqualTo(written.plain());
    }

    @Test
    @DisplayName("ATA-004: two segments of different sizes swapped, and two of the same size swapped, are ARTIFACT_CORRUPTED")
    void ata004_swappedSegmentsAreCorrupted() {
        Written different = threeChunks();
        List<byte[]> segments = StoredFiles.segments(StoredFiles.readAll(fixture.fileOf(different.key())));
        StoredFiles.replace(fixture.fileOf(different.key()), joined(segments, 1, 0, 2, 3));
        assertCorrupted(different);

        Written same = fixture.write(fixture.keyId(), Uploads.body(100, 4), Uploads.body(100, 5), Uploads.body(50, 6));
        List<byte[]> equal = StoredFiles.segments(StoredFiles.readAll(fixture.fileOf(same.key())));
        assertThat(equal.get(0)).hasSameSizeAs(equal.get(1));
        StoredFiles.replace(fixture.fileOf(same.key()), joined(equal, 1, 0, 2, 3));
        assertCorrupted(same);
    }

    @Test
    @DisplayName("ATA-004: a cut tail (with the end mark segment, without it, in the middle of a segment, the whole file) and a removed end mark are ARTIFACT_CORRUPTED")
    void ata004_aCutTailIsCorrupted() {
        Written written = threeChunks();
        Path file = fixture.fileOf(written.key());
        byte[] raw = StoredFiles.readAll(file);
        List<byte[]> segments = StoredFiles.segments(raw);
        assertThat(segments).as("three chunks and the end mark").hasSize(4);

        StoredFiles.replace(file, joined(segments, 0, 1, 2));
        assertCorrupted(written);
        StoredFiles.replace(file, joined(segments, 0, 1));
        assertCorrupted(written);
        StoredFiles.replace(file, Arrays.copyOf(raw, raw.length - 10));
        assertCorrupted(written);
        StoredFiles.replace(file, Arrays.copyOf(raw, 2));
        assertCorrupted(written);
        StoredFiles.replace(file, new byte[0]);
        assertCorrupted(written);
    }

    @Test
    @DisplayName("ATA-004: a page that covers only an early segment is not returned when a later segment is damaged (the lengths are followed to the end mark)")
    void ata004_aDamagedTailFailsAnEarlyPageToo() {
        Written written = threeChunks();
        Path file = fixture.fileOf(written.key());
        byte[] raw = StoredFiles.readAll(file);
        List<byte[]> segments = StoredFiles.segments(raw);
        StoredFiles.replace(file, joined(segments, 0, 1, 2));

        assertThatThrownBy(() -> store.read(written.key(), written.keyId(), 0, 10))
                .isInstanceOfSatisfying(InfrastructureException.class,
                        failure -> assertThat(failure.code()).isEqualTo(InfrastructureCode.ARTIFACT_CORRUPTED));
    }

    // ------------------------------------------------------------------------------------------------ ATA-005

    @Test
    @DisplayName("ATA-005: the file of another attachment put under the name fails the tag (the storage key is in the AAD)")
    void ata005_theFileOfAnotherAttachmentUnderTheNameIsCorrupted() {
        Written a = fixture.write(fixture.keyId(), Uploads.body(100, 10), Uploads.body(100, 11));
        Written b = fixture.write(fixture.keyId(), Uploads.body(100, 12), Uploads.body(100, 13));
        byte[] fileOfB = StoredFiles.readAll(fixture.fileOf(b.key()));

        StoredFiles.replace(fixture.fileOf(a.key()), fileOfB);

        assertCorrupted(a);
        assertThat(store.read(b.key(), b.keyId(), 0, PAGE).data()).as("B under its own name still reads").isEqualTo(b.plain());
    }

    // ------------------------------------------------------------------------------------------------ ATA-006

    @Test
    @DisplayName("ATA-006: the name is 32 hex characters in a two-character folder, with no extension and nothing of the file; the bytes hold no run of 8 plain bytes")
    void ata006_nothingOfTheFileShowsOnTheDisk() throws IOException {
        byte[] sentence = "The review report says that the ledger keeps its evidence outside the database."
                .getBytes(StandardCharsets.UTF_8);
        Written written = fixture.write(fixture.keyId(), Arrays.copyOfRange(sentence, 0, 30),
                Arrays.copyOfRange(sentence, 30, sentence.length));

        Path file = fixture.fileOf(written.key());
        String name = file.getFileName().toString();
        assertThat(name).matches("[0-9a-f]{32}").doesNotContain(".");
        assertThat(file.getParent().getFileName().toString()).hasSize(2).isEqualTo(name.substring(0, 2));
        try (Stream<Path> entries = Files.list(fixture.properties().directory())) {
            assertThat(entries.map(entry -> entry.getFileName().toString()).collect(Collectors.toSet()))
                    .containsExactlyInAnyOrder(name.substring(0, 2), "orphans");
        }
        try (Stream<Path> entries = Files.list(file.getParent())) {
            assertThat(entries.map(entry -> entry.getFileName().toString()).toList()).containsExactly(name);
        }
        assertThat(store.list()).singleElement().satisfies(stored -> assertThat(stored.key()).isEqualTo(written.key()));

        byte[] raw = StoredFiles.readAll(file);
        for (int start = 0; start + 8 <= sentence.length; start++) {
            byte[] run = Arrays.copyOfRange(sentence, start, start + 8);
            assertThat(StoredFiles.indexOf(raw, run)).as("run at %d is not in the file", start).isEqualTo(-1);
        }
        assertThat(StoredFiles.indexOf(raw, Uploads.NAME.getBytes(StandardCharsets.UTF_8))).isEqualTo(-1);
    }

    // ------------------------------------------------------------------------------------------------ ATA-027

    @Test
    @DisplayName("ATA-027: chunks of 10, 300 and 7 bytes are three segments of 4 + 12 + n + 16 bytes and the end mark; the 4-byte prefix is the length of what follows")
    void ata027_theSegmentsHaveTheirLengthPrefix() {
        Written written = fixture.write(fixture.keyId(), Uploads.body(10, 21), Uploads.body(300, 22), Uploads.body(7, 23));

        byte[] raw = StoredFiles.readAll(fixture.fileOf(written.key()));
        List<byte[]> segments = StoredFiles.segments(raw);
        assertThat(segments).extracting(segment -> segment.length).containsExactly(4 + 12 + 10 + 16, 4 + 12 + 300 + 16,
                4 + 12 + 7 + 16, 4 + 12 + 0 + 16);
        for (byte[] segment : segments) {
            assertThat(ByteBuffer.wrap(segment).getInt()).as("the prefix is big endian and counts what follows it")
                    .isEqualTo(segment.length - 4);
        }
        assertThat(raw).hasSize(segments.stream().mapToInt(segment -> segment.length).sum());
        assertThat(SegmentCipher.OVERHEAD).isEqualTo(32);
    }

    @Test
    @DisplayName("ATA-027: the body is read back in pages of other sizes than the chunks, and from offsets inside and on the boundaries of the segments")
    void ata027_anyPageSizeReadsTheBodyBack() {
        Written written = fixture.write(fixture.keyId(), Uploads.body(10, 21), Uploads.body(300, 22), Uploads.body(7, 23));
        byte[] plain = written.plain();

        for (int pageBytes : new int[] {1, 7, 10, 13, 64, 100, 310, 317, 1000, PAGE}) {
            ByteArrayOutputStream joined = new ByteArrayOutputStream();
            long offset = 0;
            while (offset < plain.length) {
                AttachmentStore.Page page = store.read(written.key(), written.keyId(), offset, pageBytes);
                assertThat(page.totalSize()).isEqualTo(plain.length);
                assertThat(page.offset()).isEqualTo(offset);
                joined.writeBytes(page.data());
                offset += page.data().length;
            }
            assertThat(joined.toByteArray()).as("pages of %d bytes", pageBytes).isEqualTo(plain);
        }
        for (int offset : new int[] {0, 9, 10, 11, 309, 310, 311, 316}) {
            byte[] page = store.read(written.key(), written.keyId(), offset, 5).data();
            int end = Math.min(plain.length, offset + 5);
            assertThat(page).as("5 bytes from %d", offset).isEqualTo(Arrays.copyOfRange(plain, offset, end));
        }
        assertThat(store.read(written.key(), written.keyId(), plain.length, 5).data()).as("past the last byte").isEmpty();
    }

    // ------------------------------------------------------------------------------------------------ ATA-029 (the store)

    @Test
    @DisplayName("ATA-029: while the byte past the end is locked by another channel the append answers ARTIFACT_BUSY at once; after the lock is released the same chunk is appended")
    void ata029_aHeldLockMakesTheAppendBusy() throws IOException {
        StorageKey key = fixture.storageKeys().next();
        byte[] first = Uploads.body(100, 31);
        byte[] second = Uploads.body(100, 32);
        long stored = store.append(key, fixture.keyId(), 0, 0, first, Sha256.ofBytes(first));
        Path file = fixture.fileOf(key);

        try (FileChannel holder = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE);
                FileLock lock = holder.tryLock(Long.MAX_VALUE - 1, 1, false)) {
            assertThat(lock).isNotNull();
            assertThatThrownBy(() -> store.append(key, fixture.keyId(), stored, 1, second, Sha256.ofBytes(second)))
                    .isInstanceOfSatisfying(ConflictException.class,
                            failure -> assertThat(failure.code()).isEqualTo(ConflictCode.ARTIFACT_BUSY));
            assertThat(Files.size(file)).as("nothing was appended").isEqualTo(stored);
        }
        long after = store.append(key, fixture.keyId(), stored, 1, second, Sha256.ofBytes(second));
        assertThat(after).isEqualTo(Files.size(file)).isGreaterThan(stored);
    }

    // ------------------------------------------------------------------------------------------------ ATA-031 (the store)

    @Test
    @DisplayName("ATA-031: a body written with key A is written again with key B under a new name; it reads with the same plain SHA-256 and the old file is untouched")
    void ata031_rekeyWritesANewFile() {
        Written old = fixture.write(fixture.keyId(), Uploads.body(100, 41), Uploads.body(250, 42), Uploads.body(9, 43));
        String keyB = fixture.newKey();
        StorageKey fresh = fixture.storageKeys().next();
        byte[] oldBytes = StoredFiles.readAll(fixture.fileOf(old.key()));

        store.rekey(old.key(), old.keyId(), fresh, keyB);

        assertThat(fresh).isNotEqualTo(old.key());
        assertThat(store.read(fresh, keyB, 0, PAGE).data()).isEqualTo(old.plain());
        assertThat(store.verify(fresh, keyB).sha256()).isEqualTo(Sha256.ofBytes(old.plain()));
        assertThat(StoredFiles.readAll(fixture.fileOf(fresh))).as("sealed again, not copied").isNotEqualTo(oldBytes);
        assertThat(StoredFiles.readAll(fixture.fileOf(old.key()))).as("the old file is not touched").isEqualTo(oldBytes);
        assertThat(store.read(old.key(), old.keyId(), 0, PAGE).data()).isEqualTo(old.plain());
        assertThat(fixture.fileOf(fresh).toFile().canWrite()).as("the new file is read only").isFalse();
        Set<StorageKey> listed = store.list().stream().map(AttachmentStore.StoredFile::key).collect(Collectors.toSet());
        assertThat(listed).containsExactlyInAnyOrder(old.key(), fresh);
    }

    @Test
    @DisplayName("ATA-031: a rekey of a file that does not pass leaves no new file behind")
    void ata031_aRekeyOfADamagedFileLeavesNothing() {
        Written old = fixture.write(fixture.keyId(), Uploads.body(100, 44), Uploads.body(50, 45));
        String keyB = fixture.newKey();
        StorageKey fresh = fixture.storageKeys().next();
        List<byte[]> segments = StoredFiles.segments(StoredFiles.readAll(fixture.fileOf(old.key())));
        StoredFiles.replace(fixture.fileOf(old.key()), joined(segments, 0, 1));

        assertThatThrownBy(() -> store.rekey(old.key(), old.keyId(), fresh, keyB))
                .isInstanceOfSatisfying(InfrastructureException.class,
                        failure -> assertThat(failure.code()).isEqualTo(InfrastructureCode.ARTIFACT_CORRUPTED));

        assertThat(Files.exists(fixture.fileOf(fresh))).isFalse();
    }
}
