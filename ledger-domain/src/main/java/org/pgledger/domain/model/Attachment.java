package org.pgledger.domain.model;

import java.time.Instant;
import org.pgledger.domain.value.AuthorId;
import org.pgledger.domain.value.Sha256;

/**
 * A finished attachment: written once, never changed and never deleted (the application account has no UPDATE and no DELETE on
 * the table). The body is a file that {@link AttachmentFile} names; this row holds the metadata.
 *
 * @param contentSha256 the SHA-256 of the plain bytes
 * @param createdBy     the author that uploaded it: a record may point to it only when it is written by the same author
 */
public record Attachment(String id, String fileName, String mediaType, long sizeBytes, Sha256 contentSha256,
        AuthorId createdBy, Instant createdAt) {
}
