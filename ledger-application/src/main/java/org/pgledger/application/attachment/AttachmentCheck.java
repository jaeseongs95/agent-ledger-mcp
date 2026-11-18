package org.pgledger.application.attachment;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.pgledger.application.attachment.AttachmentMaintenanceService.KeyState;
import org.pgledger.application.attachment.AttachmentMaintenanceService.KeyUse;
import org.pgledger.application.attachment.AttachmentMaintenanceService.StoredEntry;
import org.pgledger.application.attachment.AttachmentStore.Content;
import org.pgledger.application.attachment.AttachmentStore.StoredFile;
import org.pgledger.domain.error.InfrastructureException;
import org.pgledger.domain.value.OwnerIdentity;
import org.springframework.stereotype.Component;

/**
 * Reconciles the attachment directory, quarantine folder, keys, and bundle metadata rows (ATA-008). Moves nothing when
 * {@code move = false}; never deletes automatically except on explicit {@link #purgeQuarantine(OwnerIdentity)}.
 */
@Component
@ConditionalOnAttachments
public class AttachmentCheck {

    public record Report(
            int attachments,
            int orphansQuarantined,
            int orphansWaiting,
            int replacedQuarantined,
            int restored,
            int quarantined,
            List<String> missing,
            List<String> damaged,
            List<String> unreferenced) {

        public Report {
            missing = List.copyOf(missing);
            damaged = List.copyOf(damaged);
            unreferenced = List.copyOf(unreferenced);
        }

        public boolean clean() {
            return missing.isEmpty() && damaged.isEmpty();
        }
    }

    private final AttachmentMaintenanceService maintenance;
    private final AttachmentStore store;
    private final AttachmentProperties properties;
    private final Clock clock;

    public AttachmentCheck(
            AttachmentMaintenanceService maintenance,
            AttachmentStore store,
            AttachmentProperties properties,
            Clock clock) {
        this.maintenance = maintenance;
        this.store = store;
        this.properties = properties;
        this.clock = clock;
    }

    public Report run(boolean move) {
        store.prepare();
        Instant now = clock.instant();
        int restored = 0;
        for (StoredFile file : store.listQuarantined()) {
            if (maintenance.keyUse(file.key()).state() == KeyState.IN_USE) {
                if (move) {
                    store.restore(file.key());
                }
                restored++;
            }
        }
        int orphansQuarantined = 0;
        int orphansWaiting = 0;
        int replacedQuarantined = 0;
        for (StoredFile file : store.list()) {
            KeyUse use = maintenance.keyUse(file.key());
            switch (use.state()) {
                case IN_USE -> {
                }
                case REPLACED -> {
                    if (!now.isBefore(use.replacedAt().plus(properties.orphanGrace()))) {
                        if (move) {
                            store.quarantine(file.key());
                        }
                        replacedQuarantined++;
                    }
                }
                case UNREFERENCED -> {
                    if (!now.isBefore(file.lastModified().plus(properties.orphanGrace()))) {
                        if (move) {
                            store.quarantine(file.key());
                        }
                        orphansQuarantined++;
                    } else {
                        orphansWaiting++;
                    }
                }
            }
        }
        Set<String> attachmentIds = new LinkedHashSet<>();
        Set<String> missing = new LinkedHashSet<>();
        Set<String> damaged = new LinkedHashSet<>();
        for (StoredEntry entry : maintenance.storedEntries()) {
            String attachmentId = entry.attachment().id();
            attachmentIds.add(attachmentId);
            if (entry.file() == null) {
                damaged.add(attachmentId);
                continue;
            }
            if (entry.current() == null || !store.exists(entry.current().storageKey())) {
                missing.add(attachmentId);
                continue;
            }
            try {
                Content verified = store.verify(entry.current().storageKey(), entry.current().keyId());
                if (verified.sizeBytes() != entry.file().sizeBytes()
                        || !Objects.equals(verified.sha256(), entry.file().contentSha256())) {
                    damaged.add(attachmentId);
                }
            } catch (InfrastructureException corrupted) {
                damaged.add(attachmentId);
            }
        }
        damaged.removeAll(missing);
        List<String> unreferenced = maintenance.unreferencedAttachmentIds();
        int quarantined = store.listQuarantined().size();
        return new Report(
                attachmentIds.size(),
                orphansQuarantined,
                orphansWaiting,
                replacedQuarantined,
                restored,
                quarantined,
                List.copyOf(missing),
                List.copyOf(damaged),
                unreferenced);
    }

    public int purgeQuarantine(OwnerIdentity actor) {
        int deleted = store.purgeQuarantine();
        if (deleted > 0) {
            maintenance.auditOrphansPurged(actor, deleted);
        }
        return deleted;
    }
}
