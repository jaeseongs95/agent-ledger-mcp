package org.pgledger.application.support;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicBoolean;
import org.pgledger.application.attachment.AttachmentProperties;
import org.pgledger.application.attachment.StorageKeys;
import org.pgledger.application.attachment.UploadCommands.Begin;
import org.pgledger.application.attachment.UploadCommands.Begun;
import org.pgledger.application.attachment.UploadCommands.Finished;
import org.pgledger.application.attachment.UploadCommands.Received;
import org.pgledger.application.attachment.UploadCommands.Status;
import org.pgledger.application.attachment.UploadService;
import org.pgledger.application.gate.WriteGate;
import org.pgledger.database.repository.AttachmentFileRepository;
import org.pgledger.database.repository.AttachmentRepository;
import org.pgledger.database.repository.UploadStagingRepository;
import org.pgledger.domain.model.UploadStaging;
import org.pgledger.domain.value.Sha256;
import org.springframework.util.IdGenerator;

/**
 * A test double of the upload service that fails once: the one step it is told to ({@code recordChunk} or {@code completeUpload}) throws
 * the first time it is called, after the file work before it was done, and every call then goes to the real service (the proxy of the
 * context, with its transaction). It stands for a database that fails after the bytes were appended (a lost connection, a failed
 * commit): the entry point {@code AttachmentFlow} is built over this double, so its file work runs for real and only the service step
 * between two file steps is made to fail. Every other method only passes the call on.
 */
public final class FailingUploadService extends UploadService {

    /** The service step that fails the first time. */
    public enum Step {
        RECORD_CHUNK, COMPLETE_UPLOAD
    }

    private final UploadService real;
    private final Step failing;
    private final AtomicBoolean armed = new AtomicBoolean(true);

    public FailingUploadService(LedgerAppContext ctx, Step failing) {
        super(ctx.bean(WriteGate.class), ctx.bean(UploadStagingRepository.class), ctx.bean(AttachmentRepository.class),
                ctx.bean(AttachmentFileRepository.class), ctx.bean(AttachmentProperties.class), ctx.bean(IdGenerator.class),
                ctx.bean(StorageKeys.class), ctx.bean(Clock.class));
        this.real = ctx.bean(UploadService.class);
        this.failing = failing;
    }

    private void failOnce(Step step) {
        if (failing == step && armed.getAndSet(false)) {
            throw new IllegalStateException("injected failure of " + step + " (the test double of the service)");
        }
    }

    @Override
    public void admit(String actor) {
        real.admit(actor);
    }

    @Override
    public Begun begin(String actor, Begin command) {
        return real.begin(actor, command);
    }

    @Override
    public ChunkPlan planChunk(String actor, String uploadId, long sequence, int plainBytes, Sha256 chunkSha256) {
        return real.planChunk(actor, uploadId, sequence, plainBytes, chunkSha256);
    }

    @Override
    public Received recordChunk(String actor, UploadStaging row, long newStoredBytes, int plainBytes, Sha256 chunkSha256) {
        failOnce(Step.RECORD_CHUNK);
        return real.recordChunk(actor, row, newStoredBytes, plainBytes, chunkSha256);
    }

    @Override
    public FinishPlan planFinish(String actor, String uploadId) {
        return real.planFinish(actor, uploadId);
    }

    @Override
    public Finished completeUpload(String actor, UploadStaging row, long finishedFileBytes) {
        failOnce(Step.COMPLETE_UPLOAD);
        return real.completeUpload(actor, row, finishedFileBytes);
    }

    @Override
    public Status status(String actor, String id) {
        return real.status(actor, id);
    }

    @Override
    public Source planRead(String actor, String attachmentId, long offsetBytes, long maxBytes) {
        return real.planRead(actor, attachmentId, offsetBytes, maxBytes);
    }
}
