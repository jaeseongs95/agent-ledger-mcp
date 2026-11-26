// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.domain.model;

import java.time.Instant;
import org.veridger.domain.value.AuthorId;

/** One kept attachment bundle: who created it and when. Files and stored copies live in {@link AttachmentFile} and {@link AttachmentStorage}. */
public record Attachment(String id, AuthorId createdBy, Instant createdAt) {
}
