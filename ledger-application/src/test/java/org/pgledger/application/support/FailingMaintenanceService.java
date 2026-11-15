package org.pgledger.application.support;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.pgledger.application.admin.OwnerAuditWriter;
import org.pgledger.application.attachment.AttachmentMaintenanceService;
import org.pgledger.database.repository.AttachmentFileRepository;
import org.pgledger.database.repository.AttachmentRepository;
import org.pgledger.database.repository.UploadStagingRepository;
import org.pgledger.domain.model.AttachmentFile;
import org.pgledger.domain.value.OwnerIdentity;
import org.pgledger.domain.value.StorageKey;

/**
 * A test double of the maintenance service whose {@code addRekeyedFile} fails the first time it is called, and passes every call on to
 * the real service (the proxy of the context, with its transaction) afterwards. The rekey is built over it: the new file is really
 * written before the step that adds its row fails, which is the failure of design 9.16 (a failure after the new file and before its row).
 * The other method the rekey uses only passes the call on.
 */
public final class FailingMaintenanceService extends AttachmentMaintenanceService {

    private final AttachmentMaintenanceService real;
    private final AtomicBoolean armed = new AtomicBoolean(true);

    public FailingMaintenanceService(LedgerAppContext ctx) {
        super(ctx.bean(UploadStagingRepository.class), ctx.bean(AttachmentRepository.class),
                ctx.bean(AttachmentFileRepository.class), ctx.bean(OwnerAuditWriter.class), ctx.bean(Clock.class));
        this.real = ctx.bean(AttachmentMaintenanceService.class);
    }

    @Override
    public boolean addRekeyedFile(OwnerIdentity actor, String attachmentId, StorageKey expectedCurrent, StorageKey newKey,
            String keyId) {
        if (armed.getAndSet(false)) {
            throw new IllegalStateException("injected failure of the row of the new file (the test double of the service)");
        }
        return real.addRekeyedFile(actor, attachmentId, expectedCurrent, newKey, keyId);
    }

    @Override
    public List<AttachmentFile> currentFiles(String afterAttachmentId, int limit) {
        return real.currentFiles(afterAttachmentId, limit);
    }
}
