// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.domain.rule;

import org.agentledger.domain.value.Token;

/** Entity kinds that can own a recorded deliverable. */
public enum DeliverableTarget implements Token {
    ISSUE("issue", Scope.ISSUE),
    SPEC("spec", Scope.SPEC),
    TASK("task", Scope.TASK);

    private final String token;
    private final Scope writeScope;

    DeliverableTarget(String token, Scope writeScope) {
        this.token = token;
        this.writeScope = writeScope;
    }

    @Override
    public String token() {
        return token;
    }

    public Scope writeScope() {
        return writeScope;
    }

    public HistoryEntity historyEntity() {
        return switch (this) {
            case ISSUE -> HistoryEntity.ISSUE;
            case SPEC -> HistoryEntity.SPEC;
            case TASK -> HistoryEntity.TASK;
        };
    }
}
