package org.pgledger.application.attachment;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.pgledger.application.attachment.UploadCommands.Begin;
import org.pgledger.application.attachment.UploadCommands.Begun;
import org.pgledger.application.attachment.UploadCommands.Finished;
import org.pgledger.application.attachment.UploadCommands.Received;
import org.pgledger.application.attachment.UploadCommands.Status;
import org.pgledger.application.gate.GateSession;
import org.pgledger.application.gate.WriteGate;
import org.pgledger.database.repository.AttachmentFileRepository;
import org.pgledger.database.repository.AttachmentRepository;
import org.pgledger.database.repository.UploadStagingRepository;
import org.pgledger.domain.error.ConflictCode;
import org.pgledger.domain.error.ConflictException;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;
import org.pgledger.domain.error.LookupCode;
import org.pgledger.domain.error.LookupException;
import org.pgledger.domain.error.ValidationCode;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationReason;
import org.pgledger.domain.model.Attachment;
import org.pgledger.domain.model.AttachmentFile;
import org.pgledger.domain.model.UploadStaging;
import org.pgledger.domain.rule.Channel;
import org.pgledger.domain.rule.UploadState;
import org.pgledger.domain.value.AuthorId;
import org.pgledger.domain.value.Sha256;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.IdGenerator;

/**
 * The database half of the five upload operations. Every method is one use case and one transaction (principle 4.4): it opens the
 * gate (host attestation, live key, at least one live grant), reads and writes the rows, and does no file I/O; the file work
 * between two methods is done by the entry point, {@link AttachmentFlow}. A race is decided by the affected rows of one conditional
 * statement, nothing is locked by hand and nothing is retried; the begin of an operation key is decided by the unique key
 * ({@code UNIQUE(owner, operation_key)}), and the request receipt is not used for these operations, so there is one decision point.
 *
 * <p>The id of the attachment is the id of the upload: a finish that is repeated finds its attachment by the same id.
 */
@Service
@Transactional
@ConditionalOnAttachments
public class UploadService {

    /** What {@link #planChunk} decided: the chunk was received before (idempotent), or the entry point appends it. */
    public sealed interface ChunkPlan {

        /** The same chunk was recorded already: its answer. */
        record AlreadyHeld(Received received) implements ChunkPlan {
        }

        /** The chunk is the next one: the file work follows, for this row. */
        record Append(UploadStaging row) implements ChunkPlan {
        }
    }

    /** What {@link #planFinish} decided: the attachment exists already, or the entry point closes the file. */
    public sealed interface FinishPlan {

        /** The upload was finished before: its attachment. */
        record Done(Finished finished) implements FinishPlan {
        }

        /** The file is to be verified and closed, for this row. */
        record Close(UploadStaging row) implements FinishPlan {
        }
    }

    /** An attachment with the file that holds its body now (its latest row of attachment_file). */
    public record Source(Attachment attachment, AttachmentFile file) {
    }

    private static final String EVIDENCE = "evidence";
    private static final Pattern FILE_NAME_FORBIDDEN = Pattern.compile("[/\\\\\\p{Cntrl}]");
    private static final Pattern MEDIA_TYPE = Pattern
            .compile("[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]*/[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]*(;[ -~]*)?");

    private final WriteGate gate;
    private final UploadStagingRepository uploads;
    private final AttachmentRepository attachments;
    private final AttachmentFileRepository files;
    private final AttachmentProperties properties;
    private final IdGenerator ids;
    private final StorageKeys storageKeys;
    private final Clock clock;

    public UploadService(WriteGate gate, UploadStagingRepository uploads, AttachmentRepository attachments,
            AttachmentFileRepository files, AttachmentProperties properties, IdGenerator ids, StorageKeys storageKeys,
            Clock clock) {
        this.gate = gate;
        this.uploads = uploads;
        this.attachments = attachments;
        this.files = files;
        this.properties = properties;
        this.ids = ids;
        this.storageKeys = storageKeys;
        this.clock = clock;
    }

    // ------------------------------------------------------------------------------------------------ begin

    /**
     * {@code artifact-begin}. The same author and operation key with the same file name, size and SHA-256 gives the upload that
     * exists; other content under the key is ARTIFACT_OPERATION_CONFLICT. A new upload is refused, never cut, when it is over the
     * file size limit, the budget of all open uploads or the number of open uploads of the author.
     */
    public Begun begin(String actor, Begin command) {
        GateSession session = gate.openWithAnyGrant(actor, Channel.AUTHOR);
        if (command.purpose() != null && !EVIDENCE.equals(command.purpose())) {
            throw new ValidationException(ValidationCode.UNSUPPORTED_PURPOSE, List.of(command.purpose()), List.of(),
                    java.util.Map.of());
        }
        requireText("operation_key", command.operationKey(), 1, 128);
        requireFileName(command.fileName());
        requireMediaType(command.mediaType());
        if (command.size() < 0) {
            throw ValidationException.invalid("size", ValidationReason.MUST_NOT_BE_NEGATIVE);
        }
        Sha256 declared = declaredSha(command.sha256());
        AuthorId owner = session.author();
        return uploads.findByOperation(owner, command.operationKey()).map(row -> replay(row, command, declared))
                .orElseGet(() -> create(owner, command, declared));
    }

    private Begun replay(UploadStaging row, Begin command, Sha256 declared) {
        boolean same = Objects.equals(row.fileName(), command.fileName()) && row.declaredSize() == command.size()
                && Objects.equals(row.declaredSha256(), declared);
        if (!same) {
            throw new ConflictException(ConflictCode.ARTIFACT_OPERATION_CONFLICT, List.of(command.operationKey()));
        }
        return new Begun(row.id(), AttachmentProperties.CHUNK_BYTES, row.expiresAt());
    }

    private Begun create(AuthorId owner, Begin command, Sha256 declared) {
        if (command.size() > properties.maxFileBytes().toBytes()) {
            throw new ConflictException(ConflictCode.ARTIFACT_QUOTA, List.of("the file is larger than the limit"));
        }
        if (uploads.openCount(owner) >= properties.maxOpenPerOwner()) {
            throw new ConflictException(ConflictCode.ARTIFACT_QUOTA, List.of("too many open uploads"));
        }
        long open = uploads.openDeclaredBytes();
        if (open + command.size() > properties.stagingBudgetBytes().toBytes()) {
            throw new ConflictException(ConflictCode.ARTIFACT_QUOTA, List.of("the budget of the open uploads is used"));
        }
        if (attachments.keptBytes() + open + command.size() > properties.attachmentBudgetBytes().toBytes()) {
            throw new ConflictException(ConflictCode.ARTIFACT_QUOTA, List.of("the budget of the kept attachments is used"));
        }
        Instant now = clock.instant();
        UploadStaging row = new UploadStaging(ids.generateId().toString(), owner, command.operationKey(), command.fileName(),
                command.mediaType(), command.size(), declared, 0, 0, 0, null, storageKeys.next(), properties.activeKeyId(),
                UploadState.STAGING, now.plus(properties.uploadTtl()), now, now);
        uploads.insert(row);
        return new Begun(row.id(), AttachmentProperties.CHUNK_BYTES, row.expiresAt());
    }

    // ------------------------------------------------------------------------------------------------ chunk

    /**
     * The step before the file work of {@code artifact-chunk} (design 4.4, step 1): the row of the author's upload, and whether
     * the chunk is the one that was recorded last (idempotent), the next one, or a conflict.
     */
    public ChunkPlan planChunk(String actor, String uploadId, long sequence, int plainBytes, Sha256 chunkSha256) {
        AuthorId author = gate.openWithAnyGrant(actor, Channel.AUTHOR).author();
        UploadStaging row = ownedUpload(author, uploadId);
        if (row.state() != UploadState.STAGING) {
            throw new ConflictException(ConflictCode.ARTIFACT_CHUNK_CONFLICT, List.of());
        }
        if (isRecordedLast(row, sequence, chunkSha256)) {
            return new ChunkPlan.AlreadyHeld(new Received(sequence, row.receivedBytes()));
        }
        if (sequence != row.nextSequence()) {
            throw new ConflictException(ConflictCode.ARTIFACT_CHUNK_CONFLICT, List.of());
        }
        if (plainBytes < 1 || plainBytes > AttachmentProperties.CHUNK_BYTES) {
            throw ValidationException.invalid("data", ValidationReason.OUT_OF_RANGE, "1",
                    Integer.toString(AttachmentProperties.CHUNK_BYTES));
        }
        if (row.receivedBytes() + plainBytes > row.declaredSize()) {
            throw new ConflictException(ConflictCode.ARTIFACT_SIZE_MISMATCH,
                    List.of(Long.toString(row.receivedBytes() + plainBytes), Long.toString(row.declaredSize())));
        }
        return new ChunkPlan.Append(row);
    }

    /**
     * The step after the file work (design 4.4, step 4): one conditional UPDATE that moves the row from the chunk number the
     * caller read. Of two requests for the same chunk one gets the row; the other reads it again and answers idempotently when the
     * chunk is the one that was recorded, and with a conflict when it is not. The expiry is counted again from this chunk.
     */
    public Received recordChunk(String actor, UploadStaging row, long newStoredBytes, int plainBytes, Sha256 chunkSha256) {
        AuthorId author = gate.openWithAnyGrant(actor, Channel.AUTHOR).author();
        Instant now = clock.instant();
        long sequence = row.nextSequence();
        if (uploads.advance(row.id(), sequence, newStoredBytes, plainBytes, chunkSha256, now.plus(properties.uploadTtl()),
                now)) {
            return new Received(sequence, row.receivedBytes() + plainBytes);
        }
        UploadStaging current = ownedUpload(author, row.id());
        if (current.state() == UploadState.STAGING && isRecordedLast(current, sequence, chunkSha256)) {
            return new Received(sequence, current.receivedBytes());
        }
        throw new ConflictException(ConflictCode.ARTIFACT_CHUNK_CONFLICT, List.of());
    }

    private static boolean isRecordedLast(UploadStaging row, long sequence, Sha256 chunkSha256) {
        return row.nextSequence() > 0 && sequence == row.nextSequence() - 1
                && Objects.equals(chunkSha256, row.lastChunkSha256());
    }

    // ------------------------------------------------------------------------------------------------ finish

    /**
     * The step before the file work of {@code artifact-finish}: the attachment when the upload was finished before (the same id
     * again gives the same attachment), otherwise the row the entry point verifies and closes.
     */
    public FinishPlan planFinish(String actor, String uploadId) {
        AuthorId author = gate.openWithAnyGrant(actor, Channel.AUTHOR).author();
        return attachments.findById(uploadId).filter(found -> Objects.equals(found.createdBy(), author))
                .<FinishPlan>map(found -> new FinishPlan.Done(finished(found)))
                .orElseGet(() -> new FinishPlan.Close(ownedUpload(author, uploadId)));
    }

    /**
     * The step after the file work of finish: the state of the upload moves to COMPLETED by one conditional UPDATE, and then the
     * attachment is inserted. When the UPDATE affects no row another finish won (its attachment is answered), or the reaper removed
     * the row (the upload is gone).
     */
    public Finished completeUpload(String actor, UploadStaging row, long finishedFileBytes) {
        AuthorId author = gate.openWithAnyGrant(actor, Channel.AUTHOR).author();
        Instant now = clock.instant();
        if (!uploads.complete(row.id(), row.nextSequence(), finishedFileBytes, now)) {
            return attachments.findById(row.id()).filter(found -> Objects.equals(found.createdBy(), author))
                    .map(UploadService::finished).orElseThrow(() -> new LookupException(LookupCode.NOT_FOUND,
                            List.of("upload", row.id())));
        }
        Attachment attachment = new Attachment(row.id(), row.fileName(), row.mediaType(), row.declaredSize(),
                row.declaredSha256(), author, now);
        attachments.insert(attachment);
        files.insert(new AttachmentFile(ids.generateId().toString(), row.id(), row.storageKey(), row.keyId(), now));
        return finished(attachment);
    }

    private static Finished finished(Attachment attachment) {
        return new Finished(attachment.id(), attachment.sizeBytes(), attachment.contentSha256().hex());
    }

    // ------------------------------------------------------------------------------------------------ status and read

    /** {@code artifact-status}: an upload of the author, or an attachment; no path and no storage key. */
    public Status status(String actor, String id) {
        AuthorId author = gate.openWithAnyGrant(actor, Channel.AUTHOR).author();
        requireUuid("id", id);
        return uploads.findById(id).filter(row -> Objects.equals(row.owner(), author))
                .map(row -> new Status(row.id(), row.state().name(), row.fileName(), row.mediaType(), row.declaredSize(),
                        row.declaredSha256().hex(), row.expiresAt(), row.receivedBytes(), row.nextSequence()))
                .orElseGet(() -> attachments.findById(id)
                        .map(found -> new Status(found.id(), UploadState.COMPLETED.name(), found.fileName(),
                                found.mediaType(), found.sizeBytes(), found.contentSha256().hex(), null, found.sizeBytes(),
                                null))
                        .orElseThrow(() -> new LookupException(LookupCode.NOT_FOUND, List.of("attachment", id))));
    }

    /**
     * The step before the file work of {@code artifact-read}: the attachment with its current file (any author that passes the gate
     * may read an attachment, like the history that points to it) and the page the caller asks for, checked against its size.
     */
    public Source planRead(String actor, String attachmentId, long offsetBytes, long maxBytes) {
        gate.openWithAnyGrant(actor, Channel.AUTHOR);
        requireUuid("attachment_id", attachmentId);
        Attachment attachment = attachments.findById(attachmentId)
                .orElseThrow(() -> new LookupException(LookupCode.NOT_FOUND, List.of("attachment", attachmentId)));
        AttachmentFile file = files.currentOf(attachmentId).orElseThrow(() -> new InfrastructureException(
                InfrastructureCode.ATTACHMENT_CORRUPTED, List.of()));
        if (offsetBytes < 0 || offsetBytes > attachment.sizeBytes()) {
            throw ValidationException.invalid("offset_bytes", ValidationReason.OUT_OF_RANGE, "0",
                    Long.toString(attachment.sizeBytes()));
        }
        if (maxBytes < 1 || maxBytes > AttachmentProperties.CHUNK_BYTES) {
            throw ValidationException.invalid("max_bytes", ValidationReason.OUT_OF_RANGE, "1",
                    Integer.toString(AttachmentProperties.CHUNK_BYTES));
        }
        return new Source(attachment, file);
    }

    // ------------------------------------------------------------------------------------------------ details

    private UploadStaging ownedUpload(AuthorId author, String uploadId) {
        requireUuid("upload_id", uploadId);
        return uploads.findById(uploadId).filter(row -> Objects.equals(row.owner(), author))
                .orElseThrow(() -> new LookupException(LookupCode.NOT_FOUND, List.of("upload", uploadId)));
    }

    private static Sha256 declaredSha(String text) {
        try {
            return Sha256.of(text);
        } catch (ValidationException invalid) {
            throw ValidationException.invalid("sha256", ValidationReason.MUST_BE_LOWER_CASE_HEX, invalid, "64");
        }
    }

    private static void requireUuid(String path, String value) {
        try {
            java.util.UUID.fromString(value == null ? "" : value);
        } catch (IllegalArgumentException notAUuid) {
            throw ValidationException.invalid(path, ValidationReason.MUST_BE_UUID, notAUuid);
        }
    }

    private static void requireText(String path, String value, int min, int max) {
        if (value == null || value.length() < min || value.length() > max || value.isBlank()) {
            throw ValidationException.invalid(path, ValidationReason.MUST_BE_CHARS_RANGE, Integer.toString(min),
                    Integer.toString(max));
        }
    }

    private static void requireFileName(String name) {
        requireText("file_name", name, 1, 255);
        if (FILE_NAME_FORBIDDEN.matcher(name).find()) {
            throw ValidationException.invalid("file_name", ValidationReason.MUST_MATCH, "a name without a path or a control character");
        }
    }

    private static void requireMediaType(String type) {
        requireText("media_type", type, 3, 127);
        if (!MEDIA_TYPE.matcher(type).matches()) {
            throw ValidationException.invalid("media_type", ValidationReason.MUST_MATCH, "type/subtype");
        }
    }
}
