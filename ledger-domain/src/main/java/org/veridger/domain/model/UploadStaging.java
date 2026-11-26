// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.domain.model;

import java.time.Instant;
import org.veridger.domain.rule.UploadState;
import org.veridger.domain.value.AuthorId;
import org.veridger.domain.value.Sha256;
import org.veridger.domain.value.StorageKey;

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
