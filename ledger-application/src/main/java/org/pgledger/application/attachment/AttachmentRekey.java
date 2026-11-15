package org.pgledger.application.attachment;

import java.util.List;
import java.util.Objects;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationReason;
import org.pgledger.domain.model.AttachmentFile;
import org.pgledger.domain.value.OwnerIdentity;
import org.pgledger.domain.value.StorageKey;
import org.springframework.stereotype.Component;

/**
 * Writes the bodies that were written with one key again under the active key (common design 4.3, {@code attachments-rekey --from
 * <keyId>}). For every current file of the key the entry point opens and verifies the old file, writes a new file under a new storage
 * key and the active key, forces it, and only then one service method adds the row of the new file to {@code attachment_file}. From
 * that row on the old file is a replaced file, which the check moves to the quarantine. A run that dies after the new file and before
 * its row leaves the old row valid and the new file as a file without a row. The class opens no transaction.
 */
@Component
@ConditionalOnAttachments
public class AttachmentRekey {

    private static final int PAGE = 200;

    /**
     * What a run did.
     *
     * @param rewritten files that were written again and whose row was added
     * @param skipped   files that another rekey had replaced meanwhile (their new file is a file without a row)
     */
    public record Result(int rewritten, int skipped) {
    }

    private final AttachmentMaintenanceService maintenance;
    private final AttachmentStore store;
    private final StorageKeys storageKeys;
    private final AttachmentProperties properties;

    public AttachmentRekey(AttachmentMaintenanceService maintenance, AttachmentStore store, StorageKeys storageKeys,
            AttachmentProperties properties) {
        this.maintenance = maintenance;
        this.store = store;
        this.storageKeys = storageKeys;
        this.properties = properties;
    }

    /**
     * @param fromKeyId the key whose files are written again; it has to differ from the active key
     * @throws ValidationException when {@code fromKeyId} is the active key
     */
    public Result rekey(OwnerIdentity actor, String fromKeyId) {
        String toKeyId = properties.activeKeyId();
        if (Objects.equals(fromKeyId, toKeyId)) {
            throw ValidationException.invalid("from", ValidationReason.MUST_DIFFER_FROM, toKeyId);
        }
        int rewritten = 0;
        int skipped = 0;
        String after = "";
        List<AttachmentFile> page = maintenance.currentFiles(after, PAGE);
        while (!page.isEmpty()) {
            for (AttachmentFile file : page) {
                after = file.attachmentId();
                if (!Objects.equals(file.keyId(), fromKeyId)) {
                    continue;
                }
                StorageKey fresh = storageKeys.next();
                store.rekey(file.storageKey(), fromKeyId, fresh, toKeyId);
                if (maintenance.addRekeyedFile(actor, file.attachmentId(), file.storageKey(), fresh, toKeyId)) {
                    rewritten++;
                } else {
                    skipped++;
                }
            }
            page = maintenance.currentFiles(after, PAGE);
        }
        return new Result(rewritten, skipped);
    }
}
