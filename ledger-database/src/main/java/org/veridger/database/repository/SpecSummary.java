// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.database.repository;

import java.time.Instant;
import org.veridger.domain.rule.SpecStatus;
import org.veridger.domain.value.SpecId;

/** The columns a specification list shows. */
public record SpecSummary(SpecId id, SpecId parentId, String title, SpecStatus status, String priority,
        String ownerActorId, long version, Instant createdAt, Instant updatedAt) {
}
