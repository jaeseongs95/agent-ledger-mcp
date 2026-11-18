package org.pgledger.application.attachment;

import java.util.Objects;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationReason;
import org.pgledger.domain.model.AttachmentStorage;
import org.pgledger.domain.value.OwnerIdentity;
import org.pgledger.domain.value.StorageKey;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * Re-encrypts every current copy written under {@code fromKeyId} into the active key, adding rev {@code highest + 1} in
 * {@code attachment_storage}.
 */
@Component
@ConditionalOnAttachments
public class AttachmentRekey {

    /**
     * Summary of a rekey run.
     *
     * @param rewritten copies re-encrypted and recorded
     * @param skipped   copies replaced concurrently by another run
     */
    public record Result(int rewritten, int skipped) {
    }

    private final AttachmentMaintenanceService maintenance;
    private final AttachmentStore store;
    private final StorageKeys storageKeys;
    private final AttachmentProperties properties;

    public AttachmentRekey(
            AttachmentMaintenanceService maintenance,
            AttachmentStore store,
            StorageKeys storageKeys,
            AttachmentProperties properties) {
        this.maintenance = maintenance;
        this.store = store;
        this.storageKeys = storageKeys;
        this.properties = properties;
    }

    public Result rekey(OwnerIdentity actor, String fromKeyId) {
        String activeKeyId = properties.activeKeyId();
        if (activeKeyId == null || activeKeyId.isBlank()) {
            throw new IllegalStateException("ledger.attachments.active-key-id is not set");
        }
        if (fromKeyId == null || fromKeyId.isBlank()) {
            throw ValidationException.invalid("from", ValidationReason.REQUIRED);
        }
        if (Objects.equals(fromKeyId, activeKeyId)) {
            throw ValidationException.invalid("from", ValidationReason.MUST_DIFFER_FROM, "ledger.attachments.active-key-id");
        }
        int rewritten = 0;
        int skipped = 0;
        for (AttachmentStorage copy : maintenance.currentCopiesOfKey(fromKeyId)) {
            StorageKey freshKey = storageKeys.next();
            store.rekey(copy.storageKey(), copy.keyId(), freshKey, activeKeyId);
            try {
                if (maintenance.addRekeyedFile(copy.attachmentId(), copy.seq(), copy.storageKey(), freshKey,
                        activeKeyId, actor)) {
                    rewritten++;
                } else {
                    skipped++;
                }
            } catch (DuplicateKeyException lostRace) {
                skipped++;
            }
        }
        return new Result(rewritten, skipped);
    }
}
