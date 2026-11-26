// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.domain.model;

import org.veridger.domain.rule.HistoryEntity;
import org.veridger.domain.value.AuthorId;
import org.veridger.domain.value.Patch;
import org.veridger.domain.value.ProjectId;
import org.veridger.domain.value.TextRule;

/** Editable fields of a task. */
public record TaskFields(Patch<ProjectId> projectId, Patch<String> title, Patch<String> purpose,
        Patch<String> successCondition, Patch<AuthorId> ownerActorId, Patch<String> originRef,
        Patch<String> originQuote) implements EntityFields {

    public static TaskFields of(Patch<String> projectId, Patch<String> title, Patch<String> purpose,
            Patch<String> successCondition, Patch<String> ownerActorId, Patch<String> originRef,
            Patch<String> originQuote) {
        return new TaskFields(projectId.map(ProjectId::of), title.map(TextRule.TITLE::required),
                purpose.map(TextRule.PURPOSE::givenOrEmpty),
                successCondition.map(TextRule.SUCCESS_CONDITION::givenOrEmpty),
                ownerActorId.map(IssueFields::author), originRef.map(TextRule.ORIGIN_REF::optional),
                originQuote.map(TextRule.ORIGIN_QUOTE::optional));
    }

    @Override
    public HistoryEntity entity() {
        return HistoryEntity.TASK;
    }

    @Override
    public TaskFields requireCreatable() {
        EntityFields.requireValue("project_id", projectId);
        EntityFields.requireValue("title", title);
        EntityFields.requireValue("purpose", purpose);
        EntityFields.requireValue("success_condition", successCondition);
        return this;
    }

    @Override
    public TaskFields requireEditable() {
        EntityFields.requireAnyMentioned(projectId, title, purpose, successCondition, ownerActorId, originRef,
                originQuote);
        return this;
    }
}
