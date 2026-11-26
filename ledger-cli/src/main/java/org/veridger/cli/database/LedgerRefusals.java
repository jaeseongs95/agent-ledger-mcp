// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.cli.database;

import org.veridger.cli.ErrorCode;
import org.veridger.cli.FailureException;
import org.veridger.domain.error.AdminException;
import org.veridger.domain.error.ConflictException;
import org.veridger.domain.error.InfrastructureException;
import org.veridger.domain.error.LedgerException;
import org.veridger.domain.error.LookupException;
import org.veridger.domain.error.TransitionException;
import org.veridger.domain.error.TrustException;
import org.veridger.domain.error.ValidationException;
import org.veridger.domain.error.ValidationIssue;
import org.springframework.stereotype.Component;

/**
 * The one place where a refusal of the ledger becomes the refusal of the CLI. The kind of refusal is an exhaustive switch
 * over the sealed {@link LedgerException} (a new kind does not compile until it is dealt with here). No value the owner
 * typed is part of a message.
 */
@Component
public final class LedgerRefusals {

    /** What the command was doing when the ledger refused. */
    public enum Context {
        /** Any command: the refusal is LEDGER_REFUSED with its code. */
        GENERIC
    }

    public FailureException translate(LedgerException refused, Context context) {
        return switch (refused) {
            case ConflictException conflict -> generic(conflict);
            case LookupException lookup -> generic(lookup);
            case ValidationException invalid -> generic(invalid);
            case TrustException trust -> generic(trust);
            case InfrastructureException infrastructure -> generic(infrastructure);
            case TransitionException transition -> generic(transition);
            case AdminException admin -> generic(admin);
        };
    }

    /** LEDGER_REFUSED with the code and, for a validation failure, the first path and reason (never an input value). */
    private static FailureException generic(LedgerException refused) {
        String detail = refused.code().token();
        if (refused instanceof ValidationException invalid && !invalid.issues().isEmpty()) {
            ValidationIssue first = invalid.issues().getFirst();
            detail = detail + ": " + first.path() + " " + first.reason().name();
        }
        return new FailureException(ErrorCode.LEDGER_REFUSED, refused, detail);
    }
}
