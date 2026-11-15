package org.pgledger.migrate1x.wiring;

import java.util.ArrayList;
import java.util.List;
import org.pgledger.domain.error.AdminException;
import org.pgledger.domain.error.ConflictCode;
import org.pgledger.domain.error.ConflictException;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;
import org.pgledger.domain.error.LedgerException;
import org.pgledger.domain.error.LookupCode;
import org.pgledger.domain.error.LookupException;
import org.pgledger.domain.error.TransitionException;
import org.pgledger.domain.error.TrustException;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationIssue;
import org.pgledger.migrate1x.tool.ErrorCode;
import org.pgledger.migrate1x.tool.FailureException;

/**
 * The one place where a refusal of the ledger becomes the refusal of the tool. The kind of refusal is an exhaustive switch
 * over the sealed {@link LedgerException} (a new kind does not compile until it is dealt with here), and what the owner asked for
 * ({@link Context}) decides which code of the tool it becomes. The arguments of a ledger refusal are positional in the domain;
 * they are read by name here ({@link TableMismatch}) and never echoed except what the owner needs to go on (the tables of a failed
 * comparison). No value the owner typed is part of a message.
 */
public final class Refusals {

    /** What the command was doing when the ledger refused. */
    public enum Context {
        /** Any other work on the ledger: the refusal is LEDGER_REFUSED with its code. */
        GENERIC,
        /** {@code import-1x}. */
        IMPORT,
        /** {@code retire-1x}: the verification it runs first fails like the one of the import, with the words of this command. */
        RETIRE
    }

    /**
     * One table of a failed comparison of the 1.x ledger and the new one: the arguments of INTEGRITY_VIOLATION come in groups of
     * these five.
     */
    public record TableMismatch(String table, String sourceRows, String targetRows, String sourceDigest,
            String targetDigest) {

        private static final int GROUP = 5;

        /** The groups of the arguments, empty when they are not groups of five (another integrity failure). */
        public static List<TableMismatch> of(List<String> arguments) {
            List<TableMismatch> groups = new ArrayList<>();
            if (arguments.size() < GROUP) {
                return groups;
            }
            for (int from = 0; from + GROUP <= arguments.size(); from += GROUP) {
                groups.add(new TableMismatch(arguments.get(from), arguments.get(from + 1), arguments.get(from + 2),
                        arguments.get(from + 3), arguments.get(from + 4)));
            }
            return groups;
        }

        /** The five texts in the order of the message {@code import-1x.mismatch}. */
        public String[] texts() {
            return new String[] {table, sourceRows, targetRows, sourceDigest, targetDigest};
        }
    }

    private Refusals() {
    }

    public static FailureException translate(LedgerException refused, Context context) {
        return switch (refused) {
            case ConflictException conflict -> conflict(conflict, context);
            case LookupException lookup -> lookup(lookup, context);
            case ValidationException invalid -> validation(invalid, context);
            case InfrastructureException infrastructure -> infrastructure(infrastructure, context);
            case TrustException trust -> generic(trust);
            case TransitionException transition -> generic(transition);
            case AdminException admin -> generic(admin);
        };
    }

    private static FailureException conflict(ConflictException refused, Context context) {
        if ((ConflictCode) refused.code() == ConflictCode.DUPLICATE_KEY && context == Context.IMPORT) {
            return new FailureException(ErrorCode.IMPORT_TARGET_NOT_EMPTY, refused, String.join(", ", refused.args()));
        }
        return generic(refused);
    }

    private static FailureException lookup(LookupException refused, Context context) {
        // the arguments of the 1.x source that is not complete: what was looked for, then the missing steps
        if ((LookupCode) refused.code() == LookupCode.NOT_FOUND && context != Context.GENERIC && refused.args().size() > 1) {
            return new FailureException(ErrorCode.IMPORT_SOURCE_INCOMPLETE, refused, refused.args().get(1));
        }
        return generic(refused);
    }

    private static FailureException validation(ValidationException refused, Context context) {
        if (context == Context.GENERIC) {
            return generic(refused);
        }
        return new FailureException(ErrorCode.IMPORT_DATA_REFUSED, refused, String.valueOf(refused.issues().size()));
    }

    /**
     * Of the infrastructure codes only the failed comparison of the import means anything else to the owner: IMPORT_VERIFY_FAILED
     * for the import, RETIRE_VERIFY_FAILED for the verification that {@code retire-1x} runs first.
     */
    private static FailureException infrastructure(InfrastructureException refused, Context context) {
        if ((InfrastructureCode) refused.code() == InfrastructureCode.INTEGRITY_VIOLATION) {
            List<TableMismatch> groups = TableMismatch.of(refused.args());
            if (!groups.isEmpty()) {
                return switch (context) {
                    case IMPORT -> new FailureException(ErrorCode.IMPORT_VERIFY_FAILED, refused,
                            groups.getFirst().texts());
                    case RETIRE -> new FailureException(ErrorCode.RETIRE_VERIFY_FAILED, refused,
                            groups.getFirst().texts());
                    case GENERIC -> generic(refused);
                };
            }
        }
        return generic(refused);
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
