// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application.history;

import java.time.Instant;
import org.veridger.domain.model.Criterion;
import org.veridger.domain.model.CriterionLink;
import org.veridger.domain.model.Deliverable;
import org.veridger.domain.model.Issue;
import org.veridger.domain.model.IssueRelation;
import org.veridger.domain.model.IssueSpecLink;
import org.veridger.domain.model.IssueTaskLink;
import org.veridger.domain.model.Spec;
import org.veridger.domain.model.SpecTaskLink;
import org.veridger.domain.model.Task;

/**
 * The full row as it was just before a change: what the {@code before_data} column of a history row holds.
 * Component names match column names (written in snake_case JSON); instants are UTC.
 */
public sealed interface Snapshot {

    record IssueRow(String id, String projectId, String title, String category, String severity, String status,
            String mergedInto, String symptom, String rootCause, String ownerActorId, long caseSeq, long version,
            Instant createdAt, Instant updatedAt, String createdBy) implements Snapshot {

        public static IssueRow of(Issue i) {
            return new IssueRow(i.id().value(), i.projectId().value(), i.title(), i.category().value(),
                    i.severity().value(), i.status().name(), i.mergedInto() == null ? null : i.mergedInto().value(),
                    i.symptom(), i.rootCause(), i.ownerActorId() == null ? null : i.ownerActorId().value(),
                    i.caseSeq(), i.version(), i.createdAt(), i.updatedAt(), i.createdBy().value());
        }
    }

    record SpecRow(String id, String projectId, String parentId, String title, String summary,
            String acceptanceCriteria, String priority, String status, String ownerActorId, String originRef,
            String originQuote, long version, Instant createdAt, Instant updatedAt, String createdBy)
            implements Snapshot {

        public static SpecRow of(Spec s) {
            return new SpecRow(s.id().value(), s.projectId().value(),
                    s.parentId() == null ? null : s.parentId().value(), s.title(), s.summary(),
                    s.acceptanceCriteria(), s.priority(), s.status().name(),
                    s.ownerActorId() == null ? null : s.ownerActorId().value(), s.originRef(), s.originQuote(),
                    s.version(), s.createdAt(), s.updatedAt(), s.createdBy().value());
        }
    }

    record TaskRow(String id, String projectId, String title, String purpose, String successCondition, String state,
            String ownerActorId, String originRef, String originQuote, long version, Instant createdAt,
            Instant updatedAt, String createdBy) implements Snapshot {

        public static TaskRow of(Task t) {
            return new TaskRow(t.id().value(), t.projectId().value(), t.title(), t.purpose(), t.successCondition(),
                    t.state().name(), t.ownerActorId() == null ? null : t.ownerActorId().value(), t.originRef(),
                    t.originQuote(), t.version(), t.createdAt(), t.updatedAt(), t.createdBy().value());
        }
    }

    record CriterionRow(String id, String projectId, String milestoneName, String goalLabel, String code,
            String criterionText, String state, long version, String createdBy) implements Snapshot {

        public static CriterionRow of(Criterion c) {
            return new CriterionRow(c.id().value(), c.projectId().value(), c.milestone(), c.goalLabel(), c.code(),
                    c.criterionText(), c.state().name(), c.version(), c.createdBy().value());
        }
    }

    record DeliverableRow(long id, String targetEntity, String targetId, String kind, String deliverableRef,
            String location, String summary, String checkRef, String checkResult, Instant checkedAt,
            String attachmentId, String recordedBy, Instant recordedAt, String origin) implements Snapshot {

        public static DeliverableRow of(Deliverable d) {
            return new DeliverableRow(d.id(), d.targetEntity().name(), d.targetId(), d.kind().value(),
                    d.deliverableRef(), d.location(), d.summary(), d.checkRef(),
                    d.checkResult() == null ? null : d.checkResult().name(), d.checkedAt(), d.attachmentId(),
                    d.recordedBy().value(), d.recordedAt(), d.origin().name());
        }
    }

    record IssueTaskRow(String issueId, String taskId, String createdBy) implements Snapshot {

        public static IssueTaskRow of(IssueTaskLink l) {
            return new IssueTaskRow(l.issueId().value(), l.taskId().value(), l.createdBy().value());
        }
    }

    record IssueSpecRow(String issueId, String specId, String createdBy) implements Snapshot {

        public static IssueSpecRow of(IssueSpecLink l) {
            return new IssueSpecRow(l.issueId().value(), l.specId().value(), l.createdBy().value());
        }
    }

    record SpecTaskRow(String specId, String taskId, String createdBy) implements Snapshot {

        public static SpecTaskRow of(SpecTaskLink l) {
            return new SpecTaskRow(l.specId().value(), l.taskId().value(), l.createdBy().value());
        }
    }

    record IssueRelationRow(String a, String b, String kind, String createdBy) implements Snapshot {

        public static IssueRelationRow of(IssueRelation r) {
            return new IssueRelationRow(r.a().value(), r.b().value(), r.relation().value(), r.createdBy().value());
        }
    }

    record CriterionLinkRow(String linkKey, String criterionId, String taskId, String issueId, String specId,
            String createdBy) implements Snapshot {

        public static CriterionLinkRow of(CriterionLink l) {
            return new CriterionLinkRow(l.linkKey(), l.criterionId().value(),
                    l.taskId() == null ? null : l.taskId().value(),
                    l.issueId() == null ? null : l.issueId().value(),
                    l.specId() == null ? null : l.specId().value(), l.createdBy().value());
        }
    }
}
