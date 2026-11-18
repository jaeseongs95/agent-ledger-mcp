package org.pgledger.domain.rule;

import java.util.Optional;
import org.pgledger.domain.value.Token;
import org.pgledger.domain.value.Tokens;

/** A state of an issue, spec, task, or milestone criterion. Verification states can only be entered or left by a reviewer. */
public sealed interface LifecycleState extends Token
        permits IssueStatus, SpecStatus, TaskState, CriterionState {

    String name();

    /** True for VERIFIED, MET and NOT_MET: moving into or out of such a state is an independent verification. */
    boolean verification();

    @Override
    default String token() {
        return name();
    }

    static Optional<LifecycleState> of(HistoryEntity entity, String stored) {
        return switch (entity) {
            case ISSUE -> Tokens.find(stored, IssueStatus.values()).map(state -> state);
            case SPEC -> Tokens.find(stored, SpecStatus.values()).map(state -> state);
            case TASK -> Tokens.find(stored, TaskState.values()).map(state -> state);
            case CRITERION -> Tokens.find(stored, CriterionState.values()).map(state -> state);
            case DELIVERABLE, ISSUE_TASK, ISSUE_SPEC, SPEC_TASK, ISSUE_RELATION, CRITERION_LINK, ISSUE_CASE ->
                Optional.empty();
        };
    }

    static boolean isVerification(HistoryEntity entity, String stored) {
        return of(entity, stored).map(LifecycleState::verification).orElse(false);
    }
}
