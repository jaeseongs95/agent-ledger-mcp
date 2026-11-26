// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.application.history;

import java.util.List;
import org.agentledger.domain.model.HistoryEntry;
import org.agentledger.domain.rule.HistoryOp;
import org.agentledger.domain.rule.SpecReports;
import org.agentledger.domain.rule.SpecStatus;
import org.agentledger.domain.rule.SpecTransition;
import org.agentledger.domain.value.Tokens;

/**
 * History rows of one specification, oldest first, projected into {@link SpecReports}.
 */
public record SpecHistory(List<HistoryEntry> entries) {

    public SpecHistory {
        entries = List.copyOf(entries);
    }

    public SpecReports reports() {
        List<SpecTransition> transitions = entries.stream()
                .filter(entry -> entry.op() == HistoryOp.TRANSITION)
                .flatMap(entry -> Tokens.find(entry.fromState(), SpecStatus.values())
                        .flatMap(from -> Tokens.find(entry.toState(), SpecStatus.values())
                                .map(to -> new SpecTransition(from, to, entry.authorId())))
                        .stream())
                .toList();
        return new SpecReports(transitions);
    }
}
