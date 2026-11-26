// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.veridger.application.attachment.AttachmentStore;
import org.veridger.application.support.CountingFiles;
import org.veridger.application.support.StoreFixture;
import org.veridger.application.support.StoreFixture.Written;
import org.veridger.application.support.Uploads;
import org.veridger.domain.error.InfrastructureCode;
import org.veridger.domain.error.InfrastructureException;

/**
 * R9-D0074: a page of {@link AttachmentStore#read} opens the segments that cover the page and only follows the lengths of the others,
 * so reading a large file page by page does not read the whole file for every page. The end mark and the structure of the whole file
 * are still checked.
 */
class AttachmentReadCostTest {

    private static final int SEGMENT = 100_000;
    private static final int SEGMENTS = 6;

    private StoreFixture fixture;
    private CountingFiles counting;
    private AttachmentStore store;
    private Written written;
    private long fileSize;

    @BeforeEach
    void open() throws IOException {
        fixture = StoreFixture.create();
        byte[][] chunks = new byte[SEGMENTS][];
        for (int i = 0; i < SEGMENTS; i++) {
            chunks[i] = Uploads.body(SEGMENT, 7400L + i);
        }
        written = fixture.write(fixture.keyId(), chunks);
        fileSize = Files.size(fixture.fileOf(written.key()));
        counting = new CountingFiles();
        store = new AttachmentStore(fixture.properties(), counting, fixture.keys(), fixture.cipher());
    }

    @AfterEach
    void close() {
        fixture.close();
    }

    @Test
    @DisplayName("R9-D0074: a page inside one segment reads that segment, the lengths of the others and the end mark, not the whole file")
    void r9D0074_aPageDoesNotReadTheWholeFile() {
        counting.reset();

        AttachmentStore.Page page = store.read(written.key(), written.keyId(), 3 * (long) SEGMENT + 10, 1000);

        assertThat(page.data()).isEqualTo(Arrays.copyOfRange(written.plain(), 3 * SEGMENT + 10, 3 * SEGMENT + 1010));
        assertThat(page.totalSize()).isEqualTo((long) SEGMENTS * SEGMENT);
        assertThat(counting.bytesRead()).as("one segment of %d bytes and the lengths, of a file of %d bytes", SEGMENT, fileSize)
                .isLessThan(fileSize / 2);
    }

    @Test
    @DisplayName("R9-D0074: a page that spans two segments is the bytes of both, and a cut-off tail is still found")
    void r9D0074_theStructureIsStillChecked() throws IOException {
        AttachmentStore.Page page = store.read(written.key(), written.keyId(), SEGMENT - 5, 10);
        assertThat(page.data()).isEqualTo(Arrays.copyOfRange(written.plain(), SEGMENT - 5, SEGMENT + 5));

        Path file = fixture.fileOf(written.key());
        byte[] all = Files.readAllBytes(file);
        assertThat(file.toFile().setWritable(true)).isTrue();
        Files.write(file, Arrays.copyOf(all, all.length - 3));

        assertThatThrownBy(() -> store.read(written.key(), written.keyId(), 0, 10)).isInstanceOfSatisfying(
                InfrastructureException.class, failure -> assertThat(failure.code()).isEqualTo(InfrastructureCode.ARTIFACT_CORRUPTED));
    }
}
