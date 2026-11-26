// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.domain.model;

import java.util.Objects;
import org.veridger.domain.rule.HistoryEntity;
import org.veridger.domain.rule.LinkKind;
import org.veridger.domain.value.ActorId;
import org.veridger.domain.value.SpecId;
import org.veridger.domain.value.TaskId;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.relational.core.mapping.InsertOnlyProperty;
import org.springframework.data.relational.core.mapping.Table;

/** Link between a specification and a task ({@code spec_task}). */
@Table("spec_task")
public record SpecTaskLink(SpecId specId, TaskId taskId, @CreatedBy @InsertOnlyProperty ActorId createdBy)
        implements Link {

    public SpecTaskLink {
        Objects.requireNonNull(specId);
        Objects.requireNonNull(taskId);
    }

    public SpecTaskLink(SpecId specId, TaskId taskId) {
        this(specId, taskId, null);
    }

    @Override
    public LinkKind kind() {
        return LinkKind.SPEC_TASK;
    }

    @Override
    public HistoryEntity historyEntity() {
        return HistoryEntity.SPEC_TASK;
    }

    @Override
    public String historyId() {
        return specId.value() + "|" + taskId.value();
    }
}
