package org.pgledger.domain.model;

import java.util.Objects;
import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.rule.LinkKind;
import org.pgledger.domain.value.ActorId;
import org.pgledger.domain.value.IssueId;
import org.pgledger.domain.value.SpecId;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.relational.core.mapping.InsertOnlyProperty;
import org.springframework.data.relational.core.mapping.Table;

/** Link between an issue and a specification ({@code issue_spec}). */
@Table("issue_spec")
public record IssueSpecLink(IssueId issueId, SpecId specId, @CreatedBy @InsertOnlyProperty ActorId createdBy)
        implements Link {

    public IssueSpecLink {
        Objects.requireNonNull(issueId);
        Objects.requireNonNull(specId);
    }

    public IssueSpecLink(IssueId issueId, SpecId specId) {
        this(issueId, specId, null);
    }

    @Override
    public LinkKind kind() {
        return LinkKind.ISSUE_SPEC;
    }

    @Override
    public HistoryEntity historyEntity() {
        return HistoryEntity.ISSUE_SPEC;
    }

    @Override
    public String historyId() {
        return issueId.value() + "|" + specId.value();
    }
}
