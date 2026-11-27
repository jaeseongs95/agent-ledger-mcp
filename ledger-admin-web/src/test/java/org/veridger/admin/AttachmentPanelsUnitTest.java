// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.unit.DataSize;
import org.veridger.admin.console.AttachmentPanels;
import org.veridger.admin.console.AttachmentPanels.Panel;
import org.veridger.admin.console.AttachmentPanels.State;
import org.veridger.application.attachment.AttachmentKeys;
import org.veridger.application.attachment.AttachmentProperties;
import org.veridger.application.attachment.AttachmentReadService;
import org.veridger.application.attachment.AttachmentStore;
import org.veridger.application.attachment.PrivateFiles;
import org.veridger.application.attachment.SegmentCipher;
import org.veridger.domain.model.Attachment;
import org.veridger.domain.model.AttachmentFile;
import org.veridger.domain.model.AttachmentStorage;
import org.veridger.domain.value.AuthorId;
import org.veridger.domain.value.Sha256;
import org.veridger.domain.value.StorageKey;

/**
 * Verifies {@link AttachmentPanels} bundle-level overall state aggregation when files within a multi-file attachment
 * bundle have mixed availability (e.g., first file unavailable, subsequent file text or downloadable).
 */
class AttachmentPanelsUnitTest {

    private static final Instant NOW = Instant.parse("2026-11-27T00:00:00Z");

    @Test
    @DisplayName("ATA-PANEL-01: overall bundle state picks first available TEXT/DOWNLOAD file instead of masking with first UNAVAILABLE file")
    void mixedBundlePicksAvailableFileState(@TempDir Path work) {
        AttachmentProperties properties = new AttachmentProperties(work.resolve("att"), work.resolve("keys"), "k1",
                DataSize.ofMegabytes(64), DataSize.ofMegabytes(512), DataSize.ofGigabytes(10), 4,
                DataSize.ofMegabytes(64), Duration.ofMinutes(10), Duration.ofHours(24), Duration.ofMinutes(1));
        PrivateFiles files = new PrivateFiles();
        AttachmentKeys keys = new AttachmentKeys(properties, files, new SecureRandom());
        String keyId = keys.create();
        AttachmentStore store = new AttachmentStore(properties, files, keys, new SegmentCipher(new SecureRandom()));
        store.prepare();

        Attachment attachment = new Attachment("att-mixed", AuthorId.OWNER, NOW);
        AttachmentFile unavailableFirst = new AttachmentFile(
                "att-mixed", 0, "missing.txt", "text/plain", 5L, Sha256.ofBytes(new byte[] {1}));
        AttachmentFile emptyTextSecond = new AttachmentFile(
                "att-mixed", 1, "notes.txt", "text/plain", 0L, Sha256.ofBytes(new byte[0]));
        AttachmentFile binarySecond = new AttachmentFile(
                "att-mixed", 1, "archive.bin", "application/octet-stream", 10L, Sha256.ofBytes(new byte[] {2}));
        StorageKey dummyKey = StorageKey.of("00112233445566778899aabbccddeeff");
        AttachmentStorage secondStorage = new AttachmentStorage("att-mixed", 1, 0, dummyKey, keyId, NOW);

        AttachmentPanels textMixed = new AttachmentPanels(
                stubRead(attachment, List.of(unavailableFirst, emptyTextSecond), Map.of(1, secondStorage)),
                providerOf(store));
        Panel textPanel = textMixed.panelsOf(Map.of(1L, "att-mixed")).get(1L);
        assertThat(textPanel.state()).isEqualTo(State.TEXT);
        assertThat(textPanel.files()).extracting(AttachmentPanels.FilePanel::state)
                .containsExactly(State.UNAVAILABLE, State.TEXT);

        AttachmentPanels downloadMixed = new AttachmentPanels(
                stubRead(attachment, List.of(unavailableFirst, binarySecond), Map.of(1, secondStorage)),
                providerOf(store));
        Panel downloadPanel = downloadMixed.panelsOf(Map.of(2L, "att-mixed")).get(2L);
        assertThat(downloadPanel.state()).isEqualTo(State.DOWNLOAD);
        assertThat(downloadPanel.files()).extracting(AttachmentPanels.FilePanel::state)
                .containsExactly(State.UNAVAILABLE, State.DOWNLOAD);

        AttachmentPanels allUnavailable = new AttachmentPanels(
                stubRead(attachment, List.of(unavailableFirst), Map.of()),
                providerOf(store));
        Panel unavailablePanel = allUnavailable.panelsOf(Map.of(3L, "att-mixed")).get(3L);
        assertThat(unavailablePanel.state()).isEqualTo(State.UNAVAILABLE);

        AttachmentPanels notConfigured = new AttachmentPanels(
                stubRead(attachment, List.of(unavailableFirst), Map.of()),
                providerOf(null));
        Panel unconfiguredPanel = notConfigured.panelsOf(Map.of(4L, "att-mixed")).get(4L);
        assertThat(unconfiguredPanel.state()).isEqualTo(State.NOT_CONFIGURED);
    }

    private static AttachmentReadService stubRead(
            Attachment attachment, List<AttachmentFile> files, Map<Integer, AttachmentStorage> storages) {
        return new AttachmentReadService(null, null, null) {
            @Override
            public Optional<Attachment> find(String attachmentId) {
                return attachment.id().equals(attachmentId) ? Optional.of(attachment) : Optional.empty();
            }

            @Override
            public List<AttachmentFile> filesOf(String attachmentId) {
                return attachment.id().equals(attachmentId) ? files : List.of();
            }

            @Override
            public Optional<AttachmentStorage> currentStorage(String attachmentId, int seq) {
                return Optional.ofNullable(storages.get(seq));
            }
        };
    }

    private static ObjectProvider<AttachmentStore> providerOf(AttachmentStore value) {
        return new ObjectProvider<>() {
            @Override
            public AttachmentStore getObject(Object... args) {
                return value;
            }

            @Override
            public AttachmentStore getIfAvailable() {
                return value;
            }

            @Override
            public AttachmentStore getIfUnique() {
                return value;
            }

            @Override
            public AttachmentStore getObject() {
                return value;
            }

            @Override
            public Stream<AttachmentStore> stream() {
                return value == null ? Stream.empty() : Stream.of(value);
            }
        };
    }
}
