// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.domain.error.ConflictCode;
import org.pgledger.domain.error.ConflictException;
import org.pgledger.domain.error.ErrorCode;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.LedgerException;
import org.pgledger.domain.error.LookupCode;
import org.pgledger.domain.error.ValidationCode;
import org.pgledger.mcp.error.ToolError;
import org.pgledger.mcp.error.ToolErrorMapper;
import org.pgledger.mcp.support.Failures;
import org.springframework.context.support.MessageSourceAccessor;
import org.springframework.context.support.ResourceBundleMessageSource;

/**
 * Section 1.5, the error-token registry: for every constant of the seven code enums the failure of its category
 * becomes a tool error whose token is the constant, with an English message from the bundles. The mapper is the real
 * one on the real message bundles (nothing is stubbed).
 */
class ToolErrorMapperTest {

    private static ToolErrorMapper mapper() {
        ResourceBundleMessageSource bundles = new ResourceBundleMessageSource();
        bundles.setBasenames("messages/ledger-errors", "messages/ledger-reasons", "messages/mcp");
        bundles.setFallbackToSystemLocale(false);
        return new ToolErrorMapper(new MessageSourceAccessor(bundles, Locale.ENGLISH));
    }

    static Stream<ErrorCode> codes() {
        return Failures.allCodes();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("codes")
    @DisplayName("1.5: every error code is a tool error with the token of the code and an English message from the "
            + "bundle")
    void everyCodeIsAToolError(ErrorCode code) {
        ToolError error = mapper().map(Failures.of(code));

        assertThat(error.code()).isEqualTo(code.name());
        assertThat(error.message()).isNotBlank().isNotEqualTo(code.token()).doesNotContain("{").doesNotContain("}");
        assertThat(error.message()).doesNotContain("Exception").doesNotContain("org.pgledger");
    }

    static Stream<ErrorCode> validationCodes() {
        return Failures.allCodes().filter(ValidationCode.class::isInstance);
    }

    static Stream<ErrorCode> otherCodes() {
        return Failures.allCodes().filter(code -> !(code instanceof ValidationCode));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("validationCodes")
    @DisplayName("1.5: a failure of a validation code lists its issues with path and reason")
    void validationListsItsIssues(ErrorCode code) {
        ToolError error = mapper().map(Failures.of(code));

        assertThat(error.issues()).containsExactly(new ToolError.Issue("some.path", "is required", List.of()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("otherCodes")
    @DisplayName("1.5: a failure of any other code lists no issues")
    void otherFailuresListNoIssues(ErrorCode code) {
        assertThat(mapper().map(Failures.of(code)).issues()).isEmpty();
    }

    /** ATA-040 (design 3.2, edition 8): the tool error of an upload code carries details.retryable, by code and by the kind of limit. */
    @ParameterizedTest(name = "{0} {1} -> {2}")
    @CsvSource(value = {"ARTIFACT_BUSY,-,true", "ARTIFACT_INCOMPLETE,-,false", "ARTIFACT_LIMIT,staging-budget,true",
            "ARTIFACT_LIMIT,open-uploads,true", "ARTIFACT_LIMIT,file-size,false", "ARTIFACT_LIMIT,attachment-budget,false",
            "ARTIFACT_STORAGE_FULL,-,false", "ARTIFACT_EXPIRED,-,false", "ARTIFACT_HASH_MISMATCH,-,false",
            "CHUNK_HASH_MISMATCH,-,false", "ARTIFACT_CHUNK_CONFLICT,-,false", "ARTIFACT_OPERATION_CONFLICT,-,false"})
    @DisplayName("ATA-040: the conflict codes of the upload tools say whether the same request sent later can succeed")
    void ata040_retryableOfTheConflictCodes(String token, String limit, String retryable) {
        ConflictException failure = new ConflictException(ConflictCode.valueOf(token), Failures.ARGS);
        LedgerException withLimit = "-".equals(limit) ? failure
                : failure.withDetails(Map.of("limit", List.of(limit)));

        ToolError error = mapper().map(withLimit);

        assertThat(error.details().get("retryable")).containsExactly(retryable);
        if (!"-".equals(limit)) {
            assertThat(error.details().get("limit")).containsExactly(limit);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("otherUploadCodes")
    @DisplayName("ATA-040: not found, corrupted, the storage and key failures and the unsupported purpose are not retryable")
    void ata040_retryableOfTheOtherCodes(ErrorCode code) {
        ToolError error = mapper().map(Failures.of(code));

        assertThat(error.details().get("retryable")).containsExactly("false");
    }

    static Stream<ErrorCode> otherUploadCodes() {
        return Stream.of(LookupCode.ARTIFACT_NOT_FOUND, InfrastructureCode.ARTIFACT_CORRUPTED,
                InfrastructureCode.ARTIFACT_STORAGE_IO_ERROR, InfrastructureCode.ARTIFACT_STORAGE_UNSAFE,
                InfrastructureCode.ARTIFACT_KEY_MISSING, ValidationCode.ARTIFACT_UNSUPPORTED_PURPOSE);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("codes")
    @DisplayName("ATA-040: every code of the upload tools carries a retryable detail (common design 3.2, edition 9 supplement)")
    void ata040_everyUploadCodeCarriesRetryable(ErrorCode code) {
        if (code.name().startsWith("ARTIFACT_") || "CHUNK_HASH_MISMATCH".equals(code.name())) {
            assertThat(mapper().map(Failures.of(code)).details().get("retryable")).hasSize(1);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("codes")
    @DisplayName("ATA-040: no other code of the ledger has a retryable detail")
    void ata040_noRetryableOnTheOtherCodes(ErrorCode code) {
        if (!code.name().startsWith("ARTIFACT_") && !"CHUNK_HASH_MISMATCH".equals(code.name())) {
            assertThat(mapper().map(Failures.of(code)).details()).doesNotContainKey("retryable");
        }
    }
}
