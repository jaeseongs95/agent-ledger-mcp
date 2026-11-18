package org.pgledger.application.support;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicBoolean;
import org.pgledger.application.attachment.AttachmentMaintenanceService;
import org.pgledger.database.repository.AttachmentFileRepository;
import org.pgledger.database.repository.AttachmentRepository;
import org.pgledger.database.repository.AttachmentStorageRepository;
import org.pgledger.database.repository.IdAllocator;
import org.pgledger.database.repository.OwnerAuditRepository;
import org.pgledger.database.repository.UploadStagingRepository;
import org.pgledger.domain.value.OwnerIdentity;
import org.pgledger.domain.value.StorageKey;

/** Test double that injects one failure inside {@link #addRekeyedFile} after the new encrypted file has been written. */
public class FailingMaintenanceService extends AttachmentMaintenanceService {

    private final AtomicBoolean armed = new AtomicBoolean(true);

    public FailingMaintenanceService(LedgerAppContext ctx) {
        super(
                ctx.bean(UploadStagingRepository.class),
                ctx.bean(AttachmentRepository.class),
                ctx.bean(AttachmentFileRepository.class),
                ctx.bean(AttachmentStorageRepository.class),
                ctx.bean(OwnerAuditRepository.class),
                ctx.bean(IdAllocator.class),
                ctx.bean(Clock.class));
    }

    @Override
    public boolean addRekeyedFile(String attachmentId, int seq, StorageKey expectedCurrent, StorageKey storageKey,
            String keyId, OwnerIdentity actor) {
        if (armed.compareAndSet(true, false)) {
            throw new IllegalStateException("injected failure in addRekeyedFile");
        }
        return super.addRekeyedFile(attachmentId, seq, expectedCurrent, storageKey, keyId, actor);
    }
}
