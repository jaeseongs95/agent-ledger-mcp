// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.application.support;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.pgledger.application.attachment.AttachmentProperties;
import org.pgledger.application.attachment.StorageKeys;
import org.pgledger.application.attachment.UploadCommands.Finished;
import org.pgledger.application.attachment.UploadCommands.Received;
import org.pgledger.application.attachment.UploadService;
import org.pgledger.application.gate.WriteGate;
import org.pgledger.database.repository.AttachmentFileRepository;
import org.pgledger.database.repository.AttachmentRepository;
import org.pgledger.database.repository.AttachmentStorageRepository;
import org.pgledger.database.repository.UploadStagingRepository;
import org.pgledger.domain.model.UploadStaging;
import org.pgledger.domain.value.Sha256;

/** Test double that injects a single crash after disk writes during {@link Step#RECORD_CHUNK} or {@link Step#COMPLETE_UPLOAD}. */
public class FailingUploadService extends UploadService {

    public enum Step {
        RECORD_CHUNK,
        COMPLETE_UPLOAD
    }

    private final Step step;
    private final AtomicBoolean armed = new AtomicBoolean(true);

    public FailingUploadService(LedgerAppContext ctx, Step step) {
        super(
                ctx.bean(WriteGate.class),
                ctx.bean(UploadStagingRepository.class),
                ctx.bean(AttachmentRepository.class),
                ctx.bean(AttachmentFileRepository.class),
                ctx.bean(AttachmentStorageRepository.class),
                ctx.bean(AttachmentProperties.class),
                ctx.bean(StorageKeys.class),
                ctx.bean(Clock.class));
        this.step = step;
    }

    @Override
    public Received recordChunk(String actor, UploadStaging row, long storedBytes, int chunkBytes, Sha256 chunkSha256) {
        if (step == Step.RECORD_CHUNK && armed.compareAndSet(true, false)) {
            throw new IllegalStateException("injected failure in recordChunk");
        }
        return super.recordChunk(actor, row, storedBytes, chunkBytes, chunkSha256);
    }

    @Override
    public Finished completeUpload(String actor, String attachmentId, List<ClosedFile> closed) {
        if (step == Step.COMPLETE_UPLOAD && armed.compareAndSet(true, false)) {
            throw new IllegalStateException("injected failure in completeUpload");
        }
        return super.completeUpload(actor, attachmentId, closed);
    }
}
