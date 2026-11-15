package org.pgledger.migrate1x.wiring;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.domain.error.AdminCode;
import org.pgledger.domain.error.AdminException;
import org.pgledger.domain.error.ConflictCode;
import org.pgledger.domain.error.ConflictException;
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
import org.pgledger.domain.error.ValidationIssue;
import org.pgledger.domain.error.ValidationReason;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.ErrorKey;
import org.pgledger.cli.FailureException;
import org.pgledger.migrate1x.tool.MigrationError;
import org.pgledger.migrate1x.wiring.Refusals.Context;

/**
 * CLI-PRN-07 and RET-016 in the tool: the one translation of a refusal of the ledger into the refusal of the tool, for every kind of
 * {@code LedgerException} in each context of the commands that translate. What the owner needs to continue is in the arguments of
 * the refusal of the tool; the arguments of the ledger are read by name, and the cause is kept.
 */
class RefusalsTest {

    private static FailureException translate(LedgerException refused, Context context) {
        FailureException failure = Refusals.translate(refused, context);
        assertThat(failure.getCause()).as("the refusal of the ledger is the cause").isSameAs(refused);
        return failure;
    }

    private static void assertRefusedAs(LedgerException refused, Context context, ErrorKey code, String... arguments) {
        FailureException failure = translate(refused, context);

        assertThat(failure.code()).as("%s in %s", refused.code(), context).isEqualTo(code);
        assertThat(failure.arguments()).as("%s in %s", refused.code(), context).containsExactly(arguments);
    }

    @Test
    @DisplayName("CLI-PRN-07 generic: every kind of refusal is LEDGER_REFUSED with its code; a validation failure adds the first path and reason and never a value")
    void generic() {
        assertRefusedAs(new ConflictException(ConflictCode.VERSION_CONFLICT, List.of("defect", "D-1", "1", "2")),
                Context.GENERIC, ErrorCode.LEDGER_REFUSED, "VERSION_CONFLICT");
        assertRefusedAs(new LookupException(LookupCode.NOT_FOUND, List.of("project", "x")), Context.GENERIC,
                ErrorCode.LEDGER_REFUSED, "NOT_FOUND");
        assertRefusedAs(new TrustException(TrustCode.SESSION_NOT_GRANTED, List.of()), Context.GENERIC,
                ErrorCode.LEDGER_REFUSED, "SESSION_NOT_GRANTED");
        assertRefusedAs(new TransitionException(TransitionCode.TRANSITION_INVALID, List.of()), Context.GENERIC,
                ErrorCode.LEDGER_REFUSED, "TRANSITION_INVALID");
        assertRefusedAs(new AdminException(AdminCode.UNAUTHORIZED, List.of()), Context.GENERIC, ErrorCode.LEDGER_REFUSED,
                "UNAUTHORIZED");
        assertRefusedAs(new InfrastructureException(InfrastructureCode.INTERNAL_ERROR, List.of()), Context.GENERIC,
                ErrorCode.LEDGER_REFUSED, "INTERNAL_ERROR");
        assertRefusedAs(ValidationException.invalid("title", ValidationReason.REQUIRED), Context.GENERIC,
                ErrorCode.LEDGER_REFUSED, "VALIDATION_FAILED: title REQUIRED");
        // a validation failure without an issue is only its code
        assertRefusedAs(new ValidationException(ValidationCode.CODE_REQUIRED, List.of(), List.<ValidationIssue>of(), Map.of()),
                Context.GENERIC, ErrorCode.LEDGER_REFUSED, "CODE_REQUIRED");
    }

    @Test
    @DisplayName("CLI-PRN-07 import: a duplicate key is IMPORT_TARGET_NOT_EMPTY naming the tables, a 1.x ledger that is not complete is IMPORT_SOURCE_INCOMPLETE naming the missing steps, data that cannot be imported is IMPORT_DATA_REFUSED with the number of issues, a failed comparison is IMPORT_VERIFY_FAILED with its first table; a NOT_FOUND with one argument and an integrity failure that is no comparison are LEDGER_REFUSED")
    void importOneX() {
        assertRefusedAs(new ConflictException(ConflictCode.DUPLICATE_KEY, List.of("defect", "history")), Context.IMPORT,
                MigrationError.IMPORT_TARGET_NOT_EMPTY, "defect, history");
        assertRefusedAs(new LookupException(LookupCode.NOT_FOUND, List.of("missing", "step 1, step 2")), Context.IMPORT,
                MigrationError.IMPORT_SOURCE_INCOMPLETE, "step 1, step 2");
        assertRefusedAs(new LookupException(LookupCode.NOT_FOUND, List.of("project")), Context.IMPORT,
                ErrorCode.LEDGER_REFUSED, "NOT_FOUND");
        ValidationException two = new ValidationException(ValidationCode.VALIDATION_FAILED, List.of(), List.of(
                new ValidationIssue("a[1].x", ValidationReason.REQUIRED, List.of()),
                new ValidationIssue("a[2].x", ValidationReason.REQUIRED, List.of())), Map.of());
        assertRefusedAs(two, Context.IMPORT, MigrationError.IMPORT_DATA_REFUSED, "2");
        assertRefusedAs(new InfrastructureException(InfrastructureCode.INTEGRITY_VIOLATION,
                List.of("defect", "3", "2", "aaaa", "bbbb", "task", "1", "1", "cccc", "dddd")), Context.IMPORT,
                MigrationError.IMPORT_VERIFY_FAILED, "defect", "3", "2", "aaaa", "bbbb");
        assertRefusedAs(new InfrastructureException(InfrastructureCode.INTEGRITY_VIOLATION, List.of("history")), Context.IMPORT,
                ErrorCode.LEDGER_REFUSED, "INTEGRITY_VIOLATION");
        assertRefusedAs(new InfrastructureException(InfrastructureCode.INTERNAL_ERROR, List.of()), Context.IMPORT,
                ErrorCode.LEDGER_REFUSED, "INTERNAL_ERROR");
        assertRefusedAs(new TrustException(TrustCode.AUTHOR_ID_FORMAT, List.of("x")), Context.IMPORT,
                ErrorCode.LEDGER_REFUSED, "AUTHOR_ID_FORMAT");
    }

    @Test
    @DisplayName("RET-016 retire: the verification that retire-1x runs first is refused with its own words: a failed comparison is RETIRE_VERIFY_FAILED with its first table, an incomplete 1.x ledger is IMPORT_SOURCE_INCOMPLETE, data that cannot be imported is IMPORT_DATA_REFUSED; an integrity failure that is no comparison is LEDGER_REFUSED")
    void retireOneX() {
        assertRefusedAs(new InfrastructureException(InfrastructureCode.INTEGRITY_VIOLATION,
                List.of("defect", "3", "2", "aaaa", "bbbb", "task", "1", "1", "cccc", "dddd")), Context.RETIRE,
                MigrationError.RETIRE_VERIFY_FAILED, "defect", "3", "2", "aaaa", "bbbb");
        assertRefusedAs(new LookupException(LookupCode.NOT_FOUND, List.of("missing", "step 1, step 2")), Context.RETIRE,
                MigrationError.IMPORT_SOURCE_INCOMPLETE, "step 1, step 2");
        ValidationException one = new ValidationException(ValidationCode.VALIDATION_FAILED, List.of(), List.of(
                new ValidationIssue("a[1].x", ValidationReason.REQUIRED, List.of())), Map.of());
        assertRefusedAs(one, Context.RETIRE, MigrationError.IMPORT_DATA_REFUSED, "1");
        assertRefusedAs(new InfrastructureException(InfrastructureCode.INTEGRITY_VIOLATION, List.of("history")), Context.RETIRE,
                ErrorCode.LEDGER_REFUSED, "INTEGRITY_VIOLATION");
    }

    @Test
    @DisplayName("CLI-PRN-07 the groups of a failed comparison are read by name: five arguments per table, and none when there are fewer than five")
    void tableMismatches() {
        List<Refusals.TableMismatch> groups = Refusals.TableMismatch
                .of(List.of("defect", "3", "2", "aaaa", "bbbb", "task", "1", "1", "cccc", "dddd", "stray"));

        assertThat(groups).extracting(Refusals.TableMismatch::table).containsExactly("defect", "task");
        assertThat(groups.getFirst().texts()).containsExactly("defect", "3", "2", "aaaa", "bbbb");
        assertThat(groups.getFirst().sourceRows()).isEqualTo("3");
        assertThat(groups.getFirst().targetDigest()).isEqualTo("bbbb");
        assertThat(Refusals.TableMismatch.of(List.of("a", "b"))).isEmpty();
    }
}
