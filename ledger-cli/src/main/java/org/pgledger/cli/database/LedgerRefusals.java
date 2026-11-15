package org.pgledger.cli.database;

import java.util.List;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.FailureException;
import org.pgledger.domain.error.AdminException;
import org.pgledger.domain.error.ConflictCode;
import org.pgledger.domain.error.ConflictException;
import org.pgledger.domain.error.InfrastructureException;
import org.pgledger.domain.error.LedgerException;
import org.pgledger.domain.error.LookupCode;
import org.pgledger.domain.error.LookupException;
import org.pgledger.domain.error.TransitionException;
import org.pgledger.domain.error.TrustCode;
import org.pgledger.domain.error.TrustException;
import org.pgledger.domain.error.ValidationCode;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationIssue;
import org.pgledger.domain.error.ValidationReason;

/**
 * The one place where a refusal of the ledger becomes the refusal of the CLI. The kind of refusal is an exhaustive switch
 * over the sealed {@link LedgerException} (a new kind does not compile until it is dealt with here), the code is a switch
 * over the code enum of that kind, and what the owner asked for ({@link Context}) decides which CLI code it becomes. The
 * arguments of a ledger refusal are positional in the domain; they are read by name here ({@link LiveKey}) and never
 * echoed except what the owner needs to go on (the fingerprint and the registration time of a live key). No value the
 * owner typed is part of a message.
 */
public final class LedgerRefusals {

    /** What the command was doing when the ledger refused. */
    public enum Context {
        /** Any other command: the refusal is LEDGER_REFUSED with its code. */
        GENERIC,
        /** {@code key-register} without {@code --replace}. */
        REGISTER,
        /** {@code key-register --replace=<fingerprint>}: the owner has already given the fingerprint of the live key. */
        REPLACE,
        /** {@code key-revoke}. */
        REVOKE
    }

    /** The live key that a registration would replace: the arguments of KEY_REPLACEMENT_NOT_CONFIRMED. */
    record LiveKey(String author, String fingerprint, String registeredAt) {

        static LiveKey of(List<String> arguments) {
            return new LiveKey(arguments.get(0), arguments.get(1), arguments.get(2));
        }
    }

    private LedgerRefusals() {
    }

    public static FailureException translate(LedgerException refused, Context context) {
        return switch (refused) {
            case ConflictException conflict -> conflict(conflict, context);
            case LookupException lookup -> lookup(lookup, context);
            case ValidationException invalid -> validation(invalid, context);
            case TrustException trust -> trust(trust, context);
            case InfrastructureException infrastructure -> generic(infrastructure);
            case TransitionException transition -> generic(transition);
            case AdminException admin -> generic(admin);
        };
    }

    private static FailureException conflict(ConflictException refused, Context context) {
        return switch ((ConflictCode) refused.code()) {
            case DUPLICATE_KEY -> switch (context) {
                case REGISTER, REPLACE -> new FailureException(ErrorCode.KEY_IN_USE, refused);
                case GENERIC, REVOKE -> generic(refused);
            };
            case KEY_REPLACEMENT_NOT_CONFIRMED -> switch (context) {
                case REGISTER, REPLACE -> {
                    LiveKey live = LiveKey.of(refused.args());
                    yield new FailureException(context == Context.REPLACE ? ErrorCode.REPLACE_FINGERPRINT_STALE
                            : ErrorCode.KEY_REPLACEMENT_NOT_CONFIRMED, refused, live.fingerprint(), live.registeredAt());
                }
                case GENERIC, REVOKE -> generic(refused);
            };
            // the one place of the rule is the service; whatever command asked, the owner is told how to register the key
            case SESSION_KEY_REQUIRED -> new FailureException(ErrorCode.NO_SESSION_KEY, refused,
                    refused.args().getFirst());
            case VERSION_CONFLICT, OPERATION_CONFLICT, ARTIFACT_CHUNK_CONFLICT, ARTIFACT_BUSY, ARTIFACT_INCOMPLETE,
                ARTIFACT_OPERATION_CONFLICT, ARTIFACT_LIMIT, ARTIFACT_HASH_MISMATCH, CHUNK_HASH_MISMATCH,
                ARTIFACT_EXPIRED, ARTIFACT_STORAGE_FULL -> generic(refused);
        };
    }

    private static FailureException lookup(LookupException refused, Context context) {
        return switch ((LookupCode) refused.code()) {
            case NOT_FOUND -> switch (context) {
                case REVOKE -> new FailureException(ErrorCode.NO_LIVE_KEY, refused);
                case GENERIC, REGISTER, REPLACE -> generic(refused);
            };
            case ARTIFACT_NOT_FOUND -> generic(refused);
        };
    }

    private static FailureException validation(ValidationException refused, Context context) {
        return switch (context) {
            case REVOKE -> revokeValidation(refused);
            case REGISTER, REPLACE -> namesTheAuthor(refused)
                    ? new FailureException(ErrorCode.INVALID_AUTHOR, refused) : generic(refused);
            case GENERIC -> generic(refused);
        };
    }

    private static FailureException revokeValidation(ValidationException refused) {
        if ((ValidationCode) refused.code() == ValidationCode.VALIDATION_FAILED && !refused.issues().isEmpty()
                && refused.issues().getFirst().reason() == ValidationReason.MUST_MATCH) {
            // the confirmation does not match: the issue names the fingerprint of the key that is live now
            return new FailureException(ErrorCode.REVOKE_FINGERPRINT_MISMATCH, refused,
                    refused.issues().getFirst().reasonArgs().getFirst());
        }
        return namesTheAuthor(refused) ? new FailureException(ErrorCode.INVALID_AUTHOR, refused) : generic(refused);
    }

    private static boolean namesTheAuthor(ValidationException refused) {
        return (ValidationCode) refused.code() == ValidationCode.VALIDATION_FAILED && refused.args().contains("author");
    }

    private static FailureException trust(TrustException refused, Context context) {
        return switch ((TrustCode) refused.code()) {
            case AUTHOR_ID_FORMAT -> switch (context) {
                case REGISTER, REPLACE, REVOKE -> new FailureException(ErrorCode.INVALID_AUTHOR, refused);
                case GENERIC -> generic(refused);
            };
            case HOST_ATTESTATION_REQUIRED, SESSION_NOT_GRANTED, VERIFY_NEEDS_REVIEWER_LOGIN, ATTESTATION_UNSIGNED,
                    ATTESTATION_MALFORMED, ATTESTATION_SIGNATURE_INVALID,
                    ATTESTATION_BINDING_MISMATCH, ATTESTATION_TIME_WINDOW -> generic(refused);
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
