package org.pgledger.domain.error;

/** Error tokens of category LOOKUP. */
public enum LookupCode implements ErrorCode {
    NOT_FOUND,
    /** No upload or attachment of this id (an upload or attachment of another author is answered the same way). Argument: the id. */
    ARTIFACT_NOT_FOUND;

    @Override
    public ErrorCategory category() {
        return ErrorCategory.LOOKUP;
    }
}
