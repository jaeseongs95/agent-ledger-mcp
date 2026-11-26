// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.veridger.domain.error.TransitionCode;
import org.veridger.domain.error.TransitionException;
import org.veridger.domain.error.ValidationException;
import org.veridger.domain.error.ValidationReason;
import org.veridger.domain.rule.IssueStatus;
import org.veridger.domain.value.ActorId;
import org.veridger.domain.value.CodeValue;
import org.veridger.domain.value.IssueId;
import org.veridger.domain.value.ProjectId;
import org.veridger.domain.value.TextRule;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.ReadOnlyProperty;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.InsertOnlyProperty;
import org.springframework.data.relational.core.mapping.Table;

/**
 * An issue or defect tracked in any domain. {@code caseSeq} increments atomically without bumping {@code version}.
 */
@Table("issue")
public record Issue(@Id IssueId id, ProjectId projectId, String title, CodeValue category, CodeValue severity,
        IssueStatus status, IssueId mergedInto, String symptom, String rootCause, ActorId ownerActorId,
        @ReadOnlyProperty long caseSeq, @Version long version, Instant createdAt, Instant updatedAt,
        @CreatedBy @InsertOnlyProperty ActorId createdBy) {

    public Issue {
        symptom = TextRule.emptyToNull(symptom);
        rootCause = TextRule.emptyToNull(rootCause);
        if ((status == IssueStatus.MERGED) != (mergedInto != null)) {
            throw ValidationException.invalid("merged_into", ValidationReason.SET_EXACTLY_WHEN_STATUS_IS, "MERGED");
        }
        if (Objects.equals(id, mergedInto)) {
            throw ValidationException.invalid("merged_into", ValidationReason.MUST_NOT_BE_THE_ISSUE_ITSELF);
        }
    }

    /** Verified issues cannot be edited by save, record_deliverable, or record_case until reopened by a reviewer. */
    public void requireNotVerified() {
        if (status == IssueStatus.VERIFIED) {
            throw new TransitionException(TransitionCode.VERIFIED_ENTITY, List.of(id.value()));
        }
    }

    /** A new OPEN issue from complete fields. */
    public static Issue open(IssueId id, IssueFields fields, Instant now) {
        IssueFields f = fields.requireCreatable();
        return new Issue(id, f.projectId().orNull(), f.title().orNull(), f.category().orNull(),
                f.severity().orNull(), IssueStatus.OPEN, null, f.symptom().orNull(),
                f.rootCause().orNull(), f.ownerActorId().orNull(), 0, 0, now, now, null);
    }

    public Issue edited(IssueFields f, Instant now) {
        return new Issue(id, projectId, f.title().orElse(title), f.category().orElse(category),
                f.severity().orElse(severity), status, mergedInto, f.symptom().orElse(symptom),
                f.rootCause().orElse(rootCause), f.ownerActorId().<ActorId>map(a -> a).orElse(ownerActorId),
                caseSeq, version, createdAt, now, createdBy);
    }

    /** The issue after a state change; {@code mergedInto} must be set exactly for MERGED. */
    public Issue transitioned(IssueStatus to, IssueId newMergedInto, Instant now) {
        return new Issue(id, projectId, title, category, severity, to, newMergedInto, symptom, rootCause,
                ownerActorId, caseSeq, version, createdAt, now, createdBy);
    }

    public Issue ownedBy(ActorId owner, Instant now) {
        return new Issue(id, projectId, title, category, severity, status, mergedInto, symptom, rootCause, owner,
                caseSeq, version, createdAt, now, createdBy);
    }
}
