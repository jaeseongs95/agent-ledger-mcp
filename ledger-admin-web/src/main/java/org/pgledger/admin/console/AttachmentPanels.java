package org.pgledger.admin.console;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.pgledger.application.attachment.AttachmentProperties;
import org.pgledger.application.attachment.AttachmentReadService;
import org.pgledger.application.attachment.AttachmentStore;
import org.pgledger.domain.error.InfrastructureException;
import org.pgledger.domain.error.LookupCode;
import org.pgledger.domain.error.LookupException;
import org.pgledger.domain.model.Attachment;
import org.pgledger.domain.model.AttachmentFile;
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

    /** A download reads the file in pages of this size: each page opens the segments it covers, so larger pages mean fewer passes. */
    private static final int DOWNLOAD_PAGE = 1 << 20;

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

    /** One attachment as the page shows it; {@code text} is empty unless the state is TEXT. */
    public record Panel(String id, State state, String fileName, String mediaType, long sizeBytes, String sha256,
            String uploadedBy, Instant uploadedAt, String text, boolean truncated) {

        public boolean isText() {
            return state == State.TEXT;
        }

        public boolean isDownload() {
            return state == State.DOWNLOAD || state == State.TEXT;
        }
    }

    /** What a download needs; the place of the file stays inside. */
    public static final class Download {

        private final Attachment attachment;
        private final AttachmentFile file;

        private Download(Attachment attachment, AttachmentFile file) {
            this.attachment = attachment;
            this.file = file;
        }

        public String fileName() {
            return attachment.fileName();
        }

        public long sizeBytes() {
            return attachment.sizeBytes();
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
        for (Map.Entry<Long, String> row : attachmentIdsByHistoryId.entrySet()) {
            panels.put(row.getKey(), panel(row.getValue()));
        }
        return panels;
    }

    private Panel panel(String attachmentId) {
        return read.find(attachmentId).map(attachment -> panel(attachment, stores.getIfAvailable()))
                .orElseGet(() -> new Panel(attachmentId, State.NOT_FOUND, "", "", 0, "", "", null, "", false));
    }

    private Panel panel(Attachment attachment, AttachmentStore store) {
        if (store == null) {
            return of(attachment, State.NOT_CONFIGURED, "", false);
        }
        return read.currentFile(attachment.id()).map(file -> content(attachment, store, file))
                .orElseGet(() -> of(attachment, State.UNAVAILABLE, "", false));
    }

    private Panel content(Attachment attachment, AttachmentStore store, AttachmentFile file) {
        if (!isText(attachment.mediaType())) {
            return of(attachment, State.DOWNLOAD, "", false);
        }
        if (attachment.sizeBytes() == 0) {
            return of(attachment, State.TEXT, "", false);
        }
        boolean cut = attachment.sizeBytes() > TEXT_BYTES;
        try {
            AttachmentStore.Page page = store.read(file.storageKey(), file.keyId(), 0,
                    (int) Math.min(attachment.sizeBytes(), TEXT_BYTES));
            return decode(page.data(), cut).map(text -> of(attachment, State.TEXT, text, cut))
                    .orElseGet(() -> of(attachment, State.DOWNLOAD, "", false));
        } catch (InfrastructureException damaged) {
            LOG.warn("The attachment {} cannot be read: {}", attachment.id(), damaged.code());
            return of(attachment, State.UNAVAILABLE, "", false);
        }
    }

    private static Panel of(Attachment attachment, State state, String text, boolean truncated) {
        return new Panel(attachment.id(), state, attachment.fileName(), attachment.mediaType(), attachment.sizeBytes(),
                attachment.contentSha256().hex(), attachment.createdBy().value(), attachment.createdAt(), text, truncated);
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
     * The attachment to download.
     *
     * @throws LookupException ARTIFACT_NOT_FOUND when the id is no attachment, when it has no file, or when the attachments are not configured
     */
    public Download download(String attachmentId) {
        if (stores.getIfAvailable() == null) {
            throw notFound(attachmentId);
        }
        try {
            UUID.fromString(attachmentId);
        } catch (IllegalArgumentException notAnId) {
            throw notFound(attachmentId);
        }
        Attachment attachment = read.find(attachmentId).orElseThrow(() -> notFound(attachmentId));
        AttachmentFile file = read.currentFile(attachmentId).orElseThrow(() -> notFound(attachmentId));
        return new Download(attachment, file);
    }

    /**
     * Writes the decrypted body to the stream, one page at a time (every page is opened and its tags are checked, so a damaged
     * file ends the stream with an exception, never with wrong bytes).
     *
     * @throws IOException when the stream cannot be written
     */
    public void copy(Download download, OutputStream out) throws IOException {
        AttachmentStore store = stores.getIfAvailable();
        if (store == null) {
            throw notFound(download.attachment.id());
        }
        long offset = 0;
        long size = download.attachment.sizeBytes();
        while (offset < size) {
            AttachmentStore.Page page = store.read(download.file.storageKey(), download.file.keyId(), offset,
                    (int) Math.min(DOWNLOAD_PAGE, size - offset));
            if (page.data().length == 0) {
                throw new IOException("the attachment " + download.attachment.id() + " ended early");
            }
            out.write(page.data());
            offset += page.data().length;
        }
    }

    private static LookupException notFound(String attachmentId) {
        return new LookupException(LookupCode.ARTIFACT_NOT_FOUND, List.of(attachmentId));
    }
}
