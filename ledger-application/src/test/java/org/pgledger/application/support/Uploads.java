// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.application.support;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.pgledger.application.attachment.AttachmentFlow;
import org.pgledger.application.attachment.AttachmentProperties;
import org.pgledger.application.attachment.StorageKeys;
import org.pgledger.application.attachment.UploadCommands.Begin;
import org.pgledger.application.attachment.UploadCommands.BeginFile;
import org.pgledger.application.attachment.UploadCommands.Begun;
import org.pgledger.application.attachment.UploadCommands.Chunk;
import org.pgledger.application.attachment.UploadCommands.Finished;
import org.pgledger.application.attachment.UploadCommands.Page;
import org.pgledger.application.attachment.UploadCommands.Received;
import org.pgledger.application.attachment.UploadService;
import org.pgledger.application.gate.WriteGate;
import org.pgledger.database.repository.AttachmentFileRepository;
import org.pgledger.database.repository.AttachmentRepository;
import org.pgledger.database.repository.AttachmentStorageRepository;
import org.pgledger.database.repository.UploadStagingRepository;
import org.pgledger.domain.model.AttachmentStorage;
import org.pgledger.domain.model.UploadStaging;
import org.pgledger.domain.value.Sha256;
import org.pgledger.domain.value.StorageKey;

/** Helpers for building bodies, splitting chunks, and driving {@link AttachmentFlow} in tests. */
public final class Uploads {

    public static final String NAME = "report.md";
    public static final String MEDIA_TYPE = "text/markdown";

    private Uploads() {
    }

    public static byte[] body(int size, long seed) {
        byte[] bytes = new byte[size];
        long state = seed * 6364136223846793005L + 1442695040888963407L;
        for (int i = 0; i < size; i++) {
            state = state * 6364136223846793005L + 1L;
            bytes[i] = (byte) (state >>> 56);
        }
        return bytes;
    }

    public static String sha(byte[] body) {
        return Sha256.ofBytes(body).hex();
    }

    public static BeginFile beginFile(String fileName, String mediaType, byte[] body) {
        return new BeginFile(fileName, mediaType, body.length, sha(body));
    }

    public static BeginFile beginFile(byte[] body) {
        return beginFile(NAME, MEDIA_TYPE, body);
    }

    public static Begin begin(byte[] body) {
        return begin("op-" + UUID.randomUUID(), body);
    }

    public static Begin begin(String operationKey, byte[] body) {
        return new Begin(operationKey, List.of(beginFile(body)), null);
    }

    public static Begin beginBundle(String operationKey, List<BeginFile> files) {
        return new Begin(operationKey, files, null);
    }

    public static Chunk chunk(String uploadId, long sequence, byte[] part) {
        return new Chunk(uploadId, sequence, Base64.getEncoder().encodeToString(part), sha(part));
    }

    public static List<byte[]> parts(byte[] body, int... sizes) {
        List<byte[]> result = new ArrayList<>();
        int pos = 0;
        int idx = 0;
        while (pos < body.length) {
            int size;
            if (idx < sizes.length) {
                size = sizes[idx++];
            } else if (sizes.length == 1) {
                size = sizes[0];
            } else {
                size = body.length - pos;
            }
            int end = Math.min(body.length, pos + size);
            result.add(Arrays.copyOfRange(body, pos, end));
            pos = end;
        }
        return result;
    }

    public static Received send(AttachmentFlow flow, String actor, String uploadId, long sequence, byte[] part) {
        return flow.chunk(actor, chunk(uploadId, sequence, part));
    }

    public static void sendParts(AttachmentFlow flow, String actor, String uploadId, List<byte[]> parts, long startSequence) {
        long seq = startSequence;
        for (byte[] part : parts) {
            send(flow, actor, uploadId, seq++, part);
        }
    }

    public static Begun sendAll(AttachmentFlow flow, String actor, byte[] body, int... chunkSizes) {
        Begun begun = flow.begin(actor, begin(body));
        sendParts(flow, actor, begun.files().getFirst().uploadId(), parts(body, chunkSizes), 0);
        return begun;
    }

    public static Finished upload(AttachmentFlow flow, String actor, byte[] body) {
        return upload(flow, actor, body, AttachmentProperties.CHUNK_BYTES);
    }

    public static Finished upload(AttachmentFlow flow, String actor, byte[] body, int chunkSize) {
        Begun begun = sendAll(flow, actor, body, chunkSize);
        return flow.finish(actor, begun.attachmentId());
    }

    public static byte[] readAll(AttachmentFlow flow, String actor, String attachmentId, int pageSize) {
        return readAll(flow, actor, attachmentId, 0, pageSize);
    }

    public static byte[] readAll(AttachmentFlow flow, String actor, String attachmentId, int seq, int pageSize) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long offset = 0;
        while (true) {
            Page page = flow.read(actor, attachmentId, seq, offset, pageSize);
            out.writeBytes(page.data());
            offset += page.size();
            if (offset >= page.totalBytes() || page.size() == 0) {
                break;
            }
        }
        return out.toByteArray();
    }

    public static UploadStaging row(LedgerAppContext ctx, String uploadId) {
        return ctx.bean(UploadStagingRepository.class).findById(uploadId).orElseThrow();
    }

    public static AttachmentStorage currentStorage(LedgerAppContext ctx, String attachmentId, int seq) {
        return ctx.bean(AttachmentStorageRepository.class).current(attachmentId, seq).orElseThrow();
    }

    public static AttachmentStorage currentStorage(LedgerAppContext ctx, String attachmentId) {
        return currentStorage(ctx, attachmentId, 0);
    }

    public static Path fileOf(LedgerAppContext ctx, StorageKey key) {
        return ctx.bean(AttachmentProperties.class).directory().resolve(key.folder()).resolve(key.hex());
    }

    public static Path quarantinedFileOf(LedgerAppContext ctx, StorageKey key) {
        return ctx.bean(AttachmentProperties.class).directory().resolve("orphans").resolve(key.hex());
    }

    public static UploadService serviceWithActiveKey(LedgerAppContext ctx, String keyId) {
        AttachmentProperties base = ctx.bean(AttachmentProperties.class);
        AttachmentProperties switched = new AttachmentProperties(
                base.directory(),
                base.keyDirectory(),
                keyId,
                base.maxFileBytes(),
                base.stagingBudgetBytes(),
                base.attachmentBudgetBytes(),
                base.maxOpenPerOwner(),
                base.diskMarginBytes(),
                base.uploadTtl(),
                base.orphanGrace(),
                base.reaperInterval());
        return new UploadService(
                ctx.bean(WriteGate.class),
                ctx.bean(UploadStagingRepository.class),
                ctx.bean(AttachmentRepository.class),
                ctx.bean(AttachmentFileRepository.class),
                ctx.bean(AttachmentStorageRepository.class),
                switched,
                ctx.bean(StorageKeys.class),
                ctx.bean(Clock.class));
    }

    public static Finished uploadWithKey(LedgerAppContext ctx, AttachmentFlow flow, String actor, String keyId,
            byte[] body, int chunkSize) {
        UploadService switched = serviceWithActiveKey(ctx, keyId);
        Begun begun = ctx.tx(() -> switched.begin(actor, begin(body)));
        sendParts(flow, actor, begun.files().getFirst().uploadId(), parts(body, chunkSize), 0);
        return flow.finish(actor, begun.attachmentId());
    }
}
