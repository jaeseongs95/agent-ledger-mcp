// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.domain.rule;

import java.util.List;
import org.agentledger.domain.error.TransitionCode;
import org.agentledger.domain.error.TransitionException;
import org.agentledger.domain.value.Evidence;
import org.agentledger.domain.value.SessionKey;

/** State of a task, with its transition policy. */
public enum TaskState implements LifecycleState {
    UNKNOWN, PLANNED, ACTIVE, BLOCKED, COMPLETED, VERIFIED, CANCELLED;

    @Override
    public boolean verification() {
        return this == VERIFIED;
    }

    public void requireTransitionTo(TaskState to, Evidence evidence, SessionKey actor, TaskReports reports) {
        requireTransition(to, evidence, actor, false, reports);
    }

    public void requireTransitionTo(TaskState to, Evidence evidence, SessionKey actor, List<SessionKey> registrants,
            TaskReports reports) {
        requireTransition(to, evidence, actor, registrants.contains(actor), reports);
    }

    private void requireTransition(TaskState to, Evidence evidence, SessionKey actor, boolean actorRegisteredTask,
            TaskReports reports) {
        if (this == to || to == UNKNOWN) {
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
            if (actorRegisteredTask || reports.reportedBy(actor)) {
                throw new TransitionException(TransitionCode.SAME_SESSION, List.of());
            }
        }
    }

    private static TransitionException evidenceRequired(TaskState to) {
        return new TransitionException(TransitionCode.EVIDENCE_REQUIRED, List.of(to.name()));
    }
}
