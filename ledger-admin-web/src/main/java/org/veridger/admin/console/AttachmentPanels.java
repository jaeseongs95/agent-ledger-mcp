// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.admin.console;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.veridger.application.attachment.AttachmentProperties;
import org.veridger.application.attachment.AttachmentReadService;
import org.veridger.application.attachment.AttachmentStore;
import org.veridger.domain.error.InfrastructureException;
import org.veridger.domain.error.LookupCode;
import org.veridger.domain.error.LookupException;
import org.veridger.domain.model.Attachment;
import org.veridger.domain.model.AttachmentFile;
import org.veridger.domain.model.AttachmentStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * What the owner sees of an attachment: on the page of a history row the metadata and, for a text attachment, the first 256 KiB
 * as text (the page shows it escaped, read only); every other attachment is offered as a download that this class streams from
 * {@link AttachmentStore} page by page, decrypted, without holding the file in memory. The attachment beans exist only when
 * {@code ledger.attachments.directory} is set; without them every panel says so and the download is 404. Nothing here, and nothing
 * it returns, holds a storage key or a path: the page and the headers carry the attachment id and the metadata only.
 */
@Component
public class AttachmentPanels {

    private static final Logger LOG = LoggerFactory.getLogger(AttachmentPanels.class);

    /** The text shown on a page is at most this many bytes of the file (the size of one chunk of the upload tools). */
    static final int TEXT_BYTES = AttachmentProperties.CHUNK_BYTES;

    /** How the page presents an attachment. */
    public enum State {
        /** A text attachment, its text shown escaped and read only. */
        TEXT,
        /** Any other attachment (or text that is not valid UTF-8): a download link. */
        DOWNLOAD,
        /** The attachments are not configured on this installation: no content. */
        NOT_CONFIGURED,
        /** The file is missing or does not pass its checks. */
        UNAVAILABLE,
        /** The history row names an attachment that has no row. */
        NOT_FOUND
    }

    /** One file inside an attachment bundle as the page shows it; {@code text} is empty unless the state is TEXT. */
    public record FilePanel(int seq, State state, String fileName, String mediaType, long sizeBytes, String sha256,
            String text, boolean truncated) {

        public boolean isText() {
            return state == State.TEXT;
        }

        public boolean isDownload() {
            return state == State.DOWNLOAD || state == State.TEXT;
        }
    }

    /** One attachment bundle as the page shows it. */
    public record Panel(String id, State state, String uploadedBy, Instant uploadedAt, List<FilePanel> files) {
    }

    /** What a download needs; the place of the file stays inside. */
    public static final class Download {

        private final AttachmentFile file;
        private final AttachmentStorage storage;

        private Download(AttachmentFile file, AttachmentStorage storage) {
            this.file = file;
            this.storage = storage;
        }

        public String fileName() {
            return file.fileName();
        }

        public long sizeBytes() {
            return file.sizeBytes();
        }
    }

    private final AttachmentReadService read;
    private final ObjectProvider<AttachmentStore> stores;

    public AttachmentPanels(AttachmentReadService read, ObjectProvider<AttachmentStore> stores) {
        this.read = read;
        this.stores = stores;
    }

    /** True when the attachments are configured on this installation. */
    public boolean configured() {
        return stores.getIfAvailable() != null;
    }

    /** The panels of the history rows that point to an attachment: the attachment id of each history id in, the panel out. */
    public Map<Long, Panel> panelsOf(Map<Long, String> attachmentIdsByHistoryId) {
        Map<Long, Panel> panels = new LinkedHashMap<>();
        Map<String, Panel> byAttachmentId = new LinkedHashMap<>();
        for (Map.Entry<Long, String> row : attachmentIdsByHistoryId.entrySet()) {
            Panel resolved = byAttachmentId.computeIfAbsent(row.getValue(), this::panel);
            panels.put(row.getKey(), resolved);
        }
        return panels;
    }

    private Panel panel(String attachmentId) {
        return read.find(attachmentId).map(attachment -> panel(attachment, stores.getIfAvailable()))
                .orElseGet(() -> new Panel(attachmentId, State.NOT_FOUND, "", null, List.of()));
    }

    private Panel panel(Attachment attachment, AttachmentStore store) {
        List<AttachmentFile> bundleFiles = read.filesOf(attachment.id());
        if (bundleFiles.isEmpty()) {
            State fallback = store == null ? State.NOT_CONFIGURED : State.UNAVAILABLE;
            return new Panel(attachment.id(), fallback, attachment.createdBy().value(), attachment.createdAt(),
                    List.of());
        }
        List<FilePanel> panels = new ArrayList<>(bundleFiles.size());
        for (AttachmentFile file : bundleFiles) {
            panels.add(filePanel(attachment.id(), file, store));
        }
        State overall = panels.stream()
                .map(FilePanel::state)
                .filter(s -> s == State.TEXT || s == State.DOWNLOAD)
                .findFirst()
                .orElse(panels.getFirst().state());
        return new Panel(attachment.id(), overall, attachment.createdBy().value(), attachment.createdAt(),
                List.copyOf(panels));
    }

    private FilePanel filePanel(String attachmentId, AttachmentFile file, AttachmentStore store) {
        if (store == null) {
            return ofFile(file, State.NOT_CONFIGURED, "", false);
        }
        return read.currentStorage(attachmentId, file.seq())
                .map(storage -> content(attachmentId, file, store, storage))
                .orElseGet(() -> ofFile(file, State.UNAVAILABLE, "", false));
    }

    private FilePanel content(String attachmentId, AttachmentFile file, AttachmentStore store, AttachmentStorage storage) {
        if (!isText(file.mediaType())) {
            return ofFile(file, State.DOWNLOAD, "", false);
        }
        if (file.sizeBytes() == 0) {
            return ofFile(file, State.TEXT, "", false);
        }
        boolean cut = file.sizeBytes() > TEXT_BYTES;
        try {
            AttachmentStore.Page page = store.read(storage.storageKey(), storage.keyId(), 0,
                    (int) Math.min(file.sizeBytes(), TEXT_BYTES));
            return decode(page.data(), cut).map(text -> ofFile(file, State.TEXT, text, cut))
                    .orElseGet(() -> ofFile(file, State.DOWNLOAD, "", false));
        } catch (InfrastructureException damaged) {
            LOG.warn("The attachment {} seq {} cannot be read: {}", attachmentId, file.seq(), damaged.code());
            return ofFile(file, State.UNAVAILABLE, "", false);
        }
    }

    private static FilePanel ofFile(AttachmentFile file, State state, String text, boolean truncated) {
        return new FilePanel(file.seq(), state, file.fileName(), file.mediaType(), file.sizeBytes(),
                file.contentSha256().hex(), text, truncated);
    }

    /** text/* and application/json (a parameter such as a charset does not matter). */
    static boolean isText(String mediaType) {
        String base = mediaType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return base.startsWith("text/") || "application/json".equals(base);
    }

    /**
     * Strict UTF-8: empty when the bytes are not valid. For a file that was cut at the limit a character that the cut split (up to
     * three bytes at the end) is dropped.
     */
    private static Optional<String> decode(byte[] bytes, boolean cut) {
        int drop = cut ? 4 : 1;
        for (int i = 0; i < drop; i++) {
            try {
                return Optional.of(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(Arrays.copyOf(bytes, bytes.length - i))).toString());
            } catch (CharacterCodingException notUtf8) {
                LOG.debug("The cut {} of the text is not valid UTF-8", i);
            }
        }
        return Optional.empty();
    }

    /**
     * The attachment file to download.
     *
     * @throws LookupException ARTIFACT_NOT_FOUND when the id is no attachment, when the file index is missing, or when attachments are not configured
     */
    public Download download(String attachmentId, int seq) {
        if (stores.getIfAvailable() == null || seq < 0) {
            throw notFound(attachmentId);
        }
        try {
            UUID.fromString(attachmentId);
        } catch (IllegalArgumentException notAnId) {
            throw notFound(attachmentId);
        }
        read.find(attachmentId).orElseThrow(() -> notFound(attachmentId));
        AttachmentFile file = read.fileAt(attachmentId, seq).orElseThrow(() -> notFound(attachmentId));
        AttachmentStorage storage = read.currentStorage(attachmentId, seq).orElseThrow(() -> notFound(attachmentId));
        return new Download(file, storage);
    }

    /**
     * Streams the decrypted body to {@code out} in a single linear pass over one open file channel (every segment tag
     * and the trailing end mark are verified, so a damaged file fails immediately rather than writing wrong bytes).
     *
     * @throws IOException when the stream cannot be written or the decrypted size does not match the recorded size
     */
    public void copy(Download download, OutputStream out) throws IOException {
        AttachmentStore store = stores.getIfAvailable();
        if (store == null) {
            throw notFound(download.file.attachmentId());
        }
        long size = download.file.sizeBytes();
        long written = store.stream(download.storage.storageKey(), download.storage.keyId(), out);
        if (written != size) {
            throw new IOException("the attachment " + download.file.attachmentId() + " ended early");
        }
    }

    private static LookupException notFound(String attachmentId) {
        return new LookupException(LookupCode.ARTIFACT_NOT_FOUND, List.of(attachmentId));
    }
}
