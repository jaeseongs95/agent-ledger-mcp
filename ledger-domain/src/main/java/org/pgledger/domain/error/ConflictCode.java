// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.domain.error;

/** Error tokens of category CONFLICT. */
public enum ConflictCode implements ErrorCode {
    VERSION_CONFLICT,
    OPERATION_CONFLICT,
    DUPLICATE_KEY,
    /**
     * Another request changed the number counter of a project between the statement that takes an explicit number and the read
     * that follows it: the explicit id is neither accepted nor ahead, and the request is to be sent again. Argument: the project code.
     */
    ID_COUNTER_CHANGED,
    KEY_REPLACEMENT_NOT_CONFIRMED,
    /** A grant for an author that has no live session key: no write of this author could be accepted. Argument: the author. */
    SESSION_KEY_REQUIRED,
    /** A chunk that is out of order, or another chunk of the number that was received. */
    ARTIFACT_CHUNK_CONFLICT,
    /** Another request holds the file lock of the upload; nothing waits and nothing is retried. */
    ARTIFACT_BUSY,
    /** A chunk was appended and not recorded (an unapplied tail), or bytes are missing at the finish; send the missing chunk again. */
    ARTIFACT_INCOMPLETE,
    /** The operation key of the author belongs to an upload with another file name, size or SHA-256. Argument: the key. */
    ARTIFACT_OPERATION_CONFLICT,
    /** A size limit, the budget or the number of open uploads is exceeded. Argument: which one. */
    ARTIFACT_LIMIT,
    /** The received size or the SHA-256 of the received bytes is not the declared one; the bytes are kept. */
    ARTIFACT_HASH_MISMATCH,
    /** The SHA-256 of a chunk is not the one sent with it. */
    CHUNK_HASH_MISMATCH,
    /** The upload expired (it had no chunk for the whole upload-ttl). */
    ARTIFACT_EXPIRED,
    /**
     * The free space of the attachment disk, less the bytes to write, is below {@code disk-margin-bytes}: the owner has to free the
     * disk (not a product limit, see ARTIFACT_LIMIT). Arguments: the bytes to write, the usable bytes, the margin; the same three are the
     * details {@code requestedBytes}, {@code usableBytes} and {@code marginBytes}.
     */
    ARTIFACT_STORAGE_FULL;

    @Override
    public ErrorCategory category() {
        return ErrorCategory.CONFLICT;
    }
}
