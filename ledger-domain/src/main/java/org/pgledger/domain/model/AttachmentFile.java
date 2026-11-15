package org.pgledger.domain.model;

import java.time.Instant;
import org.pgledger.domain.value.StorageKey;

/**
 * One file of an attachment (an append-only row; the key is the attachment id and {@code seq}, a composite primary key without an
 * id column of its own): where the body is kept and the id of the key it was written with. The file of an attachment is its row with
 * the largest {@code seq}; the finish adds seq 1 and a re-encryption adds the next number, so a smaller number names a replaced file.
 * No foreign key: the application checks the attachment id when it writes the row.
 *
 * @param keyId the name of the key file the file was written with
 */
public record AttachmentFile(String attachmentId, long seq, StorageKey storageKey, String keyId, Instant createdAt) {
}
