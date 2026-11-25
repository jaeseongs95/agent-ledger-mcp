// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.pgledger.domain.error.TransitionCode;
import org.pgledger.domain.error.TransitionException;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationReason;
import org.pgledger.domain.rule.SpecStatus;
import org.pgledger.domain.value.ActorId;
import org.pgledger.domain.value.ProjectId;
import org.pgledger.domain.value.SpecId;
import org.pgledger.domain.value.TextRule;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.InsertOnlyProperty;
import org.springframework.data.relational.core.mapping.Table;

/**
 * A domain-neutral specification ({@code spec}): tracks planned scope, completion status, and independent
 * verification across any work domain (software, media production, documentation, research, operations).
 */
@Table("spec")
public record Spec(@Id SpecId id, ProjectId projectId, SpecId parentId, String title, String summary,
        String acceptanceCriteria, SpecStatus status, String priority, ActorId ownerActorId, String originRef,
        String originQuote, @Version long version, Instant createdAt, Instant updatedAt,
        @CreatedBy @InsertOnlyProperty ActorId createdBy, @InsertOnlyProperty String idKey) {

    public Spec {
        summary = TextRule.emptyToNull(summary);
        acceptanceCriteria = TextRule.emptyToNull(acceptanceCriteria);
        priority = TextRule.emptyToNull(priority);
        originRef = TextRule.emptyToNull(originRef);
        originQuote = TextRule.emptyToNull(originQuote);
        idKey = id == null ? null : id.key();
        if (Objects.equals(id, parentId)) {
            throw ValidationException.invalid("parent_id", ValidationReason.MUST_NOT_BE_THE_SPEC_ITSELF);
        }
    }

    /** Verified specs cannot be edited by save or updated with deliverables until a reviewer reopens them. */
    public void requireNotVerified() {
        if (status == SpecStatus.VERIFIED) {
            throw new TransitionException(TransitionCode.VERIFIED_ENTITY, List.of(id.value()));
        }
    }

    /** A new PLANNED specification created from complete fields. */
    public static Spec create(SpecId id, SpecFields fields, Instant now) {
        SpecFields f = fields.requireCreatable();
        return new Spec(id, f.projectId().orNull(), f.parentId().orNull(), f.title().orNull(),
                f.summary().orNull(), f.acceptanceCriteria().orNull(), SpecStatus.PLANNED,
                f.priority().orNull(), f.ownerActorId().orNull(), f.originRef().orNull(),
                f.originQuote().orNull(), 0, now, now, null, null);
    }

    public Spec edited(SpecFields f, Instant now) {
        return new Spec(id, f.projectId().orElse(projectId), f.parentId().orElse(parentId),
                f.title().orElse(title), f.summary().orElse(summary),
                f.acceptanceCriteria().orElse(acceptanceCriteria), status, f.priority().orElse(priority),
                f.ownerActorId().<ActorId>map(a -> a).orElse(ownerActorId),
                f.originRef().orElse(originRef), f.originQuote().orElse(originQuote),
                version, createdAt, now, createdBy, idKey);
    }

    public Spec transitioned(SpecStatus to, Instant now) {
        return new Spec(id, projectId, parentId, title, summary, acceptanceCriteria, to, priority, ownerActorId,
                originRef, originQuote, version, createdAt, now, createdBy, idKey);
    }

    public Spec ownedBy(ActorId owner, Instant now) {
        return new Spec(id, projectId, parentId, title, summary, acceptanceCriteria, status, priority, owner,
                originRef, originQuote, version, createdAt, now, createdBy, idKey);
    }
}
