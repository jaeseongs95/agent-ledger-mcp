// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.domain.rule;

import java.util.List;
import org.agentledger.domain.error.TransitionCode;
import org.agentledger.domain.error.TransitionException;
import org.agentledger.domain.value.Evidence;
import org.agentledger.domain.value.SessionKey;

/**
 * Lifecycle status of a specification ({@code spec}): covers drafting, planning, active work, completion reporting,
 * independent verification, and cancellation in any domain (engineering, media, documentation, operations).
 */
public enum SpecStatus implements LifecycleState {
    DRAFT, PLANNED, ACTIVE, BLOCKED, COMPLETED, VERIFIED, CANCELLED;

    @Override
    public boolean verification() {
        return this == VERIFIED;
    }

    /**
     * Checks a move from this status to {@code to}: moving to {@link #COMPLETED} requires completion evidence,
     * leaving {@link #VERIFIED} requires evidence, and moving to {@link #VERIFIED} requires prior {@link #COMPLETED}
     * status, evidence, and an independent session different from any creator, owner, deliverable recorder, or
     * completion reporter.
     */
    public void requireTransitionTo(SpecStatus to, Evidence evidence, SessionKey actor, List<SessionKey> contributors,
            SpecReports reports) {
        if (this == to) {
            throw new TransitionException(TransitionCode.TRANSITION_INVALID, List.of(name(), to.name()));
        }
        if (to == COMPLETED && !evidence.provided()) {
            throw evidenceRequired(to);
        }
        if (this == VERIFIED && !evidence.provided()) {
            throw evidenceRequired(to);
        }
        if (to == VERIFIED) {
            if (this != COMPLETED) {
                throw new TransitionException(TransitionCode.TRANSITION_INVALID, List.of(name(), to.name()));
            }
            if (!evidence.provided()) {
                throw evidenceRequired(to);
            }
            if (contributors.contains(actor) || reports.completedBy(actor)) {
                throw new TransitionException(TransitionCode.SAME_SESSION, List.of());
            }
        }
    }

    private static TransitionException evidenceRequired(SpecStatus to) {
        return new TransitionException(TransitionCode.EVIDENCE_REQUIRED, List.of(to.name()));
    }
}
