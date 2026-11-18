// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.domain.rule;

import java.util.List;
import java.util.Objects;
import org.pgledger.domain.value.SessionKey;

/** History of transitions on one milestone criterion so self-completion cannot self-verify. */
public record CriterionReports(List<CriterionTransition> transitions) {

    public static final CriterionReports NONE = new CriterionReports(List.of());

    public CriterionReports {
        transitions = List.copyOf(transitions);
    }

    /** True when {@code session} moved the criterion to {@link CriterionState#COMPLETED} from a non-verified state. */
    public boolean completedBy(SessionKey session) {
        return transitions.stream().filter(t -> t.to() == CriterionState.COMPLETED && !t.from().verification())
                .anyMatch(t -> Objects.equals(t.author().sessionKey(), session));
    }
}
