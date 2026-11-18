// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.domain.model;

import java.time.Instant;
import java.util.List;
import org.pgledger.domain.error.TransitionCode;
import org.pgledger.domain.error.TransitionException;
import org.pgledger.domain.rule.TaskState;
import org.pgledger.domain.value.ActorId;
import org.pgledger.domain.value.ProjectId;
import org.pgledger.domain.value.TaskId;
import org.pgledger.domain.value.TextRule;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.InsertOnlyProperty;
import org.springframework.data.relational.core.mapping.Table;

/**
 * A work task in any domain. {@code idKey} is the upper-case unique key preserving exact case in {@code id}.
 */
@Table("task")
public record Task(@Id TaskId id, ProjectId projectId, String title, String purpose, String successCondition,
        TaskState state, ActorId ownerActorId, String originRef, String originQuote, @Version long version,
        Instant createdAt, Instant updatedAt, @CreatedBy @InsertOnlyProperty ActorId createdBy,
        @InsertOnlyProperty String idKey) {

    public Task {
        purpose = TextRule.emptyToNull(purpose);
        successCondition = TextRule.emptyToNull(successCondition);
        originRef = TextRule.emptyToNull(originRef);
        originQuote = TextRule.emptyToNull(originQuote);
        idKey = id == null ? null : id.key();
    }

    /** Verified tasks cannot be edited or receive deliverables until reopened by a reviewer. */
    public void requireNotVerified() {
        if (state == TaskState.VERIFIED) {
            throw new TransitionException(TransitionCode.VERIFIED_ENTITY, List.of(id.value()));
        }
    }

    /** A new PLANNED task from complete fields. */
    public static Task plan(TaskId id, TaskFields fields, Instant now) {
        TaskFields f = fields.requireCreatable();
        return new Task(id, f.projectId().orNull(), f.title().orNull(), f.purpose().orNull(),
                f.successCondition().orNull(), TaskState.PLANNED, f.ownerActorId().orNull(),
                f.originRef().orNull(), f.originQuote().orNull(), 0, now, now, null, null);
    }

    public Task edited(TaskFields f, Instant now) {
        return new Task(id, f.projectId().orElse(projectId), f.title().orElse(title), f.purpose().orElse(purpose),
                f.successCondition().orElse(successCondition), state, f.ownerActorId().<ActorId>map(a -> a)
                        .orElse(ownerActorId), f.originRef().orElse(originRef), f.originQuote().orElse(originQuote),
                version, createdAt, now, createdBy, idKey);
    }

    public Task transitioned(TaskState to, Instant now) {
        return new Task(id, projectId, title, purpose, successCondition, to, ownerActorId, originRef, originQuote,
                version, createdAt, now, createdBy, idKey);
    }
}
