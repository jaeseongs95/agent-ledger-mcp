// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.domain.model;

import org.veridger.domain.rule.HistoryEntity;
import org.veridger.domain.value.AuthorId;
import org.veridger.domain.value.CodeValue;
import org.veridger.domain.value.Patch;
import org.veridger.domain.value.ProjectId;
import org.veridger.domain.value.TextRule;

/**
 * Editable fields of an issue. The code columns {@code category} and {@code severity} are checked against common
 * codes by the code catalogue rather than here.
 */
public record IssueFields(Patch<ProjectId> projectId, Patch<String> title, Patch<CodeValue> category,
        Patch<CodeValue> severity, Patch<String> symptom, Patch<String> rootCause, Patch<AuthorId> ownerActorId)
        implements EntityFields {

    /**
     * Normalises raw boundary values: required texts are validated, optional ones blank to null; {@code symptom} and
     * {@code root_cause} must be supplied when present and may be empty (stored as SQL NULL).
     */
    public static IssueFields of(Patch<String> projectId, Patch<String> title, Patch<String> category,
            Patch<String> severity, Patch<String> symptom, Patch<String> rootCause, Patch<String> ownerActorId) {
        return new IssueFields(projectId.map(ProjectId::of), title.map(TextRule.TITLE::required),
                category.map(IssueFields::code), severity.map(IssueFields::code),
                symptom.map(TextRule.SYMPTOM::givenOrEmpty), rootCause.map(TextRule.ROOT_CAUSE::givenOrEmpty),
                ownerActorId.map(IssueFields::author));
    }

    static CodeValue code(String raw) {
        return raw == null || raw.isBlank() ? null : CodeValue.of(raw);
    }

    static AuthorId author(String raw) {
        return raw == null || raw.isBlank() ? null : AuthorId.of(raw);
    }

    @Override
    public HistoryEntity entity() {
        return HistoryEntity.ISSUE;
    }

    @Override
    public IssueFields requireCreatable() {
        EntityFields.requireValue("project_id", projectId);
        EntityFields.requireValue("title", title);
        EntityFields.requireValue("symptom", symptom);
        EntityFields.requireValue("root_cause", rootCause);
        return this;
    }

    @Override
    public IssueFields requireEditable() {
        EntityFields.requireAnyMentioned(projectId, title, category, severity, symptom, rootCause, ownerActorId);
        return this;
    }
}
