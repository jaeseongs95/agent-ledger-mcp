// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application.support;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicBoolean;
import org.veridger.application.attachment.AttachmentMaintenanceService;
import org.veridger.database.repository.AttachmentFileRepository;
import org.veridger.database.repository.AttachmentRepository;
import org.veridger.database.repository.AttachmentStorageRepository;
import org.veridger.database.repository.IdAllocator;
import org.veridger.database.repository.OwnerAuditRepository;
import org.veridger.database.repository.UploadStagingRepository;
import org.veridger.domain.value.OwnerIdentity;
import org.veridger.domain.value.StorageKey;

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
