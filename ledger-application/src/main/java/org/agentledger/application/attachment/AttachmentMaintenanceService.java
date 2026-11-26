// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.application.attachment;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.agentledger.database.repository.AttachmentFileRepository;
import org.agentledger.database.repository.AttachmentRepository;
import org.agentledger.database.repository.AttachmentStorageRepository;
import org.agentledger.database.repository.Counter;
import org.agentledger.database.repository.IdAllocator;
import org.agentledger.database.repository.OwnerAuditRepository;
import org.agentledger.database.repository.UploadStagingRepository;
import org.agentledger.domain.error.LookupCode;
import org.agentledger.domain.error.LookupException;
import org.agentledger.domain.model.Attachment;
import org.agentledger.domain.model.AttachmentFile;
import org.agentledger.domain.model.AttachmentStorage;
import org.agentledger.domain.model.OwnerAuditEntry;
import org.agentledger.domain.model.UploadStaging;
import org.agentledger.domain.rule.OwnerAction;
import org.agentledger.domain.value.OwnerIdentity;
import org.agentledger.domain.value.StorageKey;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional database operations used by {@link AttachmentReaper}, {@link AttachmentCheck}, {@link AttachmentRekey},
 * and {@link AttachmentStartCheck} across the 4-table bundle schema.
 */
@Service
@Transactional
@ConditionalOnAttachments
public class AttachmentMaintenanceService {

    /** Whether a storage key on disk is active, replaced by a newer rev, or unreferenced by any row. */
    public enum KeyState {
        IN_USE,
        REPLACED,
        UNREFERENCED
    }

    /** Storage key status plus replacement timestamp (populated when {@link KeyState#REPLACED}). */
    public record KeyUse(KeyState state, Instant replacedAt) {
    }

    /** One bundle file paired with its highest-rev storage copy; {@code file} or {@code current} is null if missing. */
    public record StoredEntry(Attachment attachment, AttachmentFile file, AttachmentStorage current) {
    }

    private final UploadStagingRepository uploads;
    private final AttachmentRepository attachments;
    private final AttachmentFileRepository files;
    private final AttachmentStorageRepository storages;
    private final OwnerAuditRepository audit;
    private final IdAllocator ids;
    private final Clock clock;

    public AttachmentMaintenanceService(
            UploadStagingRepository uploads,
            AttachmentRepository attachments,
            AttachmentFileRepository files,
            AttachmentStorageRepository storages,
            OwnerAuditRepository audit,
            IdAllocator ids,
            Clock clock) {
        this.uploads = uploads;
        this.attachments = attachments;
        this.files = files;
        this.storages = storages;
        this.audit = audit;
        this.ids = ids;
        this.clock = clock;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    @Transactional(readOnly = true)
    public List<UploadStaging> expiredUploads(Instant now, int limit) {
        return uploads.expired(now, limit);
    }

    @Transactional(readOnly = true)
    public List<UploadStaging> completedUploads(int limit) {
        return uploads.completed(limit);
    }

    @Transactional
    public boolean removeExpired(String uploadId, Instant now) {
        return uploads.deleteExpired(uploadId, now);
    }

    @Transactional
    public boolean removeCompleted(String uploadId) {
        return uploads.deleteCompleted(uploadId);
    }

    @Transactional(readOnly = true)
    public Set<String> keyIdsInUse() {
        Set<String> ids = new LinkedHashSet<>(uploads.openKeyIds());
        ids.addAll(storages.currentKeyIds());
        return Set.copyOf(ids);
    }

    @Transactional(readOnly = true)
    public List<AttachmentStorage> currentCopiesOfKey(String keyId) {
        return storages.currentsOfKey(keyId);
    }

    @Transactional(readOnly = true)
    public List<StoredEntry> storedEntries() {
        Map<FileRef, AttachmentStorage> currents = new HashMap<>();
        for (AttachmentStorage copy : storages.currents()) {
            currents.put(new FileRef(copy.attachmentId(), copy.seq()), copy);
        }
        Map<String, List<AttachmentFile>> byAttachment = new HashMap<>();
        for (AttachmentFile file : files.all()) {
            byAttachment.computeIfAbsent(file.attachmentId(), ignored -> new ArrayList<>()).add(file);
        }
        List<StoredEntry> entries = new ArrayList<>();
        for (Attachment attachment : attachments.all()) {
            List<AttachmentFile> bundleFiles = byAttachment.getOrDefault(attachment.id(), List.of());
            if (bundleFiles.isEmpty()) {
                entries.add(new StoredEntry(attachment, null, null));
                continue;
            }
            for (AttachmentFile file : bundleFiles) {
                entries.add(new StoredEntry(attachment, file, currents.get(new FileRef(file.attachmentId(), file.seq()))));
            }
        }
        return List.copyOf(entries);
    }

    @Transactional(readOnly = true)
    public List<String> unreferencedAttachmentIds() {
        return attachments.unreferencedIds();
    }

    @Transactional(readOnly = true)
    public boolean holdsStorageKey(StorageKey storageKey) {
        return storages.holdsStorageKey(storageKey) || uploads.holdsStorageKey(storageKey);
    }

    @Transactional(readOnly = true)
    public KeyUse keyUse(StorageKey storageKey) {
        Optional<AttachmentStorage> copy = storages.byStorageKey(storageKey);
        if (copy.isEmpty()) {
            return uploads.holdsStorageKey(storageKey)
                    ? new KeyUse(KeyState.IN_USE, null)
                    : new KeyUse(KeyState.UNREFERENCED, null);
        }
        AttachmentStorage row = copy.orElseThrow();
        Optional<AttachmentStorage> current = storages.current(row.attachmentId(), row.seq());
        if (current.map(c -> Objects.equals(c.storageKey(), storageKey)).orElse(false)) {
            return new KeyUse(KeyState.IN_USE, null);
        }
        Instant replacedAt = storages.at(row.attachmentId(), row.seq(), row.rev() + 1)
                .map(AttachmentStorage::createdAt)
                .orElse(row.createdAt());
        return new KeyUse(KeyState.REPLACED, replacedAt);
    }

    /**
     * Inserts the next revision of a re-encrypted file copy ({@code rev = current.rev() + 1}) when the current copy is still
     * {@code expectedCurrent}. If another rekey already replaced it before our lookup, returns {@code false}; two concurrent
     * rekeys reading the same current copy collide on {@code (attachment_id, seq, rev)} and the loser fails with duplicate key.
     */
    @Transactional
    public boolean addRekeyedFile(String attachmentId, int seq, StorageKey expectedCurrent, StorageKey storageKey,
            String keyId, OwnerIdentity actor) {
        attachments.findById(attachmentId).orElseThrow(() -> new LookupException(LookupCode.ARTIFACT_NOT_FOUND,
                List.of(attachmentId)));
        return storages.current(attachmentId, seq)
                .filter(current -> Objects.equals(current.storageKey(), expectedCurrent))
                .map(current -> {
                    Instant now = now();
                    storages.insert(new AttachmentStorage(attachmentId, seq, current.rev() + 1, storageKey, keyId, now));
                    audit.insert(new OwnerAuditEntry(ids.next(Counter.OWNER_AUDIT), now, actor.value(),
                            OwnerAction.ATTACHMENT_REKEY, attachmentId, "key " + keyId));
                    return true;
                })
                .orElse(false);
    }

    @Transactional
    public void auditOrphansPurged(OwnerIdentity actor, int count) {
        audit.insert(new OwnerAuditEntry(ids.next(Counter.OWNER_AUDIT), now(), actor.value(),
                OwnerAction.ORPHANS_PURGED, "orphans", count + " files"));
    }

    private record FileRef(String attachmentId, int seq) {
    }
}
