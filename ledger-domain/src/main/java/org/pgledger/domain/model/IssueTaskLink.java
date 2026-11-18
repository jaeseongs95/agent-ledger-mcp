package org.pgledger.domain.model;

import java.util.Objects;
import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.rule.LinkKind;
import org.pgledger.domain.value.ActorId;
import org.pgledger.domain.value.IssueId;
import org.pgledger.domain.value.TaskId;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.relational.core.mapping.InsertOnlyProperty;
import org.springframework.data.relational.core.mapping.Table;

/** Link between an issue and a task ({@code issue_task}). */
@Table("issue_task")
public record IssueTaskLink(IssueId issueId, TaskId taskId, @CreatedBy @InsertOnlyProperty ActorId createdBy)
        implements Link {

    public IssueTaskLink {
        Objects.requireNonNull(issueId);
        Objects.requireNonNull(taskId);
    }

    public IssueTaskLink(IssueId issueId, TaskId taskId) {
        this(issueId, taskId, null);
    }

    @Override
    public LinkKind kind() {
        return LinkKind.ISSUE_TASK;
    }

    @Override
    public HistoryEntity historyEntity() {
        return HistoryEntity.ISSUE_TASK;
    }

    @Override
    public String historyId() {
        return issueId.value() + "|" + taskId.value();
    }
}
