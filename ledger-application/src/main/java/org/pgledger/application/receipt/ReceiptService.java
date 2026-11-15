package org.pgledger.application.receipt;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import org.pgledger.application.json.LedgerJson;
import org.pgledger.database.repository.ReceiptCompletionRepository;
import org.pgledger.database.repository.ReceiptRepository;
import org.pgledger.domain.error.ConflictCode;
import org.pgledger.domain.error.ConflictException;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;
import org.pgledger.domain.model.Receipt;
import org.pgledger.domain.model.ReceiptOperation;
import org.pgledger.domain.rule.Channel;
import org.pgledger.domain.value.AuthorId;
import org.pgledger.domain.value.OperationKey;
import org.pgledger.domain.value.Sha256;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * Idempotency. It has no transaction of its own: it joins the one of the write (principle 4.4). The receipt of the
 * operation key is read first: a committed receipt of an earlier call answers with its stored response (a replay) or, for
 * another request under the same key, with OPERATION_CONFLICT. Without one the receipt is inserted, in the transaction of the write.
 * A second call at the very same moment meets the unique key of the operation key (the insert waits until the first call
 * commits) and ends with the translated DUPLICATE_KEY; the caller sends the request again and then gets the replay. A
 * failed write rolls its receipt back with everything else, so a retry with a corrected payload is not a conflict.
 */
@Component
public class ReceiptService {

    private final ReceiptRepository receipts;
    private final ReceiptCompletionRepository completion;
    private final LedgerJson json;
    private final Clock clock;

    public ReceiptService(ReceiptRepository receipts, ReceiptCompletionRepository completion, LedgerJson json,
            Clock clock) {
        this.receipts = receipts;
        this.completion = completion;
        this.json = json;
        this.clock = clock;
    }

    /**
     * What {@link #reserve} found: the caller is the first, or the same request was answered before. The two differ in
     * shape (the second carries the stored response), so the answer is a type of two variants and the caller decides
     * with a switch that has to name both.
     */
    public sealed interface Reservation<R> {

        /** The operation key is the caller's: it writes and then {@link ReceiptService#finish finishes} the receipt. */
        record Reserved<R>() implements Reservation<R> {
        }

        /** The same request was answered before: its stored response. */
        record Replayed<R>(R response) implements Reservation<R> {
        }
    }

    /**
     * Reserves the operation key: {@link Reservation.Reserved} when the caller is the first and writes,
     * {@link Reservation.Replayed} with the stored response when the same request was answered before.
     *
     * @throws ConflictException OPERATION_CONFLICT when the key belongs to another operation, payload, author or
     *         channel; DUPLICATE_KEY when another call reserved the key at the very same moment
     */
    public <R> Reservation<R> reserve(OperationKey id, ReceiptOperation operation, Sha256 digest, AuthorId author,
            Channel channel, Class<R> responseType) {
        return receipts.findById(id)
                .<Reservation<R>>map(stored -> new Reservation.Replayed<>(storedResponse(stored, id, operation, digest,
                        author, channel, responseType)))
                .orElseGet(() -> insertReservation(id, operation, digest, author, channel));
    }

    private <R> Reservation<R> insertReservation(OperationKey id, ReceiptOperation operation, Sha256 digest,
            AuthorId author, Channel channel) {
        try {
            receipts.insert(Receipt.reserve(id, operation, digest, author, channel, clock.instant()));
        } catch (DuplicateKeyException duplicate) {
            throw new ConflictException(ConflictCode.DUPLICATE_KEY, List.of("operationKey " + id), duplicate);
        }
        return new Reservation.Reserved<>();
    }

    /** The response stored for an operation key that was reserved before; it must be the same request. */
    private <R> R storedResponse(Receipt stored, OperationKey id, ReceiptOperation operation, Sha256 digest,
            AuthorId author, Channel channel, Class<R> responseType) {
        boolean same = stored.operation() == operation && Objects.equals(stored.payloadSha256(), digest)
                && Objects.equals(stored.authorId().value(), author.value())
                && Objects.equals(stored.login(), channel.login());
        if (!same) {
            throw new ConflictException(ConflictCode.OPERATION_CONFLICT, List.of(id.toString()));
        }
        if (stored.response() == null) {
            throw new InfrastructureException(InfrastructureCode.INTEGRITY_VIOLATION,
                    List.of("committed receipt without a response: " + id));
        }
        return json.read(stored.response(), responseType);
    }

    /** Stores the response of the first call, once, before the transaction commits. */
    public void finish(OperationKey id, Object response) {
        if (!completion.finish(id, json.write(response))) {
            throw new InfrastructureException(InfrastructureCode.INTEGRITY_VIOLATION,
                    List.of("receipt missing or already finished: " + id));
        }
    }
}
