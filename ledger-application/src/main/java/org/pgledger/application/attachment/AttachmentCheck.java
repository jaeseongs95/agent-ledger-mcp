package org.pgledger.application.attachment;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;
import org.pgledger.domain.model.Attachment;
import org.pgledger.domain.model.AttachmentFile;
import org.pgledger.domain.value.OwnerIdentity;
import org.pgledger.domain.value.StorageKey;
import org.springframework.stereotype.Component;

/**
 * The reconciliation of the folder, the keys and the rows (common design, section 7), for {@code attachments-check} and the admin
 * page. It reports and, when asked, moves files; it deletes only on the explicit {@link #purgeQuarantine}:
 *
 * <ul>
 * <li>a file that no row names ({@code upload_staging}, {@code attachment_file}) and that is older than {@code orphan-grace} is moved
 *     under {@code orphans/}; a file of a younger age stays (an upload that is about to be recorded);
 * <li>a replaced file (a row whose seq is not the largest of its attachment) is moved under {@code orphans/} after {@code orphan-grace}
 *     counted from the row of the file that replaced it;
 * <li>a quarantined file whose row exists again and is not a replaced one (the database was restored) is moved back;
 * <li>an attachment without its current file, or whose file does not pass every tag, the end mark, the size and the hash, is damaged:
 *     reported and never deleted; an attachment no history row points to is listed and kept.
 * </ul>
 *
 * The report never names a storage key or a path. The class opens no transaction: the database is asked through
 * {@link AttachmentMaintenanceService}, the files through {@link AttachmentStore}.
 */
@Component
@ConditionalOnAttachments
public class AttachmentCheck {

    private static final int PAGE = 200;

    /**
     * What a run found.
     *
     * @param orphansQuarantined files that no row names and that were moved (or, in a read-only run, would be)
     * @param orphansWaiting     files that no row names and that are younger than the grace
     * @param replacedQuarantined replaced files that were moved (or would be)
     * @param restored           quarantined files whose row exists again and that were moved back (or would be)
     * @param quarantined        the files in the quarantine folder when the run ended
     * @param missing            ids of the attachments without their current file
     * @param damaged            ids of the attachments whose file does not pass
     * @param unreferenced       ids of the attachments no history row points to
     * @param attachments        how many attachments were looked at
     */
    public record Report(int orphansQuarantined, int orphansWaiting, int replacedQuarantined, int restored, int quarantined,
            List<String> missing, List<String> damaged, List<String> unreferenced, int attachments) {

        public Report {
            missing = List.copyOf(missing);
            damaged = List.copyOf(damaged);
            unreferenced = List.copyOf(unreferenced);
        }

        /** True when nothing is missing or damaged (the condition after a restore). */
        public boolean clean() {
            return missing.isEmpty() && damaged.isEmpty();
        }
    }

    private final AttachmentMaintenanceService maintenance;
    private final AttachmentStore store;
    private final AttachmentProperties properties;
    private final Clock clock;

    public AttachmentCheck(AttachmentMaintenanceService maintenance, AttachmentStore store, AttachmentProperties properties,
            Clock clock) {
        this.maintenance = maintenance;
        this.store = store;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Runs the check.
     *
     * @param moveFiles true to move the files named above; false for a read-only run (the admin page), which reports what a run would move
     */
    public Report run(boolean moveFiles) {
        Instant now = clock.instant();
        Duration grace = properties.orphanGrace();
        int orphans = 0;
        int waiting = 0;
        int replaced = 0;
        int restored = 0;
        for (AttachmentStore.StoredFile stored : store.list()) {
            if (!maintenance.holdsStorageKey(stored.key())) {
                if (Duration.between(stored.lastModified(), now).compareTo(grace) >= 0) {
                    orphans++;
                    if (moveFiles) {
                        store.quarantine(stored.key());
                    }
                } else {
                    waiting++;
                }
            } else if (isReplacedLongEnough(stored.key(), now, grace)) {
                replaced++;
                if (moveFiles) {
                    store.quarantine(stored.key());
                }
            }
        }
        for (AttachmentStore.StoredFile stored : store.listQuarantined()) {
            if (maintenance.holdsStorageKey(stored.key()) && !maintenance.isReplaced(stored.key())) {
                restored++;
                if (moveFiles) {
                    store.restore(stored.key());
                }
            }
        }
        List<String> missing = new ArrayList<>();
        List<String> damaged = new ArrayList<>();
        List<String> unreferenced = new ArrayList<>();
        int looked = 0;
        String after = "";
        List<Attachment> page = maintenance.attachments(after, PAGE);
        while (!page.isEmpty()) {
            for (Attachment attachment : page) {
                looked++;
                inspect(attachment, missing, damaged);
                if (!maintenance.isReferenced(attachment.id())) {
                    unreferenced.add(attachment.id());
                }
                after = attachment.id();
            }
            page = maintenance.attachments(after, PAGE);
        }
        return new Report(orphans, waiting, replaced, restored, store.listQuarantined().size(), missing, damaged, unreferenced,
                looked);
    }

    /** A replaced file waits for the grace, counted from the row of the file that replaced it (a reader may still hold the old one). */
    private boolean isReplacedLongEnough(StorageKey key, Instant now, Duration grace) {
        return maintenance.replacedAt(key).filter(at -> Duration.between(at, now).compareTo(grace) >= 0).isPresent();
    }

    private void inspect(Attachment attachment, List<String> missing, List<String> damaged) {
        maintenance.currentFile(attachment.id()).filter(file -> store.exists(file.storageKey()))
                .ifPresentOrElse(file -> verify(attachment, file, damaged), () -> missing.add(attachment.id()));
    }

    private void verify(Attachment attachment, AttachmentFile file, List<String> damaged) {
        try {
            AttachmentStore.Content content = store.verify(file.storageKey(), file.keyId());
            if (content.sizeBytes() != attachment.sizeBytes() || !Objects.equals(content.sha256(), attachment.contentSha256())) {
                damaged.add(attachment.id());
            }
        } catch (InfrastructureException failure) {
            if (failure.code() == InfrastructureCode.ATTACHMENT_CORRUPTED) {
                damaged.add(attachment.id());
            } else {
                throw failure;
            }
        }
    }

    /** Empties the quarantine folder (the explicit command only) and records the count in the owner's audit trail. */
    public int purgeQuarantine(OwnerIdentity actor) {
        int deleted = store.purgeQuarantine();
        maintenance.recordPurge(actor, deleted);
        return deleted;
    }
}
