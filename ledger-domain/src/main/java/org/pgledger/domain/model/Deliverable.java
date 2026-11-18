package org.pgledger.domain.model;

import java.time.Instant;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationReason;
import org.pgledger.domain.rule.CheckResult;
import org.pgledger.domain.rule.DeliverableOrigin;
import org.pgledger.domain.rule.DeliverableTarget;
import org.pgledger.domain.value.ActorId;
import org.pgledger.domain.value.CodeValue;
import org.pgledger.domain.value.TextRule;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * A universal work deliverable attached to an {@code ISSUE}, {@code SPEC}, or {@code TASK}, with optional
 * external inspection/check metadata and attachment bundle link.
 */
@Table("deliverable")
public record Deliverable(@Id Long id, DeliverableTarget targetEntity, String targetId, CodeValue kind,
        String deliverableRef, String location, String summary, String checkRef, CheckResult checkResult,
        Instant checkedAt, String attachmentId, ActorId recordedBy, Instant recordedAt, DeliverableOrigin origin) {

    public Deliverable {
        location = TextRule.emptyToNull(location);
        checkRef = TextRule.emptyToNull(checkRef);
        attachmentId = TextRule.emptyToNull(attachmentId);
        if (checkResult != null && checkRef == null) {
            throw ValidationException.invalid("check_ref", ValidationReason.REQUIRED_WHEN_SET, "check_result");
        }
    }

    /** Records a new native deliverable on an issue, spec, or task. */
    public static Deliverable record(long id, DeliverableTarget targetEntity, String targetId, CodeValue kind,
            String deliverableRef, String location, String summary, String checkRef, CheckResult checkResult,
            Instant checkedAt, String attachmentId, ActorId by, Instant now) {
        return new Deliverable(id, targetEntity, targetId, kind, TextRule.DELIVERABLE_REF.required(deliverableRef),
                TextRule.LOCATION.optional(location), TextRule.DELIVERABLE_SUMMARY.required(summary),
                TextRule.CHECK_REF.optional(checkRef), checkResult, checkedAt,
                attachmentId == null || attachmentId.isBlank() ? null : attachmentId.strip(),
                by, now, DeliverableOrigin.NATIVE);
    }

    /** Returns an updated deliverable carrying new verification/check metadata. */
    public Deliverable withCheck(String newCheckRef, CheckResult result, Instant checkedAt) {
        return new Deliverable(id, targetEntity, targetId, kind, deliverableRef, location, summary,
                TextRule.CHECK_REF.optional(newCheckRef), result, checkedAt, attachmentId, recordedBy, recordedAt,
                origin);
    }
}
