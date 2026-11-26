// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.application.attachment;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.agentledger.application.attachment.UploadCommands.Begin;
import org.agentledger.application.attachment.UploadCommands.BeginFile;
import org.agentledger.application.attachment.UploadCommands.Begun;
import org.agentledger.application.attachment.UploadCommands.BegunFile;
import org.agentledger.application.attachment.UploadCommands.Finished;
import org.agentledger.application.attachment.UploadCommands.KeptFile;
import org.agentledger.application.attachment.UploadCommands.Status;
import org.agentledger.application.gate.WriteGate;
import org.agentledger.database.repository.AttachmentFileRepository;
import org.agentledger.database.repository.AttachmentRepository;
import org.agentledger.database.repository.AttachmentStorageRepository;
import org.agentledger.database.repository.UploadStagingRepository;
import org.agentledger.domain.error.ArtifactRetry;
import org.agentledger.domain.error.ConflictCode;
import org.agentledger.domain.error.ConflictException;
import org.agentledger.domain.error.LedgerException;
import org.agentledger.domain.error.LookupCode;
import org.agentledger.domain.error.LookupException;
import org.agentledger.domain.error.ValidationCode;
import org.agentledger.domain.error.ValidationException;
import org.agentledger.domain.error.ValidationIssue;
import org.agentledger.domain.error.ValidationReason;
import org.agentledger.domain.model.Attachment;
import org.agentledger.domain.model.AttachmentFile;
import org.agentledger.domain.model.AttachmentStorage;
import org.agentledger.domain.model.UploadStaging;
import org.agentledger.domain.rule.Channel;
import org.agentledger.domain.rule.UploadState;
import org.agentledger.domain.value.AuthorId;
import org.agentledger.domain.value.Sha256;
import org.agentledger.domain.value.StorageKey;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Database transactions of the bundle upload lifecycle (design 6): each public method is a single transaction and never
 * touches files on disk. File encryption/verification happens in {@link AttachmentFlow} outside transactions.
 */
@Service
@Transactional
@ConditionalOnAttachments
public class UploadService {

    /** Contract cap on the number of files in one bundle attachment. */
    public static final int MAX_FILES = 64;
    static final String PURPOSE_EVIDENCE = "evidence";

    private static final int MAX_OPERATION_KEY = 128;
    private static final int MAX_FILE_NAME = 512;
    private static final int MAX_MEDIA_TYPE = 255;
    private static final Pattern MEDIA_TYPE =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]*/[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]*(;[ -~]*)?");

    /** Decision for {@link AttachmentFlow#begin}: already begun idempotently, or validated within quotas and ready to create. */
    public sealed interface BeginPlan {
        record Replay(Begun begun) implements BeginPlan {
        }

        record Create(long totalDeclaredBytes) implements BeginPlan {
        }
    }

    /** Decision for {@link AttachmentFlow#chunk}: already applied idempotently, or ready to append to {@code row}. */
    public sealed interface ChunkPlan {
        record AlreadyApplied(UploadCommands.Received received) implements ChunkPlan {
        }

        record Append(UploadStaging row) implements ChunkPlan {
        }
    }

    /** Decision for {@link AttachmentFlow#finish}: already finished, or rows ordered by {@code seq} to verify and close. */
    public sealed interface FinishPlan {
        record Done(Finished finished) implements FinishPlan {
        }

        record Close(List<UploadStaging> rows) implements FinishPlan {
        }
    }

    /** One closed and verified file of a pending bundle, passed into {@link #completeUpload}. */
    public record ClosedFile(String uploadId, long storedBytes) {
    }

    /** Resolved file view for reading an attachment page. */
    public record FileView(Attachment attachment, AttachmentFile file, AttachmentStorage current) {
    }

    private final WriteGate gate;
    private final UploadStagingRepository uploads;
    private final AttachmentRepository attachments;
    private final AttachmentFileRepository files;
    private final AttachmentStorageRepository storages;
    private final AttachmentProperties properties;
    private final StorageKeys storageKeys;
    private final Clock clock;

    public UploadService(
            WriteGate gate,
            UploadStagingRepository uploads,
            AttachmentRepository attachments,
            AttachmentFileRepository files,
            AttachmentStorageRepository storages,
            AttachmentProperties properties,
            StorageKeys storageKeys,
            Clock clock) {
        this.gate = gate;
        this.uploads = uploads;
        this.attachments = attachments;
        this.files = files;
        this.storages = storages;
        this.properties = properties;
        this.storageKeys = storageKeys;
        this.clock = clock;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private AuthorId requireOwner(String actor) {
        return gate.openWithAnyGrant(actor, Channel.AUTHOR).author();
    }

    private AuthorId optionalOwner(String actor) {
        return actor == null ? null : gate.openWithAnyGrant(actor, Channel.AUTHOR).author();
    }

    /**
     * Read-only pre-flight for {@link AttachmentFlow#begin}: verifies caller identity and grant, validates input,
     * resolves idempotent operation-key replay/conflict, and checks file-size and quota limits before disk space is
     * checked or directories are created.
     */
    @Transactional(readOnly = true)
    public BeginPlan planBegin(String actor, Begin command) {
        AuthorId owner = requireOwner(actor);
        List<ValidatedFile> checkedFiles = validateBegin(command);
        List<UploadStaging> existing = uploads.findByOwnerAndOperationKey(owner.value(), command.operationKey());
        if (!existing.isEmpty()) {
            return new BeginPlan.Replay(replayOrConflict(existing, checkedFiles, command.operationKey()));
        }
        long totalDeclared = totalDeclaredBytes(checkedFiles);
        checkQuotas(owner, totalDeclared);
        return new BeginPlan.Create(totalDeclared);
    }

    public Begun begin(String actor, Begin command) {
        AuthorId owner = requireOwner(actor);
        List<ValidatedFile> checkedFiles = validateBegin(command);
        String operationKey = command.operationKey();
        uploads.lockQuotaGate();
        List<UploadStaging> existing = uploads.findByOwnerAndOperationKey(owner.value(), operationKey);
        if (!existing.isEmpty()) {
            return replayOrConflict(existing, checkedFiles, operationKey);
        }
        long totalDeclared = totalDeclaredBytes(checkedFiles);
        checkQuotas(owner, totalDeclared);
        Instant now = now();
        Instant expiresAt = now.plus(properties.uploadTtl());
        String attachmentId = UUID.randomUUID().toString();
        String keyId = activeKeyId();
        List<BegunFile> begunFiles = new ArrayList<>(checkedFiles.size());
        for (int seq = 0; seq < checkedFiles.size(); seq++) {
            ValidatedFile file = checkedFiles.get(seq);
            String uploadId = UUID.randomUUID().toString();
            StorageKey storageKey = storageKeys.next();
            uploads.insert(new UploadStaging(
                    uploadId,
                    attachmentId,
                    seq,
                    owner,
                    operationKey,
                    file.fileName(),
                    file.mediaType(),
                    file.size(),
                    file.sha256(),
                    0L,
                    0L,
                    0L,
                    null,
                    storageKey,
                    keyId,
                    UploadState.STAGING,
                    expiresAt,
                    now,
                    now));
            begunFiles.add(new BegunFile(seq, uploadId));
        }
        return new Begun(attachmentId, List.copyOf(begunFiles), AttachmentProperties.CHUNK_BYTES, expiresAt);
    }

    private List<ValidatedFile> validateBegin(Begin command) {
        List<ValidationIssue> issues = new ArrayList<>();
        if (command == null) {
            throw ValidationException.invalid("request", ValidationReason.REQUIRED);
        }
        String operationKey = command.operationKey();
        if (operationKey == null || operationKey.isBlank()) {
            issues.add(new ValidationIssue("operationKey", ValidationReason.REQUIRED, List.of()));
        } else if (operationKey.length() > MAX_OPERATION_KEY) {
            issues.add(new ValidationIssue("operationKey", ValidationReason.MUST_BE_AT_MOST_CHARS,
                    List.of(Integer.toString(MAX_OPERATION_KEY))));
        }
        List<BeginFile> declared = command.files();
        if (declared == null || declared.isEmpty()) {
            issues.add(new ValidationIssue("files", ValidationReason.MUST_HOLD_ITEMS,
                    List.of("1", Integer.toString(MAX_FILES))));
        } else if (declared.size() > MAX_FILES) {
            throw limitConflict("file-count", "Too many files in one bundle: " + declared.size() + " (max " + MAX_FILES + ")");
        } else {
            for (int seq = 0; seq < declared.size(); seq++) {
                validateFile("files[" + seq + "]", declared.get(seq), issues);
            }
        }
        if (!issues.isEmpty()) {
            throw validation(issues);
        }
        String purpose = command.purpose();
        if (purpose != null && !PURPOSE_EVIDENCE.equals(purpose)) {
            throw new ValidationException(ValidationCode.ARTIFACT_UNSUPPORTED_PURPOSE, List.of(purpose),
                    List.of(new ValidationIssue("purpose", ValidationReason.UNKNOWN_VALUE, List.of(purpose),
                            List.of(PURPOSE_EVIDENCE))),
                    Map.of());
        }
        return declared.stream().map(UploadService::toValidated).toList();
    }

    private void checkQuotas(AuthorId owner, long totalDeclared) {
        long openBundles = uploads.countOpenBundles(owner.value());
        if (openBundles >= properties.maxOpenPerOwner()) {
            throw limitConflict("open-uploads",
                    "Author has too many open upload bundles (" + openBundles + ", max " + properties.maxOpenPerOwner() + ")");
        }
        long declaredOpen = uploads.declaredOpenBytes();
        long stagingBudget = properties.stagingBudgetBytes().toBytes();
        if (addSaturating(declaredOpen, totalDeclared) > stagingBudget) {
            throw limitConflict("staging-budget",
                    "Staging upload budget exceeded (" + addSaturating(declaredOpen, totalDeclared) + " > " + stagingBudget + ")");
        }
        long keptBytes = files.totalBytes();
        long attachmentBudget = properties.attachmentBudgetBytes().toBytes();
        if (addSaturating(keptBytes, addSaturating(declaredOpen, totalDeclared)) > attachmentBudget) {
            throw limitConflict("attachment-budget",
                    "Total kept attachments plus open uploads budget exceeded");
        }
    }

    /**
     * Resolves a concurrent duplicate {@code artifact_begin} after a rolled-back {@code DuplicateKeyException} by
     * re-reading the committed staging bundle in a fresh transaction and returning an idempotent replay or
     * {@code ARTIFACT_OPERATION_CONFLICT}.
     */
    @Transactional(readOnly = true)
    public Begun resolveDuplicateBegin(String actor, Begin command) {
        AuthorId owner = requireOwner(actor);
        if (command == null || command.operationKey() == null || command.files() == null) {
            throw new ConflictException(ConflictCode.ARTIFACT_OPERATION_CONFLICT,
                    List.of(command == null || command.operationKey() == null ? "" : command.operationKey()));
        }
        List<ValidatedFile> checkedFiles = command.files().stream().map(UploadService::toValidated).toList();
        List<UploadStaging> existing = uploads.findByOwnerAndOperationKey(owner.value(), command.operationKey());
        if (existing.isEmpty()) {
            throw new ConflictException(ConflictCode.ARTIFACT_OPERATION_CONFLICT, List.of(command.operationKey()));
        }
        return replayOrConflict(existing, checkedFiles, command.operationKey());
    }

    @Transactional(readOnly = true)
    public ChunkPlan planChunk(String actor, String uploadId, long sequence, int chunkBytes, Sha256 chunkSha256) {
        if (sequence < 0) {
            throw ValidationException.invalid("sequence", ValidationReason.MUST_NOT_BE_NEGATIVE);
        }
        if (chunkBytes < 1 || chunkBytes > AttachmentProperties.CHUNK_BYTES) {
            throw ValidationException.invalid("data", ValidationReason.OUT_OF_RANGE, "1",
                    Integer.toString(AttachmentProperties.CHUNK_BYTES));
        }
        AuthorId caller = optionalOwner(actor);
        UploadStaging row = findOwnedUpload(caller, uploadId);
        if (row.state() != UploadState.STAGING) {
            throw new ConflictException(ConflictCode.ARTIFACT_CHUNK_CONFLICT,
                    List.of("Upload is already completed"));
        }
        Instant now = now();
        if (!now.isBefore(row.expiresAt())) {
            throw new ConflictException(ConflictCode.ARTIFACT_EXPIRED, List.of(uploadId));
        }
        if (row.nextSequence() > 0 && sequence == row.nextSequence() - 1) {
            if (Objects.equals(row.lastChunkSha256(), chunkSha256)) {
                return new ChunkPlan.AlreadyApplied(new UploadCommands.Received(sequence, row.receivedBytes()));
            }
            throw new ConflictException(ConflictCode.ARTIFACT_CHUNK_CONFLICT,
                    List.of("Different chunk content for already applied sequence " + sequence));
        }
        if (sequence != row.nextSequence()) {
            throw new ConflictException(ConflictCode.ARTIFACT_CHUNK_CONFLICT,
                    List.of("Expected sequence " + row.nextSequence() + " but got " + sequence));
        }
        if (addSaturating(row.receivedBytes(), chunkBytes) > row.declaredSize()) {
            throw new ConflictException(ConflictCode.ARTIFACT_HASH_MISMATCH,
                    List.of("Chunk bytes exceed declared size " + row.declaredSize()));
        }
        long maxSegments = maxSegmentsFor(row.declaredSize());
        if (row.nextSequence() >= maxSegments) {
            throw limitConflict("chunk-count",
                    "Upload exceeds maximum chunk count (" + maxSegments + ") for declared size " + row.declaredSize());
        }
        return new ChunkPlan.Append(row);
    }

    static long maxSegmentsFor(long declaredSize) {
        if (declaredSize <= 0) {
            return 0L;
        }
        long byFloor = (declaredSize + 4095L) / 4096L;
        return Math.max(16L, byFloor);
    }

    @Transactional
    public UploadCommands.Received recordChunk(String actor, UploadStaging row, long storedBytes, int chunkBytes,
            Sha256 chunkSha256) {
        AuthorId caller = optionalOwner(actor);
        Instant now = now();
        Instant expiresAt = now.plus(properties.uploadTtl());
        if (uploads.advance(row.id(), row.nextSequence(), storedBytes, row.receivedBytes() + chunkBytes,
                chunkSha256, expiresAt, now)) {
            uploads.extendBundle(row.attachmentId(), expiresAt, now);
            return new UploadCommands.Received(row.nextSequence(), row.receivedBytes() + chunkBytes);
        }
        UploadStaging current = findOwnedUpload(caller, row.id());
        if (current.state() == UploadState.STAGING
                && current.nextSequence() == row.nextSequence() + 1
                && Objects.equals(current.lastChunkSha256(), chunkSha256)) {
            return new UploadCommands.Received(row.nextSequence(), current.receivedBytes());
        }
        if (!now.isBefore(current.expiresAt())) {
            throw new ConflictException(ConflictCode.ARTIFACT_EXPIRED, List.of(row.id()));
        }
        throw new ConflictException(ConflictCode.ARTIFACT_CHUNK_CONFLICT,
                List.of("Upload state changed concurrently"));
    }

    @Transactional(readOnly = true)
    public FinishPlan planFinish(String actor, String attachmentId) {
        AuthorId caller = requireOwner(actor);
        if (!isUuid(attachmentId)) {
            throw notFound(attachmentId);
        }
        List<UploadStaging> rows = uploads.findByAttachmentId(attachmentId);
        if (rows.isEmpty()) {
            Attachment kept = attachments.findById(attachmentId).orElseThrow(() -> notFound(attachmentId));
            if (!Objects.equals(kept.createdBy(), caller)) {
                throw notFound(attachmentId);
            }
            return new FinishPlan.Done(finishedOf(kept.id()));
        }
        if (!Objects.equals(rows.getFirst().owner(), caller)) {
            throw notFound(attachmentId);
        }
        if (rows.getFirst().state() == UploadState.COMPLETED) {
            return new FinishPlan.Done(finishedOf(attachmentId));
        }
        Instant now = now();
        for (int seq = 0; seq < rows.size(); seq++) {
            UploadStaging row = rows.get(seq);
            if (row.seq() != seq || row.state() != UploadState.STAGING || !now.isBefore(row.expiresAt())) {
                throw new ConflictException(ConflictCode.ARTIFACT_EXPIRED, List.of(attachmentId));
            }
        }
        return new FinishPlan.Close(rows);
    }

    @Transactional
    public Finished completeUpload(String actor, String attachmentId, List<ClosedFile> closed) {
        AuthorId caller = requireOwner(actor);
        List<UploadStaging> rows = uploads.findByAttachmentId(attachmentId);
        if (rows.isEmpty()) {
            return attachments.findById(attachmentId)
                    .filter(kept -> Objects.equals(kept.createdBy(), caller))
                    .map(kept -> finishedOf(kept.id()))
                    .orElseThrow(() -> notFound(attachmentId));
        }
        if (!Objects.equals(rows.getFirst().owner(), caller)) {
            throw notFound(attachmentId);
        }
        if (rows.getFirst().state() == UploadState.COMPLETED) {
            return finishedOf(attachmentId);
        }
        if (closed == null || closed.size() != rows.size()) {
            throw notFound(attachmentId);
        }
        Instant now = now();
        for (int seq = 0; seq < rows.size(); seq++) {
            UploadStaging row = rows.get(seq);
            ClosedFile closedFile = closed.get(seq);
            if (!Objects.equals(row.id(), closedFile.uploadId()) || !uploads.complete(row.id(), closedFile.storedBytes(), now)) {
                // A finish that another request committed first is the attachment row that it inserted in the same transaction. The
                // rows of this transaction (the ones completed above) are no evidence of it: a row that is gone ends this transaction
                // with an error and rolls the completed rows back.
                return attachments.findById(attachmentId)
                        .filter(kept -> Objects.equals(kept.createdBy(), caller))
                        .map(kept -> finishedOf(kept.id()))
                        .orElseThrow(() -> notFound(attachmentId));
            }
        }
        AuthorId owner = rows.getFirst().owner();
        attachments.insert(new Attachment(attachmentId, owner, now));
        for (UploadStaging row : rows) {
            files.insert(new AttachmentFile(attachmentId, row.seq(), row.fileName(), row.mediaType(),
                    row.declaredSize(), row.declaredSha256()));
            storages.insert(new AttachmentStorage(attachmentId, row.seq(), 0, row.storageKey(), row.keyId(), now));
        }
        return finishedOf(attachmentId);
    }

    @Transactional(readOnly = true)
    public Status uploadStatus(String actor, String uploadId) {
        AuthorId caller = optionalOwner(actor);
        UploadStaging row = findOwnedUpload(caller, uploadId);
        return new Status(
                "upload",
                row.id(),
                row.attachmentId(),
                row.seq(),
                row.state().name(),
                row.fileName(),
                row.mediaType(),
                row.declaredSize(),
                row.declaredSha256().hex(),
                row.expiresAt(),
                row.receivedBytes(),
                row.nextSequence(),
                null);
    }

    @Transactional(readOnly = true)
    public Status attachmentStatus(String actor, String attachmentId) {
        requireOwner(actor);
        return readAttachmentStatus(attachmentId);
    }

    @Transactional(readOnly = true)
    public Status readAttachmentStatus(String attachmentId) {
        if (!isUuid(attachmentId)) {
            throw notFound(attachmentId);
        }
        Attachment attachment = attachments.findById(attachmentId).orElseThrow(() -> notFound(attachmentId));
        return new Status(
                "attachment",
                null,
                attachment.id(),
                null,
                UploadState.COMPLETED.name(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                keptFiles(attachment.id()));
    }

    @Transactional(readOnly = true)
    public FileView fileView(String actor, String attachmentId, int seq) {
        requireOwner(actor);
        return readFileView(attachmentId, seq);
    }

    @Transactional(readOnly = true)
    public FileView readFileView(String attachmentId, int seq) {
        if (!isUuid(attachmentId) || seq < 0) {
            throw notFound(attachmentId);
        }
        Attachment attachment = attachments.findById(attachmentId).orElseThrow(() -> notFound(attachmentId));
        AttachmentFile file = files.at(attachmentId, seq).orElseThrow(() -> notFound(attachmentId));
        AttachmentStorage current = storages.current(attachmentId, seq).orElseThrow(() -> notFound(attachmentId));
        return new FileView(attachment, file, current);
    }

    private UploadStaging findOwnedUpload(AuthorId caller, String uploadId) {
        if (!isUuid(uploadId)) {
            throw notFound(uploadId);
        }
        UploadStaging row = uploads.findById(uploadId).orElseThrow(() -> notFound(uploadId));
        if (caller != null && !Objects.equals(row.owner(), caller)) {
            throw notFound(uploadId);
        }
        return row;
    }

    private Finished finishedOf(String attachmentId) {
        return new Finished(attachmentId, keptFiles(attachmentId));
    }

    private List<KeptFile> keptFiles(String attachmentId) {
        return files.ofAttachment(attachmentId).stream()
                .map(f -> new KeptFile(f.seq(), f.fileName(), f.mediaType(), f.sizeBytes(), f.contentSha256().hex()))
                .toList();
    }

    private String activeKeyId() {
        String keyId = properties.activeKeyId();
        if (keyId == null || keyId.isBlank()) {
            throw new IllegalStateException("ledger.attachments.active-key-id is not set");
        }
        return keyId;
    }

    private Begun replayOrConflict(List<UploadStaging> existing, List<ValidatedFile> declared, String operationKey) {
        if (existing.size() != declared.size()) {
            throw new ConflictException(ConflictCode.ARTIFACT_OPERATION_CONFLICT, List.of(operationKey));
        }
        for (int seq = 0; seq < declared.size(); seq++) {
            UploadStaging row = existing.get(seq);
            ValidatedFile file = declared.get(seq);
            if (row.seq() != seq
                    || !Objects.equals(row.fileName(), file.fileName())
                    || !Objects.equals(row.mediaType(), file.mediaType())
                    || row.declaredSize() != file.size()
                    || !Objects.equals(row.declaredSha256(), file.sha256())) {
                throw new ConflictException(ConflictCode.ARTIFACT_OPERATION_CONFLICT, List.of(operationKey));
            }
        }
        List<BegunFile> begunFiles = existing.stream().map(r -> new BegunFile(r.seq(), r.id())).toList();
        return new Begun(existing.getFirst().attachmentId(), begunFiles, AttachmentProperties.CHUNK_BYTES,
                existing.getFirst().expiresAt());
    }

    private long totalDeclaredBytes(List<ValidatedFile> files) {
        long maxFile = properties.maxFileBytes().toBytes();
        long total = 0L;
        for (ValidatedFile file : files) {
            if (file.size() > maxFile) {
                throw limitConflict("file-size",
                        "File is larger than the limit (" + file.size() + " > " + maxFile + ")");
            }
            total = addSaturating(total, file.size());
        }
        return total;
    }

    private static long addSaturating(long a, long b) {
        return a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b;
    }

    private record ValidatedFile(String fileName, String mediaType, long size, Sha256 sha256) {
    }

    private static ValidatedFile toValidated(BeginFile file) {
        return new ValidatedFile(file.fileName(), file.mediaType(), file.size(), Sha256.of(file.sha256()));
    }

    private static void validateFile(String prefix, BeginFile file, List<ValidationIssue> issues) {
        if (file == null) {
            issues.add(new ValidationIssue(prefix, ValidationReason.REQUIRED, List.of()));
            return;
        }
        if (file.fileName() == null || file.fileName().isBlank()) {
            issues.add(new ValidationIssue(prefix + ".fileName", ValidationReason.REQUIRED, List.of()));
        } else if (file.fileName().length() > MAX_FILE_NAME || hasControlOrBackslash(file.fileName())) {
            issues.add(new ValidationIssue(prefix + ".fileName", ValidationReason.MUST_BE_AT_MOST_CHARS,
                    List.of(Integer.toString(MAX_FILE_NAME))));
        }
        if (file.mediaType() == null || file.mediaType().length() > MAX_MEDIA_TYPE
                || !MEDIA_TYPE.matcher(file.mediaType()).matches()) {
            issues.add(new ValidationIssue(prefix + ".mediaType", ValidationReason.MUST_MATCH,
                    List.of("type/subtype")));
        }
        if (file.size() < 0) {
            issues.add(new ValidationIssue(prefix + ".size", ValidationReason.MUST_NOT_BE_NEGATIVE, List.of()));
        }
        try {
            Sha256.of(file.sha256());
        } catch (ValidationException invalid) {
            issues.add(new ValidationIssue(prefix + ".sha256", ValidationReason.MUST_BE_LOWER_CASE_HEX, List.of("64")));
        }
    }

    private static boolean hasControlOrBackslash(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x20 || c == 0x7F || c == '\\') {
                return true;
            }
        }
        return false;
    }

    private static boolean isUuid(String id) {
        if (id == null) {
            return false;
        }
        try {
            UUID.fromString(id);
            return true;
        } catch (IllegalArgumentException notUuid) {
            return false;
        }
    }

    private static LedgerException limitConflict(String limitKind, String message) {
        return new ConflictException(ConflictCode.ARTIFACT_LIMIT, List.of(message))
                .withDetails(Map.of(ArtifactRetry.LIMIT_KEY, List.of(limitKind)));
    }

    private static LookupException notFound(String id) {
        return new LookupException(LookupCode.ARTIFACT_NOT_FOUND, List.of(id == null ? "" : id));
    }

    private static ValidationException validation(List<ValidationIssue> issues) {
        return new ValidationException(ValidationCode.VALIDATION_FAILED,
                List.of(issues.getFirst().path()), issues, Map.of());
    }
}
