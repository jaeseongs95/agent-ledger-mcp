// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.domain.model;

import java.util.Objects;
import org.veridger.domain.error.ValidationException;
import org.veridger.domain.error.ValidationReason;
import org.veridger.domain.rule.HistoryEntity;
import org.veridger.domain.rule.LinkKind;
import org.veridger.domain.value.ActorId;
import org.veridger.domain.value.CriterionId;
import org.veridger.domain.value.EntityId;
import org.veridger.domain.value.IssueId;
import org.veridger.domain.value.SpecId;
import org.veridger.domain.value.TaskId;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.InsertOnlyProperty;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Link between a milestone criterion and exactly one target entity: a task, an issue, or a spec
 * ({@code criterion_link}).
 */
@Table("criterion_link")
public record CriterionLink(@Id String linkKey, CriterionId criterionId, TaskId taskId, IssueId issueId,
        SpecId specId, @CreatedBy @InsertOnlyProperty ActorId createdBy) implements Link {

    public CriterionLink {
        Objects.requireNonNull(criterionId);
        int set = (taskId != null ? 1 : 0) + (issueId != null ? 1 : 0) + (specId != null ? 1 : 0);
        if (set != 1) {
            throw ValidationException.invalid("target", ValidationReason.MUST_BE_EXACTLY_ONE_OF, "task_id",
                    "issue_id / spec_id");
        }
    }

    public static CriterionLink toTask(CriterionId criterionId, TaskId taskId) {
        return toTask(criterionId, taskId, null);
    }

    public static CriterionLink toTask(CriterionId criterionId, TaskId taskId, ActorId createdBy) {
        Objects.requireNonNull(taskId);
        return new CriterionLink(key("T", criterionId, taskId.value()), criterionId, taskId, null, null, createdBy);
    }

    public static CriterionLink toIssue(CriterionId criterionId, IssueId issueId) {
        return toIssue(criterionId, issueId, null);
    }

    public static CriterionLink toIssue(CriterionId criterionId, IssueId issueId, ActorId createdBy) {
        Objects.requireNonNull(issueId);
        return new CriterionLink(key("I", criterionId, issueId.value()), criterionId, null, issueId, null, createdBy);
    }

    public static CriterionLink toSpec(CriterionId criterionId, SpecId specId) {
        return toSpec(criterionId, specId, null);
    }

    public static CriterionLink toSpec(CriterionId criterionId, SpecId specId, ActorId createdBy) {
        Objects.requireNonNull(specId);
        return new CriterionLink(key("S", criterionId, specId.value()), criterionId, null, null, specId, createdBy);
    }

    private static String key(String marker, CriterionId criterionId, String targetId) {
        String critKey = criterionId.key();
        return marker + ":" + critKey.length() + ":" + critKey + ":" + EntityId.key(targetId);
    }

    @Override
    public LinkKind kind() {
        if (taskId != null) {
            return LinkKind.CRITERION_TASK;
        }
        return issueId != null ? LinkKind.CRITERION_ISSUE : LinkKind.CRITERION_SPEC;
    }

    @Override
    public HistoryEntity historyEntity() {
        return HistoryEntity.CRITERION_LINK;
    }

    @Override
    public String historyId() {
        String target = taskId != null ? taskId.value() : (issueId != null ? issueId.value() : specId.value());
        return criterionId.value() + "|" + target;
    }
}
