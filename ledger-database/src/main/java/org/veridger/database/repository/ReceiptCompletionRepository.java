// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.database.repository;

import java.util.List;
import org.veridger.domain.value.ActorId;
import org.veridger.domain.value.JsonDocument;
import org.veridger.domain.value.OperationKey;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Fills in the response of a reserved receipt, once, before the transaction of the write commits. */
@Repository
public class ReceiptCompletionRepository {

    /** One receipt to complete as part of a batch flush. */
    public record FinishedReceipt(ActorId author, OperationKey operationKey, JsonDocument response) {
    }

    private static final String FINISH_POSITIONAL = "UPDATE request_receipt SET response = ? WHERE author_id = ? "
            + "AND operation_key = ? AND response IS NULL";

    private final JdbcClient jdbc;
    private final JdbcTemplate batchJdbc;
    private final SqlBuilder sqlBuilder;

    public ReceiptCompletionRepository(JdbcClient jdbc, JdbcTemplate batchJdbc, SqlBuilder sqlBuilder) {
        this.jdbc = jdbc;
        this.batchJdbc = batchJdbc;
        this.sqlBuilder = sqlBuilder;
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

    /**
     * Completes multiple reserved receipts in a single JDBC batch update when the database driver reliably reports
     * exact per-row update counts (falling back to individual updates otherwise), returning the first failing key if
     * any receipt was missing or already finished (or {@code null} when every receipt updated exactly one row).
     * Never treats {@code Statement.SUCCESS_NO_INFO} as success, preserving fail-closed integrity.
     */
    public OperationKey finishAll(List<FinishedReceipt> items) {
        if (items.isEmpty()) {
            return null;
        }
        if (batchJdbc == null || sqlBuilder == null || !sqlBuilder.supportsExactBatchUpdateCounts()
                || items.size() == 1) {
            for (FinishedReceipt item : items) {
                if (!finish(item.author(), item.operationKey(), item.response())) {
                    return item.operationKey();
                }
            }
            return null;
        }
        int[] counts = batchJdbc.batchUpdate(FINISH_POSITIONAL, items, items.size(), (ps, item) -> {
            ps.setString(1, item.response().text());
            ps.setString(2, item.author().value());
            ps.setString(3, item.operationKey().toString());
        })[0];
        if (counts.length != items.size()) {
            return items.getFirst().operationKey();
        }
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] != 1) {
                return items.get(i).operationKey();
            }
        }
        return null;
    }
}
