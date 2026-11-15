package org.pgledger.domain.model;

import java.time.Instant;
import org.pgledger.domain.value.StorageKey;

/**
 * One file of an attachment (an append-only row): where the body is kept and the id of the key it was written with. The file of an
 * attachment is its latest row; the finish adds the first row and a re-encryption adds the next one, so an older row names a replaced
 * file. No foreign key: the application checks the attachment id when it writes the row.
 *
 * @param keyId the name of the key file the file was written with
 */
public record AttachmentFile(String id, String attachmentId, StorageKey storageKey, String keyId, Instant createdAt) {
}
