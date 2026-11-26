// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.domain.model;

import org.veridger.domain.value.Sha256;

/** One file of a kept attachment bundle, keyed by {@code (attachmentId, seq)}: the facts of the plain bytes as declared and verified. */
public record AttachmentFile(
        String attachmentId,
        int seq,
        String fileName,
        String mediaType,
        long sizeBytes,
        Sha256 contentSha256) {
}
