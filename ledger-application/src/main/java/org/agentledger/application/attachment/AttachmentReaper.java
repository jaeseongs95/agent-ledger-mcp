// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.application.attachment;

import java.time.Clock;
import java.time.Instant;
import org.agentledger.domain.model.UploadStaging;
import org.springframework.stereotype.Component;

/** Cleans expired open upload rows (and their staging files) and completed staging rows already persisted in bundle tables. */
@Component
@ConditionalOnAttachments
public class AttachmentReaper {

    private static final int BATCH_SIZE = 100;

    private final AttachmentMaintenanceService maintenance;
    private final AttachmentStore store;
    private final Clock clock;

    public AttachmentReaper(AttachmentMaintenanceService maintenance, AttachmentStore store, Clock clock) {
        this.maintenance = maintenance;
        this.store = store;
        this.clock = clock;
    }

    public int reap() {
        Instant now = clock.instant();
        int removed = 0;
        for (UploadStaging completed : maintenance.completedUploads(BATCH_SIZE)) {
            if (maintenance.removeCompleted(completed.id())) {
                removed++;
            }
        }
        for (UploadStaging expired : maintenance.expiredUploads(now, BATCH_SIZE)) {
            if (maintenance.removeExpired(expired.id(), now)) {
                store.remove(expired.storageKey());
                removed++;
            }
        }
        return removed;
    }
}
