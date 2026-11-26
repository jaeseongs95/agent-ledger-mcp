// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.veridger.application.attachment.AttachmentStore;
import org.veridger.application.support.StoreFixture;
import org.veridger.domain.error.InfrastructureCode;
import org.veridger.domain.error.InfrastructureException;
import org.veridger.domain.value.Sha256;
import org.veridger.domain.value.StorageKey;

/**
 * R9-D0077: the file of an upload that has recorded chunks is never made again. A chunk or a finish that arrives after the reaper has
 * removed the row and the file finds no file and says so (ARTIFACT_CORRUPTED, the answer for a file that is missing), and leaves no
 * new empty file without a row. The first chunk of an upload, and the finish of an upload with no chunk, make the file.
 */
class AttachmentStoreMissingFileTest {

    private static final byte[] CHUNK = "a chunk of a review report".getBytes(StandardCharsets.UTF_8);

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

    private static void assertMissing(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(InfrastructureException.class,
                failure -> assertThat(failure.code()).isEqualTo(InfrastructureCode.ARTIFACT_CORRUPTED));
    }

    @Test
    @DisplayName("R9-D0077: a later chunk of an upload whose file is gone is ARTIFACT_CORRUPTED and makes no file")
    void r9D0077_aLaterChunkMakesNoFile() {
        StorageKey key = fixture.storageKeys().next();

        assertMissing(() -> store.append(key, fixture.keyId(), 100, 1, CHUNK, Sha256.ofBytes(CHUNK)));

        assertThat(fixture.fileOf(key)).as("no empty file without a row is left").doesNotExist();
    }

    @Test
    @DisplayName("R9-D0077: the finish of an upload with chunks whose file is gone is ARTIFACT_CORRUPTED and makes no file")
    void r9D0077_aFinishMakesNoFile() {
        StorageKey key = fixture.storageKeys().next();

        assertMissing(() -> store.finish(key, fixture.keyId(), 100, 2, 2L * CHUNK.length, Sha256.ofBytes(CHUNK)));

        assertThat(fixture.fileOf(key)).doesNotExist();
    }

    @Test
    @DisplayName("R9-D0077: the first chunk makes the file, and so does the finish of an empty upload that has no chunk")
    void r9D0077_theFirstChunkAndTheEmptyUploadMakeTheFile() throws Exception {
        StorageKey first = fixture.storageKeys().next();
        long stored = store.append(first, fixture.keyId(), 0, 0, CHUNK, Sha256.ofBytes(CHUNK));
        assertThat(Files.size(fixture.fileOf(first))).isEqualTo(stored);

        StorageKey empty = fixture.storageKeys().next();
        long finished = store.finish(empty, fixture.keyId(), 0, 0, 0, Sha256.ofBytes(new byte[0]));
        assertThat(Files.size(fixture.fileOf(empty))).isEqualTo(finished);
        assertThat(store.read(empty, fixture.keyId(), 0, 10).data()).isEmpty();
    }
}
