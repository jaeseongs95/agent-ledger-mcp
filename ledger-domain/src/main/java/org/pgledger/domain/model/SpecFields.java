package org.pgledger.domain.model;

import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.value.AuthorId;
import org.pgledger.domain.value.Patch;
import org.pgledger.domain.value.ProjectId;
import org.pgledger.domain.value.SpecId;
import org.pgledger.domain.value.TextRule;

/**
 * Editable fields of a domain-neutral specification ({@code spec}).
 */
public record SpecFields(Patch<ProjectId> projectId, Patch<SpecId> parentId, Patch<String> title,
        Patch<String> summary, Patch<String> acceptanceCriteria, Patch<String> priority, Patch<AuthorId> ownerActorId,
        Patch<String> originRef, Patch<String> originQuote) implements EntityFields {

    public static SpecFields of(Patch<String> projectId, Patch<String> parentId, Patch<String> title,
            Patch<String> summary, Patch<String> acceptanceCriteria, Patch<String> priority, Patch<String> ownerActorId,
            Patch<String> originRef, Patch<String> originQuote) {
        return new SpecFields(projectId.map(ProjectId::of), parentId.map(SpecFields::specId),
                title.map(TextRule.TITLE::required), summary.map(TextRule.SUMMARY::givenOrEmpty),
                acceptanceCriteria.map(TextRule.ACCEPTANCE_CRITERIA::givenOrEmpty),
                priority.map(TextRule.PRIORITY::optional), ownerActorId.map(IssueFields::author),
                originRef.map(TextRule.ORIGIN_REF::optional), originQuote.map(TextRule.ORIGIN_QUOTE::optional));
    }

    private static SpecId specId(String raw) {
        return raw == null || raw.isBlank() ? null : SpecId.of(raw);
    }

    @Override
    public HistoryEntity entity() {
        return HistoryEntity.SPEC;
    }

    @Override
    public SpecFields requireCreatable() {
        EntityFields.requireValue("project_id", projectId);
        EntityFields.requireValue("title", title);
        return this;
    }

    @Override
    public SpecFields requireEditable() {
        EntityFields.requireAnyMentioned(projectId, parentId, title, summary, acceptanceCriteria, priority,
                ownerActorId, originRef, originQuote);
        return this;
    }
}
