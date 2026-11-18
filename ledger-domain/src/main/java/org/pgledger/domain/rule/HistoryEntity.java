package org.pgledger.domain.rule;

import org.pgledger.domain.value.Token;

/** The kind of row a history entry is about. */
public enum HistoryEntity implements Token {
    ISSUE("issue"),
    SPEC("spec"),
    TASK("task"),
    CRITERION("criterion"),
    DELIVERABLE("deliverable"),
    ISSUE_TASK("issue_task"),
    ISSUE_SPEC("issue_spec"),
    SPEC_TASK("spec_task"),
    ISSUE_RELATION("issue_relation"),
    CRITERION_LINK("criterion_link"),
    ISSUE_CASE("issue_case");

    private final String token;

    HistoryEntity(String token) {
        this.token = token;
    }

    @Override
    public String token() {
        return token;
    }

    /** The grant scope required to write this kind of history row on a project. */
    public Scope writeScope() {
        return switch (this) {
            case ISSUE, ISSUE_TASK, ISSUE_RELATION, ISSUE_CASE -> Scope.ISSUE;
            case SPEC, ISSUE_SPEC, SPEC_TASK -> Scope.SPEC;
            case TASK, CRITERION, CRITERION_LINK -> Scope.TASK;
            case DELIVERABLE -> Scope.DELIVERABLE;
        };
    }
}
