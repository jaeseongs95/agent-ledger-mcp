// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.database.repository;

import java.time.Instant;
import org.veridger.domain.rule.IssueStatus;
import org.veridger.domain.value.CodeValue;
import org.veridger.domain.value.IssueId;

/**
 * The columns an issue list shows. {@code owner} is the owner of the issue, or the actor of its latest deliverable
 * when the issue has no explicit owner.
 */
public record IssueSummary(IssueId id, String title, CodeValue category, CodeValue severity, IssueStatus status,
        IssueId mergedInto, String owner, long version, Instant createdAt, Instant updatedAt) {
}
