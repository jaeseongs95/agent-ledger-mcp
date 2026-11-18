package org.pgledger.domain.error;

/** Error tokens of category TRANSITION. */
public enum TransitionCode implements ErrorCode {
    TRANSITION_INVALID,
    FIX_REQUIRED,
    FIX_ALREADY_RECORDED,
    EVIDENCE_REQUIRED,
    MERGE_TARGET_INVALID,
    MERGED_DEFECT,
    /** {@code record_fix}, {@code save}, or {@code record_case} of a VERIFIED defect: a reviewer has to reopen it first. Argument: the defect id. */
    VERIFIED_DEFECT,
    SAME_SESSION,
    PROJECT_MOVE_FORBIDDEN,
    NOTHING_CHANGES;

    @Override
    public ErrorCategory category() {
        return ErrorCategory.TRANSITION;
    }
}
