package org.pgledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.domain.error.AdminCode;
import org.pgledger.domain.error.AdminException;
import org.pgledger.domain.error.ConflictCode;
import org.pgledger.domain.error.ConflictException;
import org.pgledger.domain.error.ErrorCategory;
import org.pgledger.domain.error.ErrorCode;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;
import org.pgledger.domain.error.LedgerException;
import org.pgledger.domain.error.LookupCode;
import org.pgledger.domain.error.LookupException;
import org.pgledger.domain.error.TransitionCode;
import org.pgledger.domain.error.TransitionException;
import org.pgledger.domain.error.TrustCode;
import org.pgledger.domain.error.TrustException;
import org.pgledger.domain.error.ValidationCode;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationReason;
import org.pgledger.domain.model.BatchOpKind;
import org.pgledger.domain.value.Tokens;

/** DOM-025 .. DOM-027: batch operations, the exception hierarchy and the message bundles. */
class BatchAndErrorsTest {

    @Test
    void dom025_batchOperationKindsCarryTheOldKeySets() {
        assertThat(Tokens.allowed(BatchOpKind.values())).containsExactly("save", "transition", "record_fix",
                "record_case", "link", "correct");
        assertThat(BatchOpKind.SAVE.requiredKeys()).containsExactlyInAnyOrder("entity", "fields", "reason");
        assertThat(BatchOpKind.SAVE.allowedKeys()).containsExactlyInAnyOrder("entity", "fields", "reason", "id",
                "expected_version", "op");
        // OPK-005: an operation of a batch carries no key of its own; its key is derived from the batch key
        for (BatchOpKind kind : BatchOpKind.values()) {
            assertThat(kind.allowedKeys()).doesNotContain("request_id", "operationKey", "operation_key");
        }
        assertThat(BatchOpKind.TRANSITION.requiredKeys()).containsExactlyInAnyOrder("entity", "id",
                "expected_version", "to", "reason");
        assertThat(BatchOpKind.RECORD_FIX.allowedKeys()).contains("branch", "ci_ref", "ci_conclusion",
                "ci_completed_at", "mark_source_fixed");
        assertThat(BatchOpKind.RECORD_CASE.requiredKeys()).containsExactlyInAnyOrder("defect_id", "observation",
                "reason");
        assertThat(BatchOpKind.LINK.allowedKeys()).contains("remove");
        assertThat(BatchOpKind.CORRECT.allowedKeys()).containsExactlyInAnyOrder("history_id", "reason", "op",
                "attachment_id");
        // ATA-012: the four operations that write a history row of their own entity take an optional attachment_id
        assertThat(List.of(BatchOpKind.TRANSITION, BatchOpKind.RECORD_FIX, BatchOpKind.RECORD_CASE,
                BatchOpKind.CORRECT)).allSatisfy(kind -> {
                    assertThat(kind.allowedKeys()).contains("attachment_id");
                    assertThat(kind.requiredKeys()).doesNotContain("attachment_id");
                });
        assertThat(BatchOpKind.SAVE.allowedKeys()).doesNotContain("attachment_id");
        assertThat(BatchOpKind.LINK.allowedKeys()).doesNotContain("attachment_id");
        // review is not a batch operation; nothing and null are not either
        Expect.invalid(() -> Tokens.parse("op", "review", BatchOpKind.values()));
        Expect.invalid(() -> Tokens.parse("op", null, BatchOpKind.values()));
    }

    @Test
    void dom025_aBatchHoldsAtMostTwoHundredOperations() {
        assertThat(BatchOpKind.MAX_OPS_PER_BATCH).isEqualTo(200);
    }

    static List<ErrorCode> allCodes() {
        List<ErrorCode> all = new ArrayList<>();
        for (Class<? extends ErrorCode> type : List.of(TrustCode.class, ValidationCode.class, TransitionCode.class,
                ConflictCode.class, LookupCode.class, AdminCode.class, InfrastructureCode.class)) {
            all.addAll(List.of(type.getEnumConstants()));
        }
        return all;
    }

    /** Compile-time exhaustive: adding a subtype of LedgerException without a case here does not compile. */
    private static ErrorCategory categoryOf(LedgerException e) {
        return switch (e) {
            case TrustException t -> ErrorCategory.TRUST;
            case ValidationException v -> ErrorCategory.VALIDATION;
            case TransitionException t -> ErrorCategory.TRANSITION;
            case ConflictException c -> ErrorCategory.CONFLICT;
            case LookupException l -> ErrorCategory.LOOKUP;
            case AdminException a -> ErrorCategory.ADMIN;
            case InfrastructureException i -> ErrorCategory.INFRASTRUCTURE;
        };
    }

    private static LedgerException exceptionFor(ErrorCode code) {
        return switch (code) {
            case TrustCode c -> new TrustException(c, List.of());
            case ValidationCode c -> new ValidationException(c, List.of(), List.of(), java.util.Map.of());
            case TransitionCode c -> new TransitionException(c, List.of());
            case ConflictCode c -> new ConflictException(c, List.of());
            case LookupCode c -> new LookupException(c, List.of());
            case AdminCode c -> new AdminException(c, List.of());
            case InfrastructureCode c -> new InfrastructureException(c, List.of());
        };
    }

    @Test
    void dom026_everyCodeMapsToOneExceptionSubtypeOfItsCategory() {
        List<ErrorCode> codes = allCodes();
        // 62, the 8 passkey codes (PSK-DOM-01) and the 15 codes of the attachments (ATA-001 ..; common table 3.2):
        // ARTIFACT_UNSUPPORTED_PURPOSE (validation), ARTIFACT_NOT_FOUND (lookup), nine conflicts (with ARTIFACT_STORAGE_FULL),
        // four infrastructure codes, the code of the long text type of the schema files (RUN-003) and the code of the encoding of the database (RUN-017), and no
        // code for an interrupted wait for another runner any more (the database waits, RUN-012)
        assertThat(codes).hasSize(86);
        assertThat(codes.stream().map(ErrorCode::token)).doesNotHaveDuplicates();
        assertThat(codes.stream().map(ErrorCode::token)).contains("AUTHOR_ID_FORMAT", "SESSION_NOT_GRANTED",
                "HOST_ATTESTATION_REQUIRED", "VERSION_CONFLICT", "SAME_SESSION", "MERGED_DEFECT", "CODE_REQUIRED",
                "CODE_INVALID", "ATTESTATION_UNSIGNED", "ATTESTATION_MALFORMED",
                "ATTESTATION_VERSION_UNSUPPORTED", "ATTESTATION_SIGNATURE_INVALID", "ATTESTATION_BINDING_MISMATCH",
                "ATTESTATION_TIME_WINDOW", "KEY_REPLACEMENT_NOT_CONFIRMED", "SESSION_KEY_REQUIRED", "ARTIFACT_UNSUPPORTED_PURPOSE", "ARTIFACT_CHUNK_CONFLICT",
                "ARTIFACT_BUSY", "ARTIFACT_INCOMPLETE", "ARTIFACT_OPERATION_CONFLICT", "ARTIFACT_LIMIT",
                "ARTIFACT_HASH_MISMATCH", "CHUNK_HASH_MISMATCH", "ARTIFACT_EXPIRED", "ARTIFACT_STORAGE_FULL", "ARTIFACT_NOT_FOUND",
                "ARTIFACT_CORRUPTED", "ARTIFACT_STORAGE_UNSAFE", "ARTIFACT_KEY_MISSING", "ARTIFACT_STORAGE_IO_ERROR");
        for (ErrorCode code : codes) {
            LedgerException e = exceptionFor(code);
            assertThat(e.code()).isEqualTo(code);
            assertThat(categoryOf(e)).as(code.token()).isEqualTo(code.category());
            assertThat(e.category()).isEqualTo(code.category());
        }
        assertThat(ErrorCode.class.getPermittedSubclasses()).hasSize(7);
        assertThat(LedgerException.class.getPermittedSubclasses()).hasSize(7);
    }

    @Test
    void dom027_bundlesHaveTheSameKeysAndCoverEveryCode() {
        ResourceBundle en = ResourceBundle.getBundle("messages/ledger-errors", Locale.ROOT);
        ResourceBundle ko = ResourceBundle.getBundle("messages/ledger-errors", Locale.of("ko"));
        assertThat(Set.copyOf(en.keySet())).isEqualTo(Set.copyOf(ko.keySet()));
        Set<String> expected = new HashSet<>();
        for (ErrorCode code : allCodes()) {
            expected.add(code.messageKey());
            assertThat(en.getString(code.messageKey())).as(code.messageKey()).isNotBlank();
            assertThat(ko.getString(code.messageKey())).as(code.messageKey()).isNotBlank();
            // the patterns must be valid MessageFormat patterns
            new MessageFormat(en.getString(code.messageKey())).format(new Object[] {"a", "b", "c", "d"});
            new MessageFormat(ko.getString(code.messageKey())).format(new Object[] {"a", "b", "c", "d"});
        }
        assertThat(Set.copyOf(en.keySet())).as("no unused key").isEqualTo(expected);
        assertThat(ko.getString("error.SESSION_NOT_GRANTED")).contains("부여");
    }

    @Test
    void dom027_reasonBundlesHaveAPatternForEveryValidationReason() {
        ResourceBundle en = ResourceBundle.getBundle("messages/ledger-reasons", Locale.ROOT);
        ResourceBundle ko = ResourceBundle.getBundle("messages/ledger-reasons", Locale.of("ko"));
        assertThat(Set.copyOf(en.keySet())).isEqualTo(Set.copyOf(ko.keySet()));
        Set<String> expected = new HashSet<>();
        for (ValidationReason reason : ValidationReason.values()) {
            assertThat(reason.messageKey()).isEqualTo("reason." + reason.name());
            expected.add(reason.messageKey());
            assertThat(en.getString(reason.messageKey())).as(reason.messageKey()).isNotBlank();
            assertThat(ko.getString(reason.messageKey())).as(reason.messageKey()).isNotBlank();
            // the patterns parse, and both languages use the same number of arguments
            MessageFormat english = new MessageFormat(en.getString(reason.messageKey()));
            MessageFormat korean = new MessageFormat(ko.getString(reason.messageKey()));
            assertThat(korean.getFormatsByArgumentIndex()).as(reason.messageKey()).hasSameSizeAs(
                    english.getFormatsByArgumentIndex());
            assertThat(english.format(new Object[] {"a", "b", "c"})).as(reason.messageKey()).doesNotContain("{");
        }
        assertThat(Set.copyOf(en.keySet())).as("no unused key").isEqualTo(expected);
        assertThat(new MessageFormat(en.getString("reason.MUST_BE_AT_MOST_CHARS")).format(new Object[] {"200"}))
                .isEqualTo("must be at most 200 characters");
        assertThat(new MessageFormat(ko.getString("reason.REQUIRED")).format(new Object[0])).contains("필수");
    }

    @Test
    void dom027_aJakartaTemplateNamesOneReason() {
        for (ValidationReason reason : ValidationReason.values()) {
            assertThat(ValidationReason.ofTemplate("{" + reason.messageKey() + "}")).isSameAs(reason);
        }
        assertThatThrownBy(() -> ValidationReason.ofTemplate("{jakarta.validation.constraints.NotNull.message}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ValidationReason.ofTemplate("is required"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void dom027_aValidationFailureCarriesThePathAndTheReasonSeparately() {
        ValidationException e = ValidationException.invalid("ops", ValidationReason.MUST_HOLD_ITEMS, "1", "100");
        assertThat(e.code()).isEqualTo(ValidationCode.VALIDATION_FAILED);
        assertThat(e.args()).containsExactly("ops");
        assertThat(e.issues()).hasSize(1);
        assertThat(e.issues().get(0).reason()).isEqualTo(ValidationReason.MUST_HOLD_ITEMS);
        assertThat(e.issues().get(0).reasonArgs()).containsExactly("1", "100");
        assertThat(e.issues().get(0).allowed()).isEmpty();
        ValidationException closed = ValidationException.invalid("kind", ValidationReason.UNKNOWN_VALUE,
                List.of("a", "b"), "x");
        assertThat(closed.issues().get(0).allowed()).containsExactly("a", "b");
        assertThat(closed.issues().get(0).reasonArgs()).containsExactly("x");
    }

    @ParameterizedTest
    @MethodSource("allCodes")
    void dom026_withDetailsKeepsTypeCodeAndArguments(ErrorCode code) {
        LedgerException original = exceptionFor(code);
        LedgerException wrapped = original.withDetails(java.util.Map.of("op_index", List.of("2")));
        assertThat(wrapped).isNotSameAs(original).hasSameClassAs(original);
        assertThat(wrapped.code()).isEqualTo(code);
        assertThat(wrapped.args()).isEqualTo(original.args());
        assertThat(wrapped.details()).containsEntry("op_index", List.of("2"));
        assertThat(original.details()).doesNotContainKey("op_index");
    }

    @Test
    void dom026_withDetailsKeepsTheCauseOfEverySubtype() {
        Throwable cause = new IllegalStateException("the failure behind");
        java.util.Map<String, List<String>> extra = java.util.Map.of("op_index", List.of("1"));
        List<LedgerException> withCause = List.of(
                new TrustException(TrustCode.ATTESTATION_MALFORMED, List.of(), java.util.Map.of(), cause),
                new ValidationException(ValidationCode.VALIDATION_FAILED, List.of("p"), List.of(),
                        java.util.Map.of(), cause),
                new TransitionException(TransitionCode.TRANSITION_INVALID, List.of("a", "b"), cause),
                new ConflictException(ConflictCode.DUPLICATE_KEY, List.of("x"), cause),
                new InfrastructureException(InfrastructureCode.INTERNAL_ERROR, List.of(), cause));
        for (LedgerException original : withCause) {
            assertThat(original.getCause()).as(original.getClass().getSimpleName()).isSameAs(cause);
            LedgerException wrapped = original.withDetails(extra);
            assertThat(wrapped.getCause()).as(original.getClass().getSimpleName()).isSameAs(cause);
            assertThat(wrapped.withDetails(java.util.Map.of("op", List.of("save"))).getCause()).isSameAs(cause);
        }
        // the admin and lookup failures are made without a cause, and stay without one
        for (LedgerException original : List.of(new AdminException(AdminCode.UNAUTHORIZED, List.of()),
                new LookupException(LookupCode.NOT_FOUND, List.of("a", "b")))) {
            assertThat(original.withDetails(extra).getCause()).isNull();
        }
    }

    @Test
    void dom026_theSchemaFailuresAreInfrastructureCodesWithAMessageEach() {
        List<String> schema = List.of("SCHEMA_FILE_NAME_INVALID", "SCHEMA_VERSION_DUPLICATE", "SCHEMA_VERSION_GAP",
                "SCHEMA_FILES_UNREADABLE", "SCHEMA_DATABASE_UNREACHABLE", "SCHEMA_HISTORY_MISSING",
                "SCHEMA_HISTORY_NOT_CREATED", "SCHEMA_APPLIED_FILE_MISSING", "SCHEMA_DIGEST_MISMATCH",
                "SCHEMA_RECORDED_FAILED", "SCHEMA_OUT_OF_ORDER", "SCHEMA_PENDING", "SCHEMA_MIGRATION_FAILED",
                "SCHEMA_LONG_TEXT_TYPE_MISSING", "SCHEMA_ENCODING_NOT_UTF8", "SCHEMA_GRANT_FAILED", "JVM_ZONE_NOT_UTC", "SESSION_ZONE_NOT_UTC",
                "SESSION_CLOCK_UNREADABLE");
        ResourceBundle en = ResourceBundle.getBundle("messages/ledger-errors", Locale.ROOT);
        for (String token : schema) {
            InfrastructureCode code = InfrastructureCode.valueOf(token);
            assertThat(code.category()).isEqualTo(ErrorCategory.INFRASTRUCTURE);
            assertThat(code.token()).isEqualTo(token);
            assertThat(en.getString(code.messageKey())).as(token).isNotBlank();
        }
        // the arguments of the patterns are filled in the order the throwing code gives them
        assertThat(new MessageFormat(en.getString("error.SCHEMA_DIGEST_MISMATCH"))
                .format(new Object[] {"3", "V3__x.sql", "aaa", "bbb"}))
                .contains("V3", "V3__x.sql", "aaa", "bbb");
        assertThat(new MessageFormat(en.getString("error.SCHEMA_VERSION_GAP")).format(new Object[] {"2", "4"}))
                .contains("2", "V4");
    }

    /** SQB-001: the three failures about the database product are INFRASTRUCTURE codes with a message in both bundles. */
    @Test
    void sqb001_theDatabaseProductFailuresAreInfrastructureCodesWithAMessageEach() {
        ResourceBundle en = ResourceBundle.getBundle("messages/ledger-errors", Locale.ROOT);
        ResourceBundle ko = ResourceBundle.getBundle("messages/ledger-errors", Locale.KOREAN);
        for (String token : List.of("DATABASE_PRODUCT_UNREADABLE", "DATABASE_PRODUCT_UNSUPPORTED",
                "DATABASE_PRODUCT_MISMATCH")) {
            InfrastructureCode code = InfrastructureCode.valueOf(token);
            assertThat(code.category()).isEqualTo(ErrorCategory.INFRASTRUCTURE);
            assertThat(code.token()).isEqualTo(token);
            assertThat(en.getString(code.messageKey())).as(token).isNotBlank();
            assertThat(ko.getString(code.messageKey())).as(token).isNotBlank();
        }
        // the arguments are filled in the order the throwing code gives them: configured product, then connected product
        assertThat(new MessageFormat(en.getString("error.DATABASE_PRODUCT_MISMATCH"))
                .format(new Object[] {"MYSQL", "POSTGRESQL"})).contains("MYSQL", "POSTGRESQL");
        assertThat(new MessageFormat(en.getString("error.DATABASE_PRODUCT_UNSUPPORTED"))
                .format(new Object[] {"Derby"})).contains("Derby");
    }
}
