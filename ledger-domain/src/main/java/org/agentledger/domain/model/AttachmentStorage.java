// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.domain.model;

import java.time.Instant;
import org.agentledger.domain.value.StorageKey;

/**
 * One stored encrypted copy of a file of a bundle, keyed by {@code (attachmentId, seq, rev)}: rev 0 is written when the upload
 * closes and each re-encryption adds rev {@code +1}; the current copy of a file is the row of the highest {@code rev}.
 */
public record AttachmentStorage(
        String attachmentId,
        int seq,
        int rev,
        StorageKey storageKey,
        String keyId,
        Instant createdAt) {
}
