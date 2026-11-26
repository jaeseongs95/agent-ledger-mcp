// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.domain.rule;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.veridger.domain.value.SessionKey;

/** The transitions of one specification, oldest first; tracks who marked completion so they cannot self-verify. */
public record SpecReports(List<SpecTransition> transitions) {

    public static final SpecReports NONE = new SpecReports(List.of());

    public SpecReports {
        transitions = List.copyOf(transitions);
    }

    public Optional<SpecTransition> latestCompletion() {
        return transitions.reversed().stream().filter(SpecReports::isCompletion).findFirst();
    }

    /** True when {@code session} moved the spec to {@link SpecStatus#COMPLETED} from a non-verified state. */
    public boolean completedBy(SessionKey session) {
        return transitions.stream().filter(SpecReports::isCompletion)
                .anyMatch(t -> Objects.equals(t.author().sessionKey(), session));
    }

    private static boolean isCompletion(SpecTransition t) {
        return t.to() == SpecStatus.COMPLETED && t.from() != SpecStatus.VERIFIED;
    }
}
