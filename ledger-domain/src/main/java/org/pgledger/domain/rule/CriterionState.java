// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.domain.rule;

import java.util.List;
import org.pgledger.domain.error.TransitionCode;
import org.pgledger.domain.error.TransitionException;
import org.pgledger.domain.value.Evidence;
import org.pgledger.domain.value.SessionKey;

/** State of a milestone criterion, with completion tracking and independent verification policy. */
public enum CriterionState implements LifecycleState {
    UNKNOWN, PLANNED, ACTIVE, COMPLETED, MET, NOT_MET;

    @Override
    public boolean verification() {
        return this == MET || this == NOT_MET;
    }

    /**
     * Checks a move from this state to {@code to}. Moving to {@link #COMPLETED} needs evidence; moving to
     * {@link #MET} or {@link #NOT_MET} needs evidence and a session different from every registrant or completion
     * reporter.
     */
    public void requireTransitionTo(CriterionState to, Evidence evidence, SessionKey actor,
            List<SessionKey> contributors, CriterionReports reports) {
        if (this == to) {
            throw new TransitionException(TransitionCode.TRANSITION_INVALID, List.of(name(), to.name()));
        }
        if (to == COMPLETED && !evidence.provided()) {
            throw new TransitionException(TransitionCode.EVIDENCE_REQUIRED, List.of(to.name()));
        }
        if (to.verification() && !evidence.provided()) {
            throw new TransitionException(TransitionCode.EVIDENCE_REQUIRED, List.of(to.name()));
        }
        if (to.verification() && (contributors.contains(actor) || reports.completedBy(actor))) {
            throw new TransitionException(TransitionCode.SAME_SESSION, List.of());
        }
    }
}
