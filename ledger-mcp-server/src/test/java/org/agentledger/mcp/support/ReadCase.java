// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.mcp.support;

import java.util.List;
import java.util.function.Function;

/** One read tool with valid minimal arguments against a {@link Seed}. */
public record ReadCase(String tool, Function<Seed, Object> arguments) {

    @Override
    public String toString() {
        return tool;
    }

    public Object call(Seed seed) {
        return arguments.apply(seed);
    }

    /** The fifteen read tools of the 2.0 contract. */
    public static List<ReadCase> all() {
        return List.of(
                new ReadCase("ledger_get", seed -> new Calls.Ids(List.of(seed.issue(), seed.spec(), seed.task()))),
                new ReadCase("ledger_issue", seed -> new Calls.IdOnly(seed.issue())),
                new ReadCase("ledger_spec", seed -> new Calls.IdOnly(seed.spec())),
                new ReadCase("ledger_task", seed -> new Calls.IdOnly(seed.task())),
                new ReadCase("ledger_criterion", seed -> new Calls.IdOnly(seed.criterion())),
                new ReadCase("ledger_history", seed -> Calls.History.of(seed.project())),
                new ReadCase("ledger_search", seed -> new Calls.Search(List.of("first"))),
                new ReadCase("ledger_issues", seed -> new Calls.ProjectOnly(seed.project())),
                new ReadCase("ledger_specs", seed -> new Calls.ProjectOnly(seed.project())),
                new ReadCase("ledger_tasks", seed -> new Calls.ProjectOnly(seed.project())),
                new ReadCase("ledger_criteria", seed -> new Calls.ProjectOnly(seed.project())),
                new ReadCase("ledger_attention", seed -> new Calls.Nothing()),
                new ReadCase("ledger_stats", seed -> new Calls.Nothing()),
                new ReadCase("ledger_projects", seed -> new Calls.Nothing()),
                new ReadCase("ledger_codes", seed -> new Calls.Nothing()));
    }
}
