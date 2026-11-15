package org.pgledger.application.support;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.pgledger.application.attachment.AttachmentFlow;
import org.pgledger.application.attachment.AttachmentProperties;
import org.pgledger.application.attachment.StorageKeys;
import org.pgledger.application.attachment.UploadCommands.Begin;
import org.pgledger.application.attachment.UploadCommands.Begun;
import org.pgledger.application.attachment.UploadCommands.Chunk;
import org.pgledger.application.attachment.UploadCommands.Finished;
import org.pgledger.application.attachment.UploadCommands.ReadPage;
import org.pgledger.application.attachment.UploadCommands.Received;
import org.pgledger.application.attachment.UploadService;
import org.pgledger.application.gate.WriteGate;
import org.pgledger.database.repository.AttachmentFileRepository;
import org.pgledger.database.repository.AttachmentRepository;
import org.pgledger.database.repository.UploadStagingRepository;
import org.pgledger.domain.model.AttachmentFile;
import org.pgledger.domain.model.UploadStaging;
import org.pgledger.domain.value.Sha256;
import org.pgledger.domain.value.StorageKey;
import org.springframework.util.IdGenerator;

/** The commands and the steps of an upload as the tests send them: a body, its chunks, the calls of the flow and where the files are. */
public final class Uploads {

    public static final String NAME = "report.md";
    public static final String MEDIA_TYPE = "text/markdown";

    private Uploads() {
    }

    /** {@code length} bytes that depend on the seed only. */
    public static byte[] body(int length, long seed) {
        byte[] bytes = new byte[length];
        new Random(seed).nextBytes(bytes);
        return bytes;
    }

    public static String sha(byte[] bytes) {
        return Sha256.ofBytes(bytes).hex();
    }

    /** A begin for this body with an operation key of its own and no purpose. */
    public static Begin begin(byte[] body) {
        return begin("op-" + UUID.randomUUID(), body);
    }

    public static Begin begin(String operationKey, byte[] body) {
        return new Begin(operationKey, NAME, MEDIA_TYPE, body.length, sha(body), null);
    }

    public static Chunk chunk(String uploadId, long sequence, byte[] part) {
        return new Chunk(uploadId, sequence, Base64.getEncoder().encodeToString(part), sha(part));
    }

    /** The body cut into parts of the given sizes; what is left after the last size is one more part. */
    public static List<byte[]> parts(byte[] body, int... sizes) {
        List<byte[]> parts = new ArrayList<>();
        int position = 0;
        for (int size : sizes) {
            parts.add(Arrays.copyOfRange(body, position, position + size));
            position += size;
        }
        if (position < body.length) {
            parts.add(Arrays.copyOfRange(body, position, body.length));
        }
        return parts;
    }

    public static Received send(AttachmentFlow flow, String actor, String uploadId, long sequence, byte[] part) {
        return flow.chunk(actor, chunk(uploadId, sequence, part));
    }

    /** Begin and every part as a chunk; the upload id. */
    public static String sendAll(AttachmentFlow flow, String actor, byte[] body, int... sizes) {
        Begun begun = flow.begin(actor, begin(body));
        sendParts(flow, actor, begun.uploadId(), parts(body, sizes), 0);
        return begun.uploadId();
    }

    /** The chunks from the sequence on, one per part. */
    public static void sendParts(AttachmentFlow flow, String actor, String uploadId, List<byte[]> parts, long firstSequence) {
        long sequence = firstSequence;
        for (byte[] part : parts) {
            send(flow, actor, uploadId, sequence, part);
            sequence++;
        }
    }

    /** A whole upload: begin, the chunks, finish. */
    public static Finished upload(AttachmentFlow flow, String actor, byte[] body, int... sizes) {
        return flow.finish(actor, sendAll(flow, actor, body, sizes));
    }

    /** The body of an attachment read in pages of {@code pageBytes}. */
    public static byte[] readAll(AttachmentFlow flow, String actor, String attachmentId, int pageBytes) {
        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        long offset = 0;
        ReadPage page = flow.read(actor, attachmentId, offset, pageBytes);
        while (true) {
            joined.writeBytes(page.data());
            offset += page.data().length;
            if (page.data().length == 0 || offset >= page.totalBytes()) {
                return joined.toByteArray();
            }
            page = flow.read(actor, attachmentId, offset, pageBytes);
        }
    }

    /**
     * The upload service as it is when the owner has set {@code active-key-id} to another key: the same components with the settings of
     * the context and the new active key. It is no bean, so its methods run in a transaction only when the caller opens one
     * ({@link LedgerAppContext#tx}).
     */
    public static UploadService serviceWithActiveKey(LedgerAppContext ctx, String activeKeyId) {
        AttachmentProperties current = ctx.bean(AttachmentProperties.class);
        AttachmentProperties switched = new AttachmentProperties(current.directory(), current.keyDirectory(), activeKeyId,
                current.maxFileBytes(), current.stagingBudgetBytes(), current.attachmentBudgetBytes(),
                current.maxOpenPerOwner(), current.uploadTtl(), current.orphanGrace(), current.reaperInterval());
        return new UploadService(ctx.bean(WriteGate.class), ctx.bean(UploadStagingRepository.class),
                ctx.bean(AttachmentRepository.class), ctx.bean(AttachmentFileRepository.class), switched,
                ctx.bean(IdGenerator.class), ctx.bean(StorageKeys.class), ctx.bean(Clock.class));
    }

    /** A whole upload whose begin was made after {@code active-key-id} was set to {@code keyId}: the file is written with that key. */
    public static Finished uploadWithKey(LedgerAppContext ctx, AttachmentFlow flow, String actor, String keyId, byte[] body,
            int... sizes) {
        UploadService switched = serviceWithActiveKey(ctx, keyId);
        Begun begun = ctx.tx(() -> switched.begin(actor, begin(body)));
        sendParts(flow, actor, begun.uploadId(), parts(body, sizes), 0);
        return flow.finish(actor, begun.uploadId());
    }

    public static UploadStaging row(LedgerAppContext ctx, String uploadId) {
        return ctx.bean(UploadStagingRepository.class).findById(uploadId).orElseThrow();
    }

    public static AttachmentFile currentFile(LedgerAppContext ctx, String attachmentId) {
        return ctx.bean(AttachmentFileRepository.class).currentOf(attachmentId).orElseThrow();
    }

    /** Where the file of the key is on the disk of the context. */
    public static Path fileOf(LedgerAppContext ctx, StorageKey key) {
        return ctx.bean(AttachmentProperties.class).directory().resolve(key.folder()).resolve(key.hex());
    }

    /** Where the file of the key is when the check has moved it away. */
    public static Path quarantinedFileOf(LedgerAppContext ctx, StorageKey key) {
        return ctx.bean(AttachmentProperties.class).directory().resolve("orphans").resolve(key.hex());
    }
}
