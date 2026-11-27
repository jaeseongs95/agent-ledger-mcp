// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application.receipt;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.veridger.application.json.LedgerJson;
import org.veridger.database.repository.ReceiptCompletionRepository;
import org.veridger.database.repository.ReceiptRepository;
import org.veridger.domain.error.ConflictCode;
import org.veridger.domain.error.ConflictException;
import org.veridger.domain.error.InfrastructureCode;
import org.veridger.domain.error.InfrastructureException;
import org.veridger.domain.model.Receipt;
import org.veridger.domain.model.ReceiptOperation;
import org.veridger.domain.rule.Channel;
import org.veridger.domain.value.AuthorId;
import org.veridger.domain.value.OperationKey;
import org.veridger.domain.value.Sha256;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * Idempotency. It has no transaction of its own: it joins the one of the write (principle 4.4). The receipt of the author and
 * the operation key is read first (each author has the keys of its own, like the uploads of {@code artifact-begin}): a committed
 * receipt of an earlier call answers with its stored response (a replay) or, for another operation, payload or channel under the
 * same key, with OPERATION_CONFLICT. Without one the receipt is inserted, in the transaction of the write.
 * A second call at the very same moment meets the unique key of the author and the operation key (the insert waits until the first call
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

    /** Result of a single {@code request_receipt} lookup shared between gate checks and reservation. */
    public sealed interface LoadedReceipt {

        /** A stored receipt row exists for the author and operation key. */
        record Found(Receipt receipt) implements LoadedReceipt {
        }

        /** No receipt row exists yet for the author and operation key. */
        record Absent() implements LoadedReceipt {
        }
    }

    /** One pending receipt to complete as part of a batch flush. */
    public record Finished(OperationKey operationKey, AuthorId author, Object response) {
    }

    /**
     * Reserves the operation key: {@link Reservation.Reserved} when the caller is the first and writes,
     * {@link Reservation.Replayed} with the stored response when the same request was answered before.
     *
     * @throws ConflictException OPERATION_CONFLICT when the key of this author belongs to another operation, payload or
     *         channel; DUPLICATE_KEY when another call of this author reserved the key at the very same moment
     */
    public <R> Reservation<R> reserve(OperationKey id, ReceiptOperation operation, Sha256 digest, AuthorId author,
            Channel channel, Class<R> responseType) {
        LoadedReceipt loaded = receipts.findByAuthorIdAndOperationKey(author, id)
                .filter(stored -> Objects.equals(author, stored.authorId()) && Objects.equals(id, stored.operationKey()))
                .<LoadedReceipt>map(LoadedReceipt.Found::new)
                .orElseGet(LoadedReceipt.Absent::new);
        return reserveWithLoaded(id, operation, digest, author, channel, responseType, loaded);
    }

    /**
     * Same as {@link #reserve(OperationKey, ReceiptOperation, Sha256, AuthorId, Channel, Class)}, reusing a receipt
     * lookup already executed in the same transaction (for example by {@code WriteOperation}'s answered-call check) to
     * avoid a duplicate {@code request_receipt} SELECT.
     */
    public <R> Reservation<R> reserveWithLoaded(OperationKey id, ReceiptOperation operation, Sha256 digest,
            AuthorId author, Channel channel, Class<R> responseType, LoadedReceipt loaded) {
        return switch (loaded) {
            case LoadedReceipt.Found found -> new Reservation.Replayed<>(
                    storedResponse(found.receipt(), author, id, operation, digest, channel, responseType));
            case LoadedReceipt.Absent absent -> insertReservation(id, operation, digest, author, channel);
        };
    }

    /** Looks up a stored receipt once for reuse across answered-target resolution and reservation. */
    public LoadedReceipt find(AuthorId author, String operationKey) {
        return OperationKey.find(operationKey)
                .flatMap(id -> receipts.findByAuthorIdAndOperationKey(author, id)
                        .filter(stored -> Objects.equals(author, stored.authorId())
                                && Objects.equals(id, stored.operationKey())))
                .<LoadedReceipt>map(LoadedReceipt.Found::new)
                .orElseGet(LoadedReceipt.Absent::new);
    }

    /** Extracts a completed stored response for {@code operation} from an already-loaded receipt. */
    public <R> Optional<R> answered(LoadedReceipt loaded, ReceiptOperation operation, Class<R> responseType) {
        if (loaded instanceof LoadedReceipt.Found found
                && found.receipt().operation() == operation
                && found.receipt().response() != null) {
            return Optional.of(json.read(found.receipt().response(), responseType));
        }
        return Optional.empty();
    }

    /**
     * The response that the receipt of this author and key stores, when the receipt is of {@code operation} and finished:
     * what a replay of the key answers if the rest of the request is the same. Nothing is reserved and nothing is
     * compared ({@link #reserve} compares the request); the gate asks it to find out which grants the call that was
     * answered needed, because a replay is checked against those and not against the state the world has now.
     */
    public <R> Optional<R> answered(AuthorId author, String operationKey, ReceiptOperation operation,
            Class<R> responseType) {
        return answered(find(author, operationKey), operation, responseType);
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

    /** The response stored for an operation key that this author reserved before; it must be the exact same request. */
    private <R> R storedResponse(Receipt stored, AuthorId author, OperationKey id, ReceiptOperation operation,
            Sha256 digest, Channel channel, Class<R> responseType) {
        boolean same = Objects.equals(stored.authorId(), author) && Objects.equals(stored.operationKey(), id)
                && stored.operation() == operation && Objects.equals(stored.payloadSha256(), digest)
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
    public void finish(OperationKey id, AuthorId author, Object response) {
        if (!completion.finish(author, id, json.write(response))) {
            throw new InfrastructureException(InfrastructureCode.INTEGRITY_VIOLATION,
                    List.of("receipt missing or already finished: " + id));
        }
    }

    /** Stores responses for multiple reserved receipts in a single JDBC batch update. */
    public void finishAll(List<Finished> items) {
        if (items.isEmpty()) {
            return;
        }
        List<ReceiptCompletionRepository.FinishedReceipt> encoded = items.stream()
                .map(item -> new ReceiptCompletionRepository.FinishedReceipt(item.author(), item.operationKey(),
                        json.write(item.response())))
                .toList();
        OperationKey failed = completion.finishAll(encoded);
        if (failed != null) {
            throw new InfrastructureException(InfrastructureCode.INTEGRITY_VIOLATION,
                    List.of("receipt missing or already finished: " + failed));
        }
    }
}
