// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.application.support;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicBoolean;
import org.agentledger.application.attachment.AttachmentMaintenanceService;
import org.agentledger.database.repository.AttachmentFileRepository;
import org.agentledger.database.repository.AttachmentRepository;
import org.agentledger.database.repository.AttachmentStorageRepository;
import org.agentledger.database.repository.IdAllocator;
import org.agentledger.database.repository.OwnerAuditRepository;
import org.agentledger.database.repository.UploadStagingRepository;
import org.agentledger.domain.value.OwnerIdentity;
import org.agentledger.domain.value.StorageKey;

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
