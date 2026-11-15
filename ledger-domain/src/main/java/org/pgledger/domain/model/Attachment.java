package org.pgledger.domain.model;

import java.time.Instant;
import org.pgledger.domain.value.AuthorId;
import org.pgledger.domain.value.Sha256;
import org.pgledger.domain.value.StorageKey;

/**
 * A finished attachment: written once, never changed and never deleted (the application account has no UPDATE and no DELETE on
 * the table). The body is a file; this row holds its metadata and the name of the file.
 *
 * @param contentSha256 the SHA-256 of the plain bytes
 * @param keyId         the id of the key the file was written with (the name of the key file)
 * @param createdBy     the author that uploaded it: a record may point to it only when it is written by the same author
 */
public record Attachment(String id, String fileName, String mediaType, long sizeBytes, Sha256 contentSha256,
        StorageKey storageKey, String keyId, AuthorId createdBy, Instant createdAt) {
}
