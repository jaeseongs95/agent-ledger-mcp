// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.database.repository;

import org.veridger.domain.value.ActorId;
import org.veridger.domain.value.JsonDocument;
import org.veridger.domain.value.OperationKey;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Fills in the response of a reserved receipt, once, before the transaction of the write commits. */
@Repository
public class ReceiptCompletionRepository {

    private final JdbcClient jdbc;

    public ReceiptCompletionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** True when the receipt of the author and key existed without a response and now has one. */
    public boolean finish(ActorId author, OperationKey operationKey, JsonDocument response) {
        return jdbc.sql("UPDATE request_receipt SET response = :response WHERE author_id = :author "
                + "AND operation_key = :key AND response IS NULL")
                .param("response", response.text())
                .param("author", author.value())
                .param("key", operationKey.toString())
                .update() == 1;
    }
}
