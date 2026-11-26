// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.application.history;

import java.util.List;
import org.agentledger.domain.model.HistoryEntry;
import org.agentledger.domain.rule.CriterionReports;
import org.agentledger.domain.rule.CriterionState;
import org.agentledger.domain.rule.CriterionTransition;
import org.agentledger.domain.rule.HistoryOp;
import org.agentledger.domain.value.Tokens;

/**
 * History rows of one milestone criterion, oldest first, projected into {@link CriterionReports}.
 */
public record CriterionHistory(List<HistoryEntry> entries) {

    public CriterionHistory {
        entries = List.copyOf(entries);
    }

    public CriterionReports reports() {
        List<CriterionTransition> transitions = entries.stream()
                .filter(entry -> entry.op() == HistoryOp.TRANSITION)
                .flatMap(entry -> Tokens.find(entry.fromState(), CriterionState.values())
                        .flatMap(from -> Tokens.find(entry.toState(), CriterionState.values())
                                .map(to -> new CriterionTransition(from, to, entry.authorId())))
                        .stream())
                .toList();
        return new CriterionReports(transitions);
    }
}
