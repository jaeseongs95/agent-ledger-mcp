// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.domain.rule;

import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationReason;
import org.pgledger.domain.value.Token;

/** What a session grant allows on a project. */
public enum Scope implements Token {
    ISSUE("issue"),
    SPEC("spec"),
    TASK("task"),
    DELIVERABLE("deliverable"),
    VERIFY("verify");

    private final String token;

    Scope(String token) {
        this.token = token;
    }

    @Override
    public String token() {
        return token;
    }

    /**
     * The scope a state change needs: VERIFY when entering or leaving a verification state, otherwise the write scope
     * of the entity.
     */
    public static Scope forTransition(HistoryEntity entity, LifecycleState from, LifecycleState to) {
        if (from.verification() || to.verification()) {
            return VERIFY;
        }
        return switch (entity) {
            case ISSUE, SPEC, TASK, CRITERION -> entity.writeScope();
            case DELIVERABLE, ISSUE_TASK, ISSUE_SPEC, SPEC_TASK, ISSUE_RELATION, CRITERION_LINK, ISSUE_CASE ->
                throw ValidationException.invalid("entity", ValidationReason.HAS_NO_STATE, entity.token());
        };
    }
}
