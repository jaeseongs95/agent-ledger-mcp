package org.pgledger.domain.error;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Whether the same request sent later can succeed, for the error codes of the upload tools (common design 3.2, edition 8). The product
 * never retries by itself; this only tells the caller. It is {@code true} for {@code ARTIFACT_BUSY} (the append that was in progress has
 * ended), {@code ARTIFACT_INCOMPLETE} (the missing chunk is sent, then the finish), and for {@code ARTIFACT_LIMIT} when the limit that
 * is named in the detail {@code limit} is {@code staging-budget} or {@code open-uploads} (an upload that ends or the reaper frees it).
 * It is {@code false} for {@code ARTIFACT_LIMIT} of {@code file-size} and {@code attachment-budget} (the owner has to tidy the
 * attachments), {@code ARTIFACT_STORAGE_FULL} (the owner has to free the disk), and for the expired, not found, hash, conflict,
 * corrupted and purpose errors. A code that is no upload code has no answer.
 */
public final class ArtifactRetry {

    /** The detail that carries the answer: one value, {@code "true"} or {@code "false"}. */
    public static final String KEY = "retryable";
    /** The detail of ARTIFACT_LIMIT that names the limit: file-size, attachment-budget, staging-budget or open-uploads. */
    public static final String LIMIT_KEY = "limit";

    private static final Set<ErrorCode> RETRYABLE = Set.of(ConflictCode.ARTIFACT_BUSY, ConflictCode.ARTIFACT_INCOMPLETE);
    private static final Set<ErrorCode> NOT_RETRYABLE = Set.of(ConflictCode.ARTIFACT_STORAGE_FULL,
            ConflictCode.ARTIFACT_EXPIRED, ConflictCode.ARTIFACT_HASH_MISMATCH, ConflictCode.CHUNK_HASH_MISMATCH,
            ConflictCode.ARTIFACT_CHUNK_CONFLICT, ConflictCode.ARTIFACT_OPERATION_CONFLICT, LookupCode.ARTIFACT_NOT_FOUND,
            InfrastructureCode.ARTIFACT_CORRUPTED, ValidationCode.ARTIFACT_UNSUPPORTED_PURPOSE);
    private static final Set<String> RETRYABLE_LIMITS = Set.of("staging-budget", "open-uploads");

    private ArtifactRetry() {
    }

    /** The answer for the code, or empty when the code is no code of the upload tools. */
    public static Optional<Boolean> retryable(ErrorCode code, Map<String, List<String>> details) {
        if (code == ConflictCode.ARTIFACT_LIMIT) {
            List<String> limit = details.getOrDefault(LIMIT_KEY, List.of());
            return Optional.of(!limit.isEmpty() && RETRYABLE_LIMITS.contains(limit.getFirst()));
        }
        if (RETRYABLE.contains(code)) {
            return Optional.of(Boolean.TRUE);
        }
        if (NOT_RETRYABLE.contains(code)) {
            return Optional.of(Boolean.FALSE);
        }
        return Optional.empty();
    }
}
