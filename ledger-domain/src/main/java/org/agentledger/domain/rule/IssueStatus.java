// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.domain.rule;

import java.util.List;
import java.util.Objects;
import org.agentledger.domain.error.TransitionCode;
import org.agentledger.domain.error.TransitionException;
import org.agentledger.domain.value.Evidence;
import org.agentledger.domain.value.IssueId;
import org.agentledger.domain.value.SessionKey;

/** Status of an issue, with the transition policy as a pure function of the facts handed in. */
public enum IssueStatus implements LifecycleState {
    OPEN, RESOLVED, VERIFIED, NOT_AN_ISSUE, DISPUTED, MERGED;

    @Override
    public boolean verification() {
        return this == VERIFIED;
    }

    /**
     * Checks a move from this status to {@code to}: same state is refused, RESOLVED requires at least one recorded
     * deliverable, leaving VERIFIED requires evidence, entering VERIFIED requires RESOLVED + evidence + deliverables
     * and an independent reviewer session.
     *
     * @param self   the issue being moved
     * @param target the merge target, or {@code null}
     */
    public void requireTransitionTo(IssueStatus to, Evidence evidence, DeliverableHistory deliverables, SessionKey actor,
            IssueId self, MergeTarget target) {
        if (this == to) {
            throw new TransitionException(TransitionCode.TRANSITION_INVALID, List.of(name(), to.name()));
        }
        if (to == RESOLVED && !deliverables.hasDeliverable()) {
            throw new TransitionException(TransitionCode.DELIVERABLE_REQUIRED, List.of(to.name()));
        }
        if (this == VERIFIED && !evidence.provided()) {
            throw evidenceRequired(to);
        }
        switch (to) {
            case VERIFIED -> {
                if (this != RESOLVED) {
                    throw new TransitionException(TransitionCode.TRANSITION_INVALID, List.of(name(), to.name()));
                }
                if (!evidence.provided()) {
                    throw evidenceRequired(to);
                }
                if (!deliverables.hasDeliverable()) {
                    throw new TransitionException(TransitionCode.DELIVERABLE_REQUIRED, List.of(to.name()));
                }
                if (deliverables.involves(actor)) {
                    throw new TransitionException(TransitionCode.SAME_SESSION, List.of());
                }
            }
            case NOT_AN_ISSUE, DISPUTED -> {
                if (!evidence.provided()) {
                    throw evidenceRequired(to);
                }
            }
            case MERGED -> {
                if (target == null || Objects.equals(target.id(), self)
                        || target.status() == MERGED || target.status() == VERIFIED) {
                    throw new TransitionException(TransitionCode.MERGE_TARGET_INVALID, List.of());
                }
            }
            case OPEN, RESOLVED -> {
                // OPEN is allowed from any state (with evidence when leaving VERIFIED); RESOLVED checked deliverable above
            }
        }
    }

    private static TransitionException evidenceRequired(IssueStatus to) {
        return new TransitionException(TransitionCode.EVIDENCE_REQUIRED, List.of(to.name()));
    }
}
