// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.agentledger.application.attachment.AttachmentStore;
import org.agentledger.application.attachment.SegmentCipher;
import org.agentledger.application.support.StoreFixture;
import org.agentledger.domain.error.ConflictCode;
import org.agentledger.domain.error.ConflictException;
import org.agentledger.domain.value.Sha256;
import org.agentledger.domain.value.StorageKey;

/**
 * Whether a file is closed is decided from its data, the valid end segment, and never from its permission bit (ATA-028 and design 4.4):
 * a process that runs as root can write a read-only file, so the bit cannot be the decision. The permission bit is only the second
 * guard that the finish sets. No operating system is asked for: the bit is changed with {@code java.io.File}, which works on every file
 * system the JDK supports.
 */
class AttachmentFinishStateTest {

    private static final byte[] FIRST = "the first chunk of a review report ".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final byte[] SECOND = "and the second one".getBytes(java.nio.charset.StandardCharsets.UTF_8);

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

    /** What the database would record after the two chunks: the file length and the number of the next chunk. */
    private record Appended(StorageKey key, long storedBytes, long nextSequence, byte[] plain) {
    }

    private Appended appendTwoChunks() {
        StorageKey key = fixture.storageKeys().next();
        long stored = store.append(key, fixture.keyId(), 0, 0, FIRST, Sha256.ofBytes(FIRST));
        stored = store.append(key, fixture.keyId(), stored, 1, SECOND, Sha256.ofBytes(SECOND));
        byte[] plain = new byte[FIRST.length + SECOND.length];
        System.arraycopy(FIRST, 0, plain, 0, FIRST.length);
        System.arraycopy(SECOND, 0, plain, FIRST.length, SECOND.length);
        return new Appended(key, stored, 2, plain);
    }

    @Test
    @DisplayName("ATA-028: a file whose permission bit says read only but whose row says STAGING and that has no end segment is still open: the finish closes it")
    void aReadOnlyBitDoesNotCloseAFileThatHasNoEndSegment() throws IOException {
        Appended up = appendTwoChunks();
        Path file = fixture.fileOf(up.key());
        assertThat(file.toFile().setReadOnly()).as("the bit is set read only").isTrue();

        long length = store.finish(up.key(), fixture.keyId(), up.storedBytes(), up.nextSequence(), up.plain().length,
                Sha256.ofBytes(up.plain()));

        assertThat(length).as("the end segment was appended").isEqualTo(up.storedBytes() + SegmentCipher.OVERHEAD);
        assertThat(Files.size(file)).isEqualTo(length);
        AttachmentStore.Page page = store.read(up.key(), fixture.keyId(), 0, 1024);
        assertThat(page.data()).isEqualTo(up.plain());
        assertThat(page.totalSize()).isEqualTo(up.plain().length);
    }

    @Test
    @DisplayName("ATA-028: a writable file that has the valid end segment is closed: a repeated finish appends nothing and a chunk is refused")
    void aWritableBitDoesNotOpenAFileThatHasTheEndSegment() throws IOException {
        Appended up = appendTwoChunks();
        Path file = fixture.fileOf(up.key());
        long closed = store.finish(up.key(), fixture.keyId(), up.storedBytes(), up.nextSequence(), up.plain().length,
                Sha256.ofBytes(up.plain()));
        byte[] before = Files.readAllBytes(file);
        // the bit says writable (an owner that runs as root, or a copy of the file), the data says closed
        assertThat(file.toFile().setWritable(true)).isTrue();

        long again = store.finish(up.key(), fixture.keyId(), up.storedBytes(), up.nextSequence(), up.plain().length,
                Sha256.ofBytes(up.plain()));

        assertThat(again).isEqualTo(closed);
        assertThat(Files.readAllBytes(file)).as("nothing was appended or changed").isEqualTo(before);
        // no overwrite: a chunk of the same number as the end segment's is refused whatever the bit says. The repeated finish made the
        // file read only again (second guard), so this append meets the bit "read only" with the data "closed": a conflict, not the
        // I/O error of opening a read-only file for writing.
        byte[] other = "another chunk".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThatThrownBy(() -> store.append(up.key(), fixture.keyId(), up.storedBytes(), up.nextSequence(), other,
                Sha256.ofBytes(other)))
                .isInstanceOfSatisfying(ConflictException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ConflictCode.ARTIFACT_CHUNK_CONFLICT));
        assertThat(Files.readAllBytes(file)).isEqualTo(before);
        // and with the bit "writable" and the data "closed" (the case of the comment above) it is the same conflict
        assertThat(file.toFile().setWritable(true)).isTrue();
        assertThatThrownBy(() -> store.append(up.key(), fixture.keyId(), up.storedBytes(), up.nextSequence(), other,
                Sha256.ofBytes(other)))
                .isInstanceOfSatisfying(ConflictException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ConflictCode.ARTIFACT_CHUNK_CONFLICT));
        assertThat(Files.readAllBytes(file)).isEqualTo(before);
    }

    @Test
    @DisplayName("ATA-028: a tail that is not the end segment is ARTIFACT_INCOMPLETE whatever the bit says, and the bytes stay")
    void aTailThatIsNoEndSegmentIsIncompleteWhateverTheBitSays() throws IOException {
        Appended up = appendTwoChunks();
        Path file = fixture.fileOf(up.key());
        byte[] third = "a chunk that was appended and not recorded".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        store.append(up.key(), fixture.keyId(), up.storedBytes(), 2, third, Sha256.ofBytes(third));
        long length = Files.size(file);
        assertThat(file.toFile().setReadOnly()).isTrue();

        assertThatThrownBy(() -> store.finish(up.key(), fixture.keyId(), up.storedBytes(), up.nextSequence(),
                up.plain().length, Sha256.ofBytes(up.plain())))
                .isInstanceOfSatisfying(ConflictException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ConflictCode.ARTIFACT_INCOMPLETE));

        assertThat(Files.size(file)).as("the tail is not cut off").isEqualTo(length);
    }
}
