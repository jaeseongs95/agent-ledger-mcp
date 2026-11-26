// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.database.repository;

import org.agentledger.domain.value.IssueId;

/**
 * Repository fragment of issues: serializes concurrent merge and verification transitions across two issue rows without
 * altering {@code version} or {@code updated_at}.
 */
public interface IssueMergeGuard {

    /**
     * Pins {@code id} for this transaction without altering {@code version} or {@code updated_at} (used by multi-op
     * batches to acquire all issue locks in ascending id order before lower-tier locks).
     *
     * @return true when the issue exists
     */
    boolean pinRow(IssueId id);

    /**
     * Guards that {@code id} is not already {@code MERGED} before entering {@code MERGED}.
     *
     * @return true when the issue exists and is not {@code MERGED}
     */
    boolean guardMergeSource(IssueId id);

    /**
     * Guards that merge target {@code id} is neither {@code MERGED} nor {@code VERIFIED}.
     *
     * @return true when the target exists and is eligible to receive a merge
     */
    boolean guardMergeTarget(IssueId id);

    /**
     * Guards that {@code id} is still {@code RESOLVED} before scanning its merge chain for verification.
     *
     * @return true when the issue exists and is {@code RESOLVED}
     */
    boolean guardVerifyCandidate(IssueId id);
}
