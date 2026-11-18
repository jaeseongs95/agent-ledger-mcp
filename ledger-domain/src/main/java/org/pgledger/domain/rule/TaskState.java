package org.pgledger.domain.rule;

import java.util.List;
import org.pgledger.domain.error.TransitionCode;
import org.pgledger.domain.error.TransitionException;
import org.pgledger.domain.value.Evidence;
import org.pgledger.domain.value.SessionKey;

/** State of a task, with its transition policy. */
public enum TaskState implements LifecycleState {
    UNKNOWN, PLANNED, ACTIVE, BLOCKED, REPORTED_DONE, VERIFIED, CANCELLED;

    @Override
    public boolean verification() {
        return this == VERIFIED;
    }

    /**
     * Checks a move from this state to {@code to} of a task that no session registered and none owns.
     *
     * @param actor the session that makes the move
     */
    public void requireTransitionTo(TaskState to, Evidence evidence, SessionKey actor, TaskReports reports) {
        requireTransition(to, evidence, actor, false, reports);
    }

    /**
     * Checks a move from this state to {@code to} of a task that was registered by a session or is owned by one. Whoever
     * registered or owns the task cannot verify it, and neither can a session that reported it done.
     *
     * @param actor       the session that makes the move
     * @param registrants the sessions that registered the task and that own it (a list, never an Optional: it is empty
     *                    when neither is known)
     */
    public void requireTransitionTo(TaskState to, Evidence evidence, SessionKey actor, List<SessionKey> registrants,
            TaskReports reports) {
        requireTransition(to, evidence, actor, registrants.contains(actor), reports);
    }

    private void requireTransition(TaskState to, Evidence evidence, SessionKey actor, boolean actorRegisteredTask,
            TaskReports reports) {
        if (this == to || to == UNKNOWN) {
            throw new TransitionException(TransitionCode.TRANSITION_INVALID, List.of(name(), to.name()));
        }
        if (to == REPORTED_DONE && !evidence.provided()) {
            throw evidenceRequired(to);
        }
        if (this == VERIFIED && !evidence.provided()) {
            throw evidenceRequired(to);
        }
        if (to == VERIFIED) {
            if (this != REPORTED_DONE) {
                throw new TransitionException(TransitionCode.TRANSITION_INVALID, List.of(name(), to.name()));
            }
            if (!evidence.provided()) {
                throw evidenceRequired(to);
            }
            if (actorRegisteredTask || reports.reportedBy(actor)) {
                throw new TransitionException(TransitionCode.SAME_SESSION, List.of());
            }
        }
    }

    private static TransitionException evidenceRequired(TaskState to) {
        return new TransitionException(TransitionCode.EVIDENCE_REQUIRED, List.of(to.name()));
    }
}
