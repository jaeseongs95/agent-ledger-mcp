package org.pgledger.application.write;

import java.util.function.Function;
import org.pgledger.application.gate.GateSession;
import org.pgledger.application.gate.Lookup;
import org.pgledger.application.gate.WriteGate;
import org.pgledger.application.receipt.ReceiptService;
import org.pgledger.domain.model.ReceiptOperation;
import org.pgledger.domain.rule.Channel;
import org.pgledger.domain.value.CanonicalPayload;
import org.pgledger.domain.value.Reason;
import org.pgledger.domain.value.OperationKey;
import org.pgledger.domain.value.Sha256;

/**
 * The fixed shape of every write, in the order of the gate: find out what the request touches, check the actor
 * and the grants (in the transaction of the write), validate the reason, validate and normalise the input, insert
 * the receipt first (a replay returns the stored response here), then apply the business rules and write, and
 * finally store the response in the receipt. Each write is a subclass that fills in the three steps that differ.
 * The class has no transaction of its own: it runs inside the transaction of the service that calls it.
 */
public abstract class WriteOperation<C extends WriteCommand, R extends WriteResult> {

    /** The validated input of a request: what the receipt digest covers, and the work to do once it is the first. */
    public record Prepared<R>(CanonicalPayload.Fields payload, Function<WriteContext, R> execute) {
    }

    private final WriteGate gate;
    private final ReceiptService receipts;
    private final ReceiptOperation operation;
    private final Class<R> resultType;

    protected WriteOperation(WriteGate gate, ReceiptService receipts, ReceiptOperation operation,
            Class<R> resultType) {
        this.gate = gate;
        this.receipts = receipts;
        this.operation = operation;
        this.resultType = resultType;
    }

    /** The projects and scopes the request needs, and when the gate looks them up (see {@link Lookup}). */
    protected abstract Lookup resolve(C command);

    /** Validates and normalises the input; the work is run only when the receipt is new. */
    protected abstract Prepared<R> prepare(C command);

    /**
     * Runs the write for {@code actor} (the raw host attestation, possibly absent) through {@code channel}. Must be
     * called inside a transaction.
     */
    public final R run(String actor, Channel channel, C command) {
        return run(actor, command.operationKey(), channel, command);
    }

    /**
     * As {@link #run(String, Channel, WriteCommand)}, for an operation of a batch: the attestation of the call is bound
     * to {@code boundOperationKey}, the operation key of the whole batch, while the operation has a key of its own
     * (derived from the key of the batch and its position).
     */
    public final R run(String actor, String boundOperationKey, Channel channel, C command) {
        GateSession session = gate.open(actor, boundOperationKey, channel, resolve(command));
        return session.bind(() -> {
            Reason reason = Reason.of(command.reason());
            OperationKey operationKey = OperationKey.parse(command.operationKey());
            Prepared<R> prepared = prepare(command);
            Sha256 digest = CanonicalPayload.digest(operation.name(),
                    prepared.payload().text("reason", reason.text()));
            // a replay returns the stored response; the first call writes and stores its response in the receipt
            return switch (receipts.reserve(operationKey, operation, digest, session.author(), session.channel(),
                    resultType)) {
                case ReceiptService.Reservation.Replayed<R> replayed -> replayed.response();
                case ReceiptService.Reservation.Reserved<R> reserved -> {
                    R result = prepared.execute().apply(new WriteContext(session, reason, operationKey));
                    receipts.finish(operationKey, result);
                    yield result;
                }
            };
        });
    }
}
