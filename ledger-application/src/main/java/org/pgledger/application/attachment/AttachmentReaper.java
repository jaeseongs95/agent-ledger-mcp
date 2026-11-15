package org.pgledger.application.attachment;

import java.time.Clock;
import java.time.Instant;
import org.pgledger.domain.model.UploadStaging;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Removes the uploads that expired and the rows of the uploads that were finished (common design, section 7). For every upload it
 * runs one conditional DELETE through {@link AttachmentMaintenanceService} and removes the file only when that statement deleted one
 * row; the list that was read first is only the list of candidates, so a chunk that moved the expiry or a finish that completed the
 * upload meanwhile makes the DELETE affect no row and the upload is left alone. A file whose row was deleted and whose removal was
 * lost (the process died) is a file without a row, which the check moves to the quarantine. The class opens no transaction: each
 * statement is one service method, and the file work is here, outside it.
 */
@Component
@ConditionalOnAttachments
public class AttachmentReaper {

    private static final Logger LOG = LoggerFactory.getLogger(AttachmentReaper.class);
    private static final int BATCH = 200;

    private final AttachmentMaintenanceService maintenance;
    private final AttachmentStore store;
    private final Clock clock;

    public AttachmentReaper(AttachmentMaintenanceService maintenance, AttachmentStore store, Clock clock) {
        this.maintenance = maintenance;
        this.store = store;
        this.clock = clock;
    }

    /** One run: returns the number of expired uploads that were removed. */
    public int reap() {
        Instant now = clock.instant();
        int removed = 0;
        for (UploadStaging row : maintenance.expired(now, BATCH)) {
            if (maintenance.removeExpired(row.id(), now)) {
                store.remove(row.storageKey());
                removed++;
            }
        }
        for (UploadStaging row : maintenance.completed(BATCH)) {
            maintenance.removeCompleted(row.id());
        }
        if (removed > 0) {
            LOG.info("{} expired uploads were removed", removed);
        }
        return removed;
    }
}
