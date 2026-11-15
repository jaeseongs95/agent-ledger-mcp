package org.pgledger.domain.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;
import org.pgledger.domain.rule.Channel;
import org.pgledger.domain.value.ActorId;
import org.pgledger.domain.value.JsonDocument;
import org.pgledger.domain.value.OperationKey;
import org.pgledger.domain.value.Sha256;

/**
 * Idempotency record of a write. It is inserted first, in the transaction of the write, with no
 * response; the response is filled in before commit, so a committed receipt always has one. The key of the table is the pair
 * (author, operation key), like the upload of {@code artifact-begin}: each author has the keys of its own, so a key that
 * another author wrote first is free for this one. The {@code @Id} names the operation key for the explicit insert only; a
 * receipt is looked up by author and key.
 */
@Table("request_receipt")
public record Receipt(@Id OperationKey operationKey, ReceiptOperation operation, Sha256 payloadSha256, ActorId authorId,
        String login, JsonDocument response, Instant createdAt) {

    /** A reservation made by this ledger; {@code response} is not known yet. */
    public static Receipt reserve(OperationKey id, ReceiptOperation operation, Sha256 payload, ActorId author,
            Channel channel, Instant now) {
        return new Receipt(id, operation, payload, author, channel.login(), null, now);
    }
}
