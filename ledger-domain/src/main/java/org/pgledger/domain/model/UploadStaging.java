package org.pgledger.domain.model;

import java.time.Instant;
import org.pgledger.domain.rule.UploadState;
import org.pgledger.domain.value.AuthorId;
import org.pgledger.domain.value.Sha256;
import org.pgledger.domain.value.StorageKey;

/**
 * An upload that is received chunk by chunk. The row is the state of the upload: how many bytes the file holds
 * ({@code storedBytes}, the length of the file that the database knows of), how many plain bytes were received, the number of the next
 * chunk and the hash of the last one (to tell a repeated chunk from a different one).
 *
 * @param lastChunkSha256 null before the first chunk
 */
public record UploadStaging(String id, AuthorId owner, String operationKey, String fileName, String mediaType,
        long declaredSize, Sha256 declaredSha256, long receivedBytes, long storedBytes, long nextSequence,
        Sha256 lastChunkSha256, StorageKey storageKey, String keyId, UploadState state, Instant expiresAt,
        Instant createdAt, Instant updatedAt) {
}
