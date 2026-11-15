package org.pgledger.database.repository;

import org.pgledger.domain.value.JsonDocument;
import org.pgledger.domain.value.OperationKey;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Fills in the response of a reserved receipt, once, before the transaction of the write commits. */
@Repository
public class ReceiptCompletionRepository {

    private final JdbcClient jdbc;

    public ReceiptCompletionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** True when the receipt existed without a response and now has one. */
    public boolean finish(OperationKey operationKey, JsonDocument response) {
        return jdbc.sql("UPDATE request_receipt SET response = :response WHERE operation_key = :key AND response IS NULL")
                .param("response", response.text())
                .param("key", operationKey.toString())
                .update() == 1;
    }
}
