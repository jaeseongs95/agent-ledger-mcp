// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.domain.rule;

import org.pgledger.domain.value.IssueId;

/** The issue a MERGED transition points at, with the status it currently has. */
public record MergeTarget(IssueId id, IssueStatus status) {
}
