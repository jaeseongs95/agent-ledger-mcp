// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.domain.model;

import java.time.Instant;
import org.agentledger.domain.rule.UploadState;
import org.agentledger.domain.value.AuthorId;
import org.agentledger.domain.value.Sha256;
import org.agentledger.domain.value.StorageKey;

/** One file of an attachment bundle upload in progress (the one mutable table of attachments). */
public record UploadStaging(
        String id,
        String attachmentId,
        int seq,
        AuthorId owner,
        String operationKey,
        String fileName,
        String mediaType,
        long declaredSize,
        Sha256 declaredSha256,
        long receivedBytes,
        long storedBytes,
        long nextSequence,
        Sha256 lastChunkSha256,
        StorageKey storageKey,
        String keyId,
        UploadState state,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt) {
}
