package org.pgledger.domain.error;

/** Error tokens of category VALIDATION. */
public enum ValidationCode implements ErrorCode {
    VALIDATION_FAILED,
    REASON_INVALID,
    CODE_REQUIRED,
    CODE_INVALID,
    ID_PREFIX_MISMATCH,
    /** An upload purpose that the ledger does not support (it supports only evidence). Argument: the purpose. */
    UNSUPPORTED_PURPOSE;

    @Override
    public ErrorCategory category() {
        return ErrorCategory.VALIDATION;
    }
}
