// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.cli.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.agentledger.cli.ErrorCode;
import org.agentledger.cli.FailureException;
import org.agentledger.cli.database.LedgerRefusals.Context;
import org.agentledger.domain.error.AdminCode;
import org.agentledger.domain.error.AdminException;
import org.agentledger.domain.error.ConflictCode;
import org.agentledger.domain.error.ConflictException;
import org.agentledger.domain.error.InfrastructureCode;
import org.agentledger.domain.error.InfrastructureException;
import org.agentledger.domain.error.LedgerException;
import org.agentledger.domain.error.LookupCode;
import org.agentledger.domain.error.LookupException;
import org.agentledger.domain.error.TransitionCode;
import org.agentledger.domain.error.TransitionException;
import org.agentledger.domain.error.TrustCode;
import org.agentledger.domain.error.TrustException;
import org.agentledger.domain.error.ValidationCode;
import org.agentledger.domain.error.ValidationException;
import org.agentledger.domain.error.ValidationIssue;
import org.agentledger.domain.error.ValidationReason;

/**
 * CLI-PRN-07: the one translation of a refusal of the ledger into the refusal of the CLI, for every kind of
 * {@code LedgerException}.
 */
class LedgerRefusalsTest {

    private static final LedgerRefusals REFUSALS = new LedgerRefusals();

    private static FailureException translate(LedgerException refused, Context context) {
        FailureException failure = REFUSALS.translate(refused, context);
        assertThat(failure.getCause()).as("the refusal of the ledger is the cause").isSameAs(refused);
        return failure;
    }

    private static void assertRefusedAs(LedgerException refused, Context context, ErrorCode code, String... arguments) {
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
        assertRefusedAs(new ValidationException(ValidationCode.CODE_REQUIRED, List.of(), List.<ValidationIssue>of(), Map.of()),
                Context.GENERIC, ErrorCode.LEDGER_REFUSED, "CODE_REQUIRED");
    }

    @Test
    @DisplayName("CLI-PRN-07 an infrastructure failure (an integrity violation included) is LEDGER_REFUSED with its code in every context")
    void infrastructure() {
        for (Context context : Context.values()) {
            assertRefusedAs(new InfrastructureException(InfrastructureCode.INTEGRITY_VIOLATION, List.of("receipt")), context,
                    ErrorCode.LEDGER_REFUSED, "INTEGRITY_VIOLATION");
        }
    }

    @Test
    @DisplayName("VRF-051 & R6-F2: every conflict code is named by the translation; ID_COUNTER_CHANGED and CONCURRENT_MODIFICATION are LEDGER_REFUSED with their code")
    void everyConflictCodeIsTranslated() {
        for (Context context : Context.values()) {
            assertRefusedAs(new ConflictException(ConflictCode.ID_COUNTER_CHANGED, List.of("E2E")), context,
                    ErrorCode.LEDGER_REFUSED, "ID_COUNTER_CHANGED");
            assertRefusedAs(new ConflictException(ConflictCode.CONCURRENT_MODIFICATION, List.of("spec", "SP-1")),
                    context, ErrorCode.LEDGER_REFUSED, "CONCURRENT_MODIFICATION");
            for (ConflictCode code : ConflictCode.values()) {
                assertRefusedAs(new ConflictException(code, List.of("a", "b", "c", "d")), context,
                        ErrorCode.LEDGER_REFUSED, code.token());
            }
        }
    }
}
