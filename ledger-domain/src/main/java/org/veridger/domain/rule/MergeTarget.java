// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.domain.rule;

import org.veridger.domain.value.IssueId;

/** The issue a MERGED transition points at, with the status it currently has. */
public record MergeTarget(IssueId id, IssueStatus status) {
}
