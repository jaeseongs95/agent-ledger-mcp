package org.pgledger.migrate1x.service;

import java.util.List;
import java.util.Objects;

/**
 * A refusal of {@link LegacyRetireService} that is not a failure of the ledger's data (those are {@code LedgerException}s of the
 * verification the service runs first): the code is one of {@link RetireRefusal}, the arguments name what is wrong (sessions,
 * objects, a digest) and never a value that anyone typed. The command turns it into its own error with one switch.
 */
public final class RetireRefusedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final RetireRefusal refusal;
    private final transient List<String> arguments;

    public RetireRefusedException(RetireRefusal refusal, List<String> arguments) {
        super(Objects.requireNonNull(refusal, "refusal") + " " + arguments);
        this.refusal = refusal;
        this.arguments = List.copyOf(arguments);
    }

    public RetireRefusal refusal() {
        return refusal;
    }

    /** What the refusal names (see {@link RetireRefusal}). */
    public List<String> arguments() {
        return arguments;
    }
}
