package org.pgledger.domain.rule;

import java.util.List;
import org.pgledger.domain.error.TransitionCode;
import org.pgledger.domain.error.TransitionException;
import org.pgledger.domain.value.Evidence;
import org.pgledger.domain.value.SessionKey;

/** State of a release condition, with its transition policy. */
public enum ConditionState implements LifecycleState {
    UNKNOWN, MET, NOT_MET;

    @Override
    public boolean verification() {
        return this == MET || this == NOT_MET;
    }

    /**
     * Checks a move from this state to {@code to}. Moving to MET or NOT_MET needs evidence and a session other than
     * the one that registered the condition (the principle of the verification of a defect and of a task); moving back
     * to UNKNOWN needs neither (by design).
     *
     * @param actor       the session that makes the move
     * @param registrants the sessions that registered the condition (a list, never an Optional: empty when unknown)
     */
    public void requireTransitionTo(ConditionState to, Evidence evidence, SessionKey actor,
            List<SessionKey> registrants) {
        if (this == to) {
            throw new TransitionException(TransitionCode.TRANSITION_INVALID, List.of(name(), to.name()));
        }
        if (to.verification() && !evidence.provided()) {
            throw new TransitionException(TransitionCode.EVIDENCE_REQUIRED, List.of(to.name()));
        }
        if (to.verification() && registrants.contains(actor)) {
            throw new TransitionException(TransitionCode.SAME_SESSION, List.of());
        }
    }
}
