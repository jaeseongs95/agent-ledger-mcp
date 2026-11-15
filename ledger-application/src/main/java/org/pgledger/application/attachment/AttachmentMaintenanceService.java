package org.pgledger.application.attachment;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.pgledger.application.admin.OwnerAuditWriter;
import org.pgledger.database.repository.AttachmentFileRepository;
import org.pgledger.database.repository.AttachmentRepository;
import org.pgledger.database.repository.UploadStagingRepository;
import org.pgledger.domain.error.LookupCode;
import org.pgledger.domain.error.LookupException;
import org.pgledger.domain.model.Attachment;
import org.pgledger.domain.model.AttachmentFile;
import org.pgledger.domain.model.UploadStaging;
import org.pgledger.domain.rule.OwnerAction;
import org.pgledger.domain.value.OwnerIdentity;
import org.pgledger.domain.value.StorageKey;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.IdGenerator;

/**
 * The database half of the reaper, the check, the start check and the rekey: every method is one use case and one transaction, and
 * none touches a file (the callers do the file work around them, principle 4.4). The removal of an upload is one conditional DELETE
 * whose condition is evaluated on the row as it is now, so a chunk or a finish that came after the list was read makes it affect no row
 * and the upload stays; the caller removes the file only after one row was deleted.
 */
@Service
@Transactional
@ConditionalOnAttachments
public class AttachmentMaintenanceService {

    private final UploadStagingRepository uploads;
    private final AttachmentRepository attachments;
    private final AttachmentFileRepository files;
    private final OwnerAuditWriter audit;
    private final IdGenerator ids;
    private final Clock clock;

    public AttachmentMaintenanceService(UploadStagingRepository uploads, AttachmentRepository attachments,
            AttachmentFileRepository files, OwnerAuditWriter audit, IdGenerator ids, Clock clock) {
        this.uploads = uploads;
        this.attachments = attachments;
        this.files = files;
        this.audit = audit;
        this.ids = ids;
        this.clock = clock;
    }

    // ------------------------------------------------------------------------------------------------ the reaper

    /** The uploads that had expired at {@code now}, oldest first. */
    public List<UploadStaging> expired(Instant now, int limit) {
        return uploads.findExpired(now, limit);
    }

    /** One conditional DELETE (design 7): true when this call removed the row, and only then the file may be removed. */
    public boolean removeExpired(String id, Instant now) {
        return uploads.deleteExpired(id, now);
    }

    /** The rows of finished uploads that are left to be removed. */
    public List<UploadStaging> completed(int limit) {
        return uploads.findCompleted(limit);
    }

    /** Removes the row of a finished upload; its file belongs to the attachment and stays. */
    public boolean removeCompleted(String id) {
        return uploads.deleteCompleted(id);
    }

    // ------------------------------------------------------------------------------------------------ the check

    /** True when an upload or a file (current or replaced) has this storage key: the file is not a file without a row. */
    public boolean holdsStorageKey(StorageKey key) {
        return uploads.holdsStorageKey(key) || files.holdsStorageKey(key);
    }

    /** True when the key is a replaced file (a row that is not the latest of its attachment). */
    public boolean isReplaced(StorageKey key) {
        return files.isReplaced(key);
    }

    /** The attachments in id order after {@code afterId}. */
    public List<Attachment> attachments(String afterId, int limit) {
        return attachments.page(afterId, limit);
    }

    /** The current file of an attachment: its latest row; empty when there is none (the attachment has no file). */
    public Optional<AttachmentFile> currentFile(String attachmentId) {
        return files.currentOf(attachmentId);
    }

    /** The current files in the order of the attachment id after {@code afterAttachmentId}. */
    public List<AttachmentFile> currentFiles(String afterAttachmentId, int limit) {
        return files.currentPage(afterAttachmentId, limit);
    }

    /** True when a history row points to the attachment. */
    public boolean isReferenced(String attachmentId) {
        return attachments.isReferenced(attachmentId);
    }

    /** The ids of the key files that the uploads and the current files need (never the keys). */
    public Set<String> keyIdsInUse() {
        Set<String> keyIds = new LinkedHashSet<>(files.keyIdsInUse());
        keyIds.addAll(uploads.keyIdsInUse());
        return Set.copyOf(keyIds);
    }

    // ------------------------------------------------------------------------------------------------ owner operations

    /**
     * Makes the file written by a rekey the current file of the attachment: one INSERT into {@code attachment_file} (and the audit row of
     * the owner), after the entry point wrote and forced the new file. Nothing is added when the current file is no longer the one that was
     * rewritten (another rekey was first): the new file then stays a file without a row.
     *
     * @param expectedCurrent the storage key of the file that was rewritten
     * @return true when the row was added
     */
    public boolean addRekeyedFile(OwnerIdentity actor, String attachmentId, StorageKey expectedCurrent, StorageKey newKey,
            String keyId) {
        attachments.findById(attachmentId).orElseThrow(() -> new LookupException(LookupCode.NOT_FOUND,
                List.of("attachment", attachmentId)));
        boolean stillCurrent = files.currentOf(attachmentId).filter(file -> Objects.equals(file.storageKey(), expectedCurrent))
                .isPresent();
        if (!stillCurrent) {
            return false;
        }
        files.insert(new AttachmentFile(ids.generateId().toString(), attachmentId, newKey, keyId, clock.instant()));
        audit.record(actor, OwnerAction.ATTACHMENT_REKEY, attachmentId, "key " + keyId);
        return true;
    }

    /** Records that the quarantine folder was emptied (the number of files), for the owner's audit trail. */
    public void recordPurge(OwnerIdentity actor, int count) {
        audit.record(actor, OwnerAction.ORPHANS_PURGED, "attachments", count + " files");
    }
}
