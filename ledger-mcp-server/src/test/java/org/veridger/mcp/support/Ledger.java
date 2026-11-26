// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.mcp.support;

import java.util.List;
import org.veridger.mcp.tool.ReadOutputs;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Reads of the ledger through the read tools, for the cases that check what was (not) written. */
public final class Ledger {

    private Ledger() {
    }

    /** Every history row of the project (the tests keep a project small enough for one page). */
    public static List<ReadOutputs.History> history(McpSession session, String project) {
        ReadOutputs.Items<ReadOutputs.History> page = session.readItems(
                session.call("ledger_history", Calls.History.of(project)), ReadOutputs.History.class);
        return page.items();
    }

    /** How many write receipts hold exactly this operation key (a refused call leaves none, a replay still one). */
    public static long receipts(Scenario scenario, String operationKey) {
        return scenario.bean(JdbcClient.class).sql("SELECT COUNT(*) FROM request_receipt WHERE operation_key = ?")
                .param(operationKey).query(Long.class).single();
    }

    /** How many write receipts this author holds under exactly this operation key (the key is per author: 0 or 1). */
    public static long receipts(Scenario scenario, String author, String operationKey) {
        return scenario.bean(JdbcClient.class)
                .sql("SELECT COUNT(*) FROM request_receipt WHERE author_id = ? AND operation_key = ?")
                .param(author).param(operationKey).query(Long.class).single();
    }

    /** The operations ({@code SAVE}, {@code BATCH}, ...) of the receipts that hold exactly this operation key, in any order. */
    public static List<String> receiptOperations(Scenario scenario, String operationKey) {
        return scenario.bean(JdbcClient.class).sql("SELECT operation FROM request_receipt WHERE operation_key = ?")
                .param(operationKey).query(String.class).list();
    }

    /**
     * How many writes were made with this operation key as the caller sent it: 1 when a receipt is held under the key itself,
     * otherwise 0. A single write holds its receipt under its key, and so does a batch (operation {@code BATCH}, next to the
     * receipts of its operations under the keys derived from the batch key), so a receipt under the key itself is the mark of
     * a write for every write tool.
     */
    public static long writes(Scenario scenario, String operationKey) {
        return receipts(scenario, operationKey) > 0 ? 1 : 0;
    }

    /** How many write receipts the author holds, for a call that has no key of its own to look for (it must change nothing). */
    public static long receiptsOf(Scenario scenario, String author) {
        return scenario.bean(JdbcClient.class).sql("SELECT COUNT(*) FROM request_receipt WHERE author_id = ?")
                .param(author).query(Long.class).single();
    }

    /** The item of {@code ledger_get} for the id, or the ids that are not found. */
    public static ReadOutputs.Got get(McpSession session, String... ids) {
        return session.ok("ledger_get", new Calls.Ids(List.of(ids)), ReadOutputs.Got.class);
    }
}
