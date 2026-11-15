package org.pgledger.mcp.support;

import java.util.List;
import org.pgledger.mcp.tool.ReadOutputs;
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
