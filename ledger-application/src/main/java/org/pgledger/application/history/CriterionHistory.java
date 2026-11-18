package org.pgledger.application.history;

import java.util.List;
import org.pgledger.domain.model.HistoryEntry;
import org.pgledger.domain.rule.CriterionReports;
import org.pgledger.domain.rule.CriterionState;
import org.pgledger.domain.rule.CriterionTransition;
import org.pgledger.domain.rule.HistoryOp;
import org.pgledger.domain.value.Tokens;

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
