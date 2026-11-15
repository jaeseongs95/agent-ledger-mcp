package org.pgledger.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.domain.error.ArtifactRetry;
import org.pgledger.domain.error.ConflictCode;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.LookupCode;
import org.pgledger.domain.error.TrustCode;
import org.pgledger.domain.error.ValidationCode;

/** ATA-040 (common design 3.2, edition 8): whether the same request sent later can succeed, by the code and, for ARTIFACT_LIMIT, by the limit. */
class ArtifactRetryTest {

    private static Map<String, List<String>> limit(String kind) {
        return Map.of(ArtifactRetry.LIMIT_KEY, List.of(kind));
    }

    @Test
    @DisplayName("ATA-040: the busy append and the incomplete upload are retryable")
    void busyAndIncompleteAreRetryable() {
        assertThat(ArtifactRetry.retryable(ConflictCode.ARTIFACT_BUSY, Map.of())).contains(true);
        assertThat(ArtifactRetry.retryable(ConflictCode.ARTIFACT_INCOMPLETE, Map.of())).contains(true);
    }

    @Test
    @DisplayName("ATA-040: ARTIFACT_LIMIT is retryable for the staging budget and the open uploads, not for the file size and the attachment budget, and not when no limit is named")
    void theLimitDecidesByItsKind() {
        assertThat(ArtifactRetry.retryable(ConflictCode.ARTIFACT_LIMIT, limit("staging-budget"))).contains(true);
        assertThat(ArtifactRetry.retryable(ConflictCode.ARTIFACT_LIMIT, limit("open-uploads"))).contains(true);
        assertThat(ArtifactRetry.retryable(ConflictCode.ARTIFACT_LIMIT, limit("file-size"))).contains(false);
        assertThat(ArtifactRetry.retryable(ConflictCode.ARTIFACT_LIMIT, limit("attachment-budget"))).contains(false);
        assertThat(ArtifactRetry.retryable(ConflictCode.ARTIFACT_LIMIT, Map.of())).contains(false);
    }

    @Test
    @DisplayName("ATA-040: the full disk, the expired, hash, conflict, not found, corrupted and purpose errors are not retryable")
    void theOtherUploadCodesAreNotRetryable() {
        assertThat(List.of(ConflictCode.ARTIFACT_STORAGE_FULL, ConflictCode.ARTIFACT_EXPIRED, ConflictCode.ARTIFACT_HASH_MISMATCH,
                ConflictCode.CHUNK_HASH_MISMATCH, ConflictCode.ARTIFACT_CHUNK_CONFLICT, ConflictCode.ARTIFACT_OPERATION_CONFLICT))
                .allSatisfy(code -> assertThat(ArtifactRetry.retryable(code, Map.of())).contains(false));
        assertThat(ArtifactRetry.retryable(LookupCode.ARTIFACT_NOT_FOUND, Map.of())).contains(false);
        assertThat(ArtifactRetry.retryable(InfrastructureCode.ARTIFACT_CORRUPTED, Map.of())).contains(false);
        assertThat(ArtifactRetry.retryable(ValidationCode.ARTIFACT_UNSUPPORTED_PURPOSE, Map.of())).contains(false);
    }

    @Test
    @DisplayName("ATA-041: the storage codes are those of design 3.2 (IO_ERROR, UNSAFE, KEY_MISSING); the settings failure of the key directory is no code of a tool")
    void theStorageCodesAreThoseOfTheCommonTable() {
        List<String> infrastructure = java.util.Arrays.stream(InfrastructureCode.values()).map(Enum::name).toList();

        assertThat(infrastructure).contains("ARTIFACT_STORAGE_IO_ERROR", "ARTIFACT_STORAGE_UNSAFE", "ARTIFACT_KEY_MISSING",
                "ARTIFACT_CORRUPTED").doesNotContain("ARTIFACT_STORAGE_FAILED", "ARTIFACT_KEY_DIRECTORY_INSIDE_STORAGE");
    }

    @Test
    @DisplayName("ATA-040: a code of no upload tool has no answer")
    void aCodeOfNoUploadToolHasNoAnswer() {
        assertThat(ArtifactRetry.retryable(TrustCode.HOST_ATTESTATION_REQUIRED, Map.of())).isEmpty();
        assertThat(ArtifactRetry.retryable(ConflictCode.VERSION_CONFLICT, Map.of())).isEmpty();
    }
}
