// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.application.history;

import java.util.List;
import org.pgledger.domain.model.HistoryEntry;
import org.pgledger.domain.rule.HistoryOp;
import org.pgledger.domain.rule.SpecReports;
import org.pgledger.domain.rule.SpecStatus;
import org.pgledger.domain.rule.SpecTransition;
import org.pgledger.domain.value.Tokens;

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
