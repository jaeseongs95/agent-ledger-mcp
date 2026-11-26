// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.domain.error;

/** Error tokens of category TRANSITION. */
public enum TransitionCode implements ErrorCode {
    TRANSITION_INVALID,
    DELIVERABLE_REQUIRED,
    DELIVERABLE_ALREADY_RECORDED,
    EVIDENCE_REQUIRED,
    MERGE_TARGET_INVALID,
    MERGED_ISSUE,
    /** {@code record_deliverable}, {@code save}, or {@code record_case} on a VERIFIED/MET/NOT_MET item: a reviewer must reopen it first. Argument: the item id. */
    VERIFIED_ENTITY,
    SAME_SESSION,
    PROJECT_MOVE_FORBIDDEN,
    NOTHING_CHANGES;

    @Override
    public ErrorCategory category() {
        return ErrorCategory.TRANSITION;
    }
}
