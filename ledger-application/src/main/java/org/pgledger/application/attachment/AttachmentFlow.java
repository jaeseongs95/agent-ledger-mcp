package org.pgledger.application.attachment;

import java.util.Base64;
import java.util.List;
import java.util.Objects;
import org.pgledger.application.attachment.UploadCommands.Begin;
import org.pgledger.application.attachment.UploadCommands.Begun;
import org.pgledger.application.attachment.UploadCommands.Chunk;
import org.pgledger.application.attachment.UploadCommands.Finished;
import org.pgledger.application.attachment.UploadCommands.ReadPage;
import org.pgledger.application.attachment.UploadCommands.Received;
import org.pgledger.application.attachment.UploadCommands.Status;
import org.pgledger.domain.error.ConflictCode;
import org.pgledger.domain.error.ConflictException;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationReason;
import org.pgledger.domain.model.UploadStaging;
import org.pgledger.domain.value.Sha256;
import org.springframework.stereotype.Component;

/**
 * The entry point of the five upload operations: the place where the file work is done between the service methods
 * ({@link UploadService}). It opens no transaction (principle 4.4: file I/O is outside it): a chunk is decoded and its hash checked,
 * the service says whether it is a repeat or the next one, the store appends it under the lock, and the service records it with one
 * conditional UPDATE; a finish verifies and closes the file and then the service marks the upload and inserts the attachment.
 * A failure between the two service methods leaves a tail in the file that the next request of the same chunk, or the finish,
 * recognises (design 4.4); nothing here retries.
 */
@Component
@ConditionalOnAttachments
public class AttachmentFlow {

    /** The longest base64 text of one chunk: {@code ceil(262144 / 3) * 4} characters. */
    private static final int MAX_BASE64 = (AttachmentProperties.CHUNK_BYTES + 2) / 3 * 4;

    private final UploadService service;
    private final AttachmentStore store;

    public AttachmentFlow(UploadService service, AttachmentStore store) {
        this.service = service;
        this.store = store;
    }

    /** {@code artifact-begin}. */
    public Begun begin(String actor, Begin command) {
        // the gate first, then the free space of the disk for the declared size (file I/O, so outside the transaction of begin)
        service.admit(actor);
        if (command.size() > 0) {
            store.requireDiskSpace(command.size());
        }
        return service.begin(actor, command);
    }

    /** {@code artifact-chunk}. */
    public Received chunk(String actor, Chunk command) {
        if (command.sequence() < 0) {
            throw ValidationException.invalid("sequence", ValidationReason.MUST_NOT_BE_NEGATIVE);
        }
        byte[] plain = decode(command.data());
        Sha256 chunkSha = chunkSha(command.chunkSha256());
        if (!Objects.equals(Sha256.ofBytes(plain), chunkSha)) {
            throw new ConflictException(ConflictCode.CHUNK_HASH_MISMATCH, List.of());
        }
        return switch (service.planChunk(actor, command.uploadId(), command.sequence(), plain.length, chunkSha)) {
            case UploadService.ChunkPlan.AlreadyHeld held -> held.received();
            case UploadService.ChunkPlan.Append append -> {
                UploadStaging row = append.row();
                long stored = store.append(row.storageKey(), row.keyId(), row.storedBytes(), row.nextSequence(), plain,
                        chunkSha);
                yield service.recordChunk(actor, row, stored, plain.length, chunkSha);
            }
        };
    }

    /** {@code artifact-finish}. */
    public Finished finish(String actor, String uploadId) {
        return switch (service.planFinish(actor, uploadId)) {
            case UploadService.FinishPlan.Done done -> done.finished();
            case UploadService.FinishPlan.Close close -> {
                UploadStaging row = close.row();
                long length = store.finish(row.storageKey(), row.keyId(), row.storedBytes(), row.nextSequence(),
                        row.declaredSize(), row.declaredSha256());
                yield service.completeUpload(actor, row, length);
            }
        };
    }

    /** {@code artifact-status}. */
    public Status status(String actor, String id) {
        return service.status(actor, id);
    }

    /** {@code artifact-read}: a page of the body, opened and checked. */
    public ReadPage read(String actor, String attachmentId, long offsetBytes, long maxBytes) {
        UploadService.Source source = service.planRead(actor, attachmentId, offsetBytes, maxBytes);
        AttachmentStore.Page page = store.read(source.file().storageKey(), source.file().keyId(), offsetBytes,
                (int) maxBytes);
        return new ReadPage(attachmentId, page.offset(), page.totalSize(), page.data(), source.attachment().mediaType(),
                source.attachment().fileName());
    }

    private static byte[] decode(String data) {
        if (data == null || data.isEmpty() || data.length() > MAX_BASE64) {
            throw ValidationException.invalid("data", ValidationReason.MUST_BE_CHARS_RANGE, "1",
                    Integer.toString(MAX_BASE64));
        }
        try {
            return Base64.getDecoder().decode(data);
        } catch (IllegalArgumentException notBase64) {
            throw ValidationException.invalid("data", ValidationReason.WRONG_TYPE, notBase64, "base64 text");
        }
    }

    private static Sha256 chunkSha(String text) {
        try {
            return Sha256.of(text);
        } catch (ValidationException invalid) {
            throw ValidationException.invalid("chunkSha256", ValidationReason.MUST_BE_LOWER_CASE_HEX, invalid, "64");
        }
    }
}
