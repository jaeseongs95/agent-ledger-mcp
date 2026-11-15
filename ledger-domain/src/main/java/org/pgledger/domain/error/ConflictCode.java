package org.pgledger.domain.error;

/** Error tokens of category CONFLICT. */
public enum ConflictCode implements ErrorCode {
    VERSION_CONFLICT,
    REQUEST_CONFLICT,
    REQUEST_ID_LEGACY,
    DUPLICATE_KEY,
    KEY_REPLACEMENT_NOT_CONFIRMED,
    /** A grant for an author that has no live session key: no write of this author could be accepted. Argument: the author. */
    SESSION_KEY_REQUIRED,
    /** A chunk that is out of order, or another chunk of the number that was received. */
    ARTIFACT_CHUNK_CONFLICT,
    /** Another request holds the file lock of the upload; nothing waits and nothing is retried. */
    ARTIFACT_BUSY,
    /** The stored file is longer than the row says: a chunk was appended and not recorded; send it again. */
    FINISH_PENDING_CHUNK,
    /** The operation key of the author belongs to an upload with another file name, size or SHA-256. Argument: the key. */
    ARTIFACT_OPERATION_CONFLICT,
    /** A size limit, the budget or the number of open uploads is exceeded. Argument: which one. */
    ARTIFACT_QUOTA,
    /** The received size is not the declared one (or a chunk would pass it). Arguments: received, declared. */
    ARTIFACT_SIZE_MISMATCH,
    /** The SHA-256 of the received bytes is not the declared one; the bytes are kept. */
    ATTACHMENT_HASH_MISMATCH,
    /** The SHA-256 of a chunk is not the one sent with it. */
    CHUNK_HASH_MISMATCH;

    @Override
    public ErrorCategory category() {
        return ErrorCategory.CONFLICT;
    }
}
