// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.application.attachment;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import org.pgledger.application.attachment.UploadCommands.Begin;
import org.pgledger.application.attachment.UploadCommands.BeginFile;
import org.pgledger.application.attachment.UploadCommands.Begun;
import org.pgledger.application.attachment.UploadCommands.Chunk;
import org.pgledger.application.attachment.UploadCommands.Finished;
import org.pgledger.application.attachment.UploadCommands.Page;
import org.pgledger.application.attachment.UploadCommands.Received;
import org.pgledger.application.attachment.UploadCommands.Status;
import org.pgledger.application.attachment.UploadService.ChunkPlan;
import org.pgledger.application.attachment.UploadService.ClosedFile;
import org.pgledger.application.attachment.UploadService.FileView;
import org.pgledger.application.attachment.UploadService.FinishPlan;
import org.pgledger.domain.error.ConflictCode;
import org.pgledger.domain.error.ConflictException;
import org.pgledger.domain.error.TrustCode;
import org.pgledger.domain.error.TrustException;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationReason;
import org.pgledger.domain.model.UploadStaging;
import org.pgledger.domain.value.Sha256;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * Orchestration entry for bundle uploads and reads: opens no transaction, coordinates {@link UploadService} and
 * {@link AttachmentStore} outside transactions (design 6).
 */
@Component
@ConditionalOnAttachments
public class AttachmentFlow {

    private static final int MAX_BASE64_CHARS = (AttachmentProperties.CHUNK_BYTES + 2) / 3 * 4;

    private final UploadService service;
    private final AttachmentStore store;

    public AttachmentFlow(UploadService service, AttachmentStore store) {
        this.service = service;
        this.store = store;
    }

    public Begun begin(String actor, Begin command) {
        service.admit(actor);
        long declaredBytes = 0L;
        if (command != null && command.files() != null) {
            for (BeginFile file : command.files()) {
                if (file != null && file.size() > 0) {
                    declaredBytes = declaredBytes > Long.MAX_VALUE - file.size()
                            ? Long.MAX_VALUE
                            : declaredBytes + file.size();
                }
            }
        }
        store.requireDiskSpace(declaredBytes);
        try {
            return service.begin(actor, command);
        } catch (DuplicateKeyException race) {
            return service.resolveDuplicateBegin(actor, command);
        }
    }

    /**
     * Uploads one chunk. {@code actor} may be null when a standalone upload client presents {@code uploadId} as a
     * capability without a host session; when {@code actor} is present, ownership of the upload is enforced.
     */
    public Received chunk(String actor, Chunk command) {
        if (command == null) {
            throw ValidationException.invalid("chunk", ValidationReason.REQUIRED);
        }
        byte[] plain = decodeChunk(command.data());
        Sha256 declaredChunkSha = parseChunkSha(command.chunkSha256());
        if (!Objects.equals(Sha256.ofBytes(plain), declaredChunkSha)) {
            throw new ConflictException(ConflictCode.CHUNK_HASH_MISMATCH, List.of());
        }
        return switch (service.planChunk(actor, command.uploadId(), command.sequence(), plain.length, declaredChunkSha)) {
            case ChunkPlan.AlreadyApplied applied -> applied.received();
            case ChunkPlan.Append append -> {
                UploadStaging row = append.row();
                long storedBytes = store.append(row.storageKey(), row.keyId(), row.storedBytes(),
                        command.sequence(), plain, declaredChunkSha);
                yield service.recordChunk(actor, row, storedBytes, plain.length, declaredChunkSha);
            }
        };
    }

    /** Closes and finishes every file of the bundle {@code attachmentId}, committing all bundle rows atomically. */
    public Finished finish(String actor, String attachmentId) {
        return switch (service.planFinish(actor, attachmentId)) {
            case FinishPlan.Done done -> done.finished();
            case FinishPlan.Close close -> {
                List<ClosedFile> closed = new ArrayList<>(close.rows().size());
                for (UploadStaging row : close.rows()) {
                    long storedBytes = store.finish(row.storageKey(), row.keyId(), row.storedBytes(),
                            row.nextSequence(), row.declaredSize(), row.declaredSha256());
                    closed.add(new ClosedFile(row.id(), storedBytes));
                }
                yield service.completeUpload(actor, attachmentId, List.copyOf(closed));
            }
        };
    }

    /** Upload status by {@code uploadId} ({@code actor} may be null when called via uploadId capability). */
    public Status uploadStatus(String actor, String uploadId) {
        return service.uploadStatus(actor, uploadId);
    }

    /** Attachment bundle status by {@code attachmentId} (requires a session actor). */
    public Status attachmentStatus(String actor, String attachmentId) {
        return service.attachmentStatus(actor, attachmentId);
    }

    /** Dispatches {@code artifact-status} on {@code uploadId} XOR {@code attachmentId}. */
    public Status status(String actor, String uploadId, String attachmentId) {
        boolean hasUpload = uploadId != null && !uploadId.isBlank();
        boolean hasAttachment = attachmentId != null && !attachmentId.isBlank();
        if (hasUpload == hasAttachment) {
            throw ValidationException.invalid("uploadId", ValidationReason.MUST_BE_EXACTLY_ONE_OF, "uploadId",
                    "attachmentId");
        }
        return hasUpload ? uploadStatus(actor, uploadId) : attachmentStatus(actor, attachmentId);
    }

    public Page read(String actor, String attachmentId, int seq, long offsetBytes, int maxBytes) {
        if (offsetBytes < 0) {
            throw ValidationException.invalid("offsetBytes", ValidationReason.MUST_NOT_BE_NEGATIVE);
        }
        if (maxBytes < 1 || maxBytes > AttachmentProperties.CHUNK_BYTES) {
            throw ValidationException.invalid("maxBytes", ValidationReason.OUT_OF_RANGE, "1",
                    Integer.toString(AttachmentProperties.CHUNK_BYTES));
        }
        FileView view = service.fileView(actor, attachmentId, seq);
        AttachmentStore.Page page = store.read(view.current().storageKey(), view.current().keyId(), offsetBytes, maxBytes);
        return new Page(
                view.attachment().id(),
                view.file().seq(),
                offsetBytes,
                page.totalSize(),
                page.data().length,
                page.data(),
                view.file().mediaType(),
                view.file().fileName());
    }

    private static byte[] decodeChunk(String data) {
        if (data == null || data.length() > MAX_BASE64_CHARS) {
            throw ValidationException.invalid("data", ValidationReason.MUST_BE_AT_MOST_CHARS,
                    Integer.toString(MAX_BASE64_CHARS));
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(data);
        } catch (IllegalArgumentException invalidBase64) {
            throw ValidationException.invalid("data", ValidationReason.MUST_MATCH, invalidBase64, "base64");
        }
        if (decoded.length < 1 || decoded.length > AttachmentProperties.CHUNK_BYTES) {
            throw ValidationException.invalid("data", ValidationReason.OUT_OF_RANGE, "1",
                    Integer.toString(AttachmentProperties.CHUNK_BYTES));
        }
        return decoded;
    }

    private static Sha256 parseChunkSha(String text) {
        try {
            return Sha256.of(text);
        } catch (ValidationException invalid) {
            throw ValidationException.invalid("chunkSha256", ValidationReason.MUST_BE_LOWER_CASE_HEX, invalid, "64");
        }
    }
}
