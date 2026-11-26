// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.application.write;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.agentledger.application.attachment.AttachmentLinks;
import org.agentledger.application.gate.Lookup;
import org.agentledger.application.gate.Target;
import org.agentledger.application.gate.WriteGate;
import org.agentledger.application.history.ChangeSet;
import org.agentledger.application.history.HistoryKey;
import org.agentledger.application.history.HistoryWriter;
import org.agentledger.application.history.Snapshot;
import org.agentledger.application.receipt.ReceiptService;
import org.agentledger.application.write.WriteResult.RecordDeliverableResult;
import org.agentledger.database.repository.Counter;
import org.agentledger.database.repository.DeliverableRepository;
import org.agentledger.database.repository.IdAllocator;
import org.agentledger.domain.error.ConflictCode;
import org.agentledger.domain.error.ConflictException;
import org.agentledger.domain.error.TransitionCode;
import org.agentledger.domain.error.TransitionException;
import org.agentledger.domain.error.ValidationException;
import org.agentledger.domain.error.ValidationReason;
import org.agentledger.domain.model.Deliverable;
import org.agentledger.domain.model.Issue;
import org.agentledger.domain.model.ReceiptOperation;
import org.agentledger.domain.model.Spec;
import org.agentledger.domain.model.Task;
import org.agentledger.domain.rule.CheckResult;
import org.agentledger.domain.rule.DeliverableTarget;
import org.agentledger.domain.rule.HistoryEntity;
import org.agentledger.domain.rule.HistoryOp;
import org.agentledger.domain.rule.IssueStatus;
import org.agentledger.domain.rule.Scope;
import org.agentledger.domain.rule.SpecStatus;
import org.agentledger.domain.rule.TaskState;
import org.agentledger.domain.value.ActorId;
import org.agentledger.domain.value.AuthorId;
import org.agentledger.domain.value.CanonicalPayload;
import org.agentledger.domain.value.CodeValue;
import org.agentledger.domain.value.Evidence;
import org.agentledger.domain.value.IssueId;
import org.agentledger.domain.value.ProjectId;
import org.agentledger.domain.value.SpecId;
import org.agentledger.domain.value.TaskId;
import org.agentledger.domain.value.TextRule;
import org.agentledger.domain.value.Tokens;
import org.agentledger.domain.value.UtcInstant;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * Records a deliverable produced for an issue, specification, or task (or amends external check facts on an already
 * recorded deliverable).
 *
 * <p>The target entity row is read and conditionally saved ({@code @Version}) before any counter is allocated so
 * concurrent target transitions (especially verification) decide the race cleanly via {@code VERSION_CONFLICT}.
 * Recording on a {@code VERIFIED} entity is refused ({@code VERIFIED_ENTITY}).
 *
 * <p>The deliverable itself is read only after the target entity is pinned (spec, task) or read (issue): every
 * write to a deliverable of a target is a write to that target too, so a deliverable read at that point is the row the
 * write goes on from, and what a concurrent write changed in between is either seen (the pin waited for it) or ends
 * in {@code VERSION_CONFLICT} when the target is saved. Read before, it would be overwritten with the values it had
 * before the other write ({@code deliverable} has no version column).
 */
@Component
public class RecordDeliverableOperation extends WriteOperation<RecordDeliverableCommand, RecordDeliverableResult> {

    private final WriteGate gate;
    private final Rows rows;
    private final CodeRules codeRules;
    private final DeliverableRepository deliverables;
    private final IdAllocator ids;
    private final HistoryWriter history;
    private final AttachmentLinks attachments;
    private final Clock clock;

    public RecordDeliverableOperation(WriteGate gate, ReceiptService receipts, Rows rows,
            CodeRules codeRules, DeliverableRepository deliverables, IdAllocator ids, HistoryWriter history,
            AttachmentLinks attachments, Clock clock) {
        super(gate, receipts, ReceiptOperation.RECORD_DELIVERABLE, RecordDeliverableResult.class);
        this.gate = gate;
        this.rows = rows;
        this.codeRules = codeRules;
        this.deliverables = deliverables;
        this.ids = ids;
        this.history = history;
        this.attachments = attachments;
        this.clock = clock;
    }

    @Override
    protected Lookup resolve(RecordDeliverableCommand c) {
        return Lookup.afterActor(() -> {
            DeliverableTarget target = Tokens.parse("target_entity", c.targetEntity(), DeliverableTarget.values());
            ProjectId project = switch (target) {
                case ISSUE -> rows.issue(IssueId.of(c.targetId())).projectId();
                case SPEC -> rows.spec(SpecId.of(c.targetId())).projectId();
                case TASK -> rows.task(TaskId.of(c.targetId())).projectId();
            };
            return List.of(new Target(project, Scope.DELIVERABLE));
        });
    }

    @Override
    protected Prepared<RecordDeliverableResult> prepare(RecordDeliverableCommand c) {
        DeliverableTarget target = Tokens.parse("target_entity", c.targetEntity(), DeliverableTarget.values());
        String targetId = switch (target) {
            case ISSUE -> IssueId.of(c.targetId()).value();
            case SPEC -> SpecId.of(c.targetId()).value();
            case TASK -> TaskId.of(c.targetId()).value();
        };
        CodeValue kind = CodeValue.of(TextRule.CODE_NAME.required(c.kind()));
        String deliverableRef = TextRule.DELIVERABLE_REF.required(c.deliverableRef());
        String location = TextRule.LOCATION.optional(c.location());
        String summary = TextRule.DELIVERABLE_SUMMARY.required(c.summary());
        String checkRef = TextRule.CHECK_REF.optional(c.checkRef());
        CheckResult checkResult = c.checkResult() == null || c.checkResult().isBlank() ? null
                : Tokens.parse("check_result", c.checkResult(), CheckResult.values());
        Instant checkedAt = c.checkedAt() == null || c.checkedAt().isBlank() ? null
                : UtcInstant.parse("checked_at", c.checkedAt()).value();
        if (checkResult != null && checkRef == null) {
            throw ValidationException.invalid("check_ref", ValidationReason.REQUIRED_WHEN_SET, "check_result");
        }
        boolean mark = c.markDone() == null || c.markDone();
        CanonicalPayload.Fields payload = CanonicalPayload.Fields.empty()
                .text("target_entity", target.token())
                .text("target_id", targetId)
                .text("kind", kind.value())
                .text("deliverable_ref", deliverableRef)
                .text("location", location)
                .text("summary", summary)
                .text("check_ref", checkRef)
                .text("check_result", checkResult == null ? null : checkResult.name())
                .text("checked_at", ChangeSet.text(checkedAt))
                .text("mark", Boolean.toString(mark))
                .text("attachment_id", c.attachmentId());
        return new Prepared<>(payload, ctx -> execute(ctx, target, targetId, kind, deliverableRef, location, summary,
                checkRef, checkResult, checkedAt, mark, c.attachmentId()));
    }

    private RecordDeliverableResult execute(WriteContext ctx, DeliverableTarget target, String targetId,
            CodeValue kind, String deliverableRef, String location, String summary, String checkRef,
            CheckResult checkResult, Instant checkedAt, boolean mark, String attachment) {
        attachments.requireOwnedBy(attachment, ctx.author());
        Instant now = clock.instant();
        AuthorId author = ctx.author();
        Evidence evidence = Evidence.of(checkRef, null);
        Supplier<Known> afterTheTarget = () -> readKnown(target, targetId, kind, deliverableRef, location, checkRef,
                checkResult, checkedAt);

        TargetUpdate targetUpdate = switch (target) {
            case ISSUE -> updateIssue(ctx, IssueId.of(targetId), afterTheTarget, author, mark, now);
            case SPEC -> updateSpec(ctx, SpecId.of(targetId), afterTheTarget, author, mark, now);
            case TASK -> updateTask(ctx, TaskId.of(targetId), afterTheTarget, author, mark, now);
        };

        String delivHistoryId = HistoryKey.deliverableEntityId(target.token(), targetId, kind.value(), deliverableRef);
        DeliverableWrite write = switch (targetUpdate.known()) {
            case Known.Recorded recorded -> amendDeliverable(ctx, recorded.row(), delivHistoryId, checkRef,
                    checkResult, checkedAt, evidence, recorded.amended(), attachment);
            case Known.New unrecorded -> insertDeliverable(ctx, target, targetId, delivHistoryId, kind,
                    deliverableRef, location, summary, checkRef, checkResult, checkedAt, evidence, now, attachment);
        };

        Long transitionHistoryId = null;
        if (targetUpdate.op() == HistoryOp.TRANSITION) {
            ChangeSet changes = ChangeSet.empty().with("deliverable", null, deliverableRef)
                    .withIfChanged("owner_actor_id", targetUpdate.oldOwner(), targetUpdate.newOwner());
            transitionHistoryId = ctx.appendHistory(history, HistoryWriter.Entry.of(target.historyEntity(), targetId,
                    HistoryOp.TRANSITION, changes)
                    .withStates(targetUpdate.fromState(), targetUpdate.toState())
                    .withEvidence(evidence)
                    .withBefore(targetUpdate.before()));
        } else if (targetUpdate.op() == HistoryOp.EDIT) {
            transitionHistoryId = ctx.appendHistory(history, HistoryWriter.Entry.of(target.historyEntity(), targetId,
                    HistoryOp.EDIT, ChangeSet.empty().with("owner_actor_id", targetUpdate.oldOwner(),
                            targetUpdate.newOwner()))
                    .withEvidence(evidence)
                    .withBefore(targetUpdate.before()));
        }

        return new RecordDeliverableResult(WriteStatus.COMMITTED, target.token(), targetId, write.deliverableId(),
                targetUpdate.toState(), targetUpdate.newOwner(), targetUpdate.version(), write.historyId(),
                transitionHistoryId, author.value(), ctx.login());
    }

    /** The deliverable of the request as it is when the target is held, and what an amendment would change in it. */
    private sealed interface Known {

        /** No such deliverable yet: the request records it. */
        record New() implements Known {
        }

        /** The deliverable is recorded: the request amends its check facts, with these changes. */
        record Recorded(Deliverable row, ChangeSet amended) implements Known {
        }

        default boolean exists() {
            return this instanceof Recorded;
        }
    }

    /**
     * Reads the deliverable of the request. Called by the update of the target once it has the row of the target and
     * not before (see the class comment); refuses a kind that is not a code when the deliverable is new, and an
     * amendment that changes nothing.
     */
    private Known readKnown(DeliverableTarget target, String targetId, CodeValue kind, String deliverableRef,
            String location, String checkRef, CheckResult checkResult, Instant checkedAt) {
        return deliverables.findByTargetEntityAndTargetIdAndKindAndDeliverableRefAndLocationKey(target, targetId, kind,
                deliverableRef, Deliverable.locationKeyOf(location))
                .or(() -> location == null ? soleDeliverable(target, targetId, kind, deliverableRef) : Optional.empty())
                .<Known>map(row -> new Known.Recorded(row, amendedChanges(row, checkRef, checkResult, checkedAt)))
                .orElseGet(() -> {
                    codeRules.checkDeliverableKind(kind);
                    return new Known.New();
                });
    }

    private Optional<Deliverable> soleDeliverable(DeliverableTarget target, String targetId, CodeValue kind,
            String deliverableRef) {
        List<Deliverable> byRef = deliverables.findByTargetEntityAndTargetIdAndKindAndDeliverableRef(target, targetId,
                kind, deliverableRef);
        return byRef.size() == 1 ? Optional.of(byRef.getFirst()) : Optional.empty();
    }

    private record TargetUpdate(HistoryOp op, String fromState, String toState, String oldOwner, String newOwner,
            long version, Snapshot before, Known known) {
    }

    private TargetUpdate updateIssue(WriteContext ctx, IssueId id, Supplier<Known> afterTheTarget, AuthorId author,
            boolean mark, Instant now) {
        ctx.requirePinnedIssue(id);
        Issue row = rows.issueForWrite(id, null);
        Known known = afterTheTarget.get();
        row.requireNotVerified();
        ActorId nextOwner = known.exists() ? row.ownerActorId() : author;
        String oldOwner = row.ownerActorId() == null ? null : row.ownerActorId().value();
        String newOwner = nextOwner == null ? null : nextOwner.value();
        Snapshot before = Snapshot.IssueRow.of(row);
        if (row.status() == IssueStatus.OPEN && mark) {
            Issue after = row.transitioned(IssueStatus.RESOLVED, null, now).ownedBy(nextOwner, now);
            long version = rows.saveIssue(after).version();
            ctx.authorize(gate, List.of(new Target(row.projectId(), Scope.DELIVERABLE)));
            return new TargetUpdate(HistoryOp.TRANSITION, IssueStatus.OPEN.name(), IssueStatus.RESOLVED.name(),
                    oldOwner, newOwner, version, before, known);
        }
        Issue after = row.ownedBy(nextOwner, now);
        long version = rows.saveIssue(after).version();
        ctx.authorize(gate, List.of(new Target(row.projectId(), Scope.DELIVERABLE)));
        HistoryOp op = Objects.equals(oldOwner, newOwner) ? null : HistoryOp.EDIT;
        return new TargetUpdate(op, row.status().name(), row.status().name(), oldOwner, newOwner, version, before,
                known);
    }

    private TargetUpdate updateSpec(WriteContext ctx, SpecId id, Supplier<Known> afterTheTarget, AuthorId author,
            boolean mark, Instant now) {
        ProjectId pinnedProject = ctx.pinSpec(rows, id);
        ctx.authorize(gate, List.of(new Target(pinnedProject, Scope.DELIVERABLE)));
        Spec row = rows.specForWrite(id, null);
        Known known = afterTheTarget.get();
        row.requireNotVerified();
        ActorId nextOwner = known.exists() ? row.ownerActorId() : author;
        String oldOwner = row.ownerActorId() == null ? null : row.ownerActorId().value();
        String newOwner = nextOwner == null ? null : nextOwner.value();
        Snapshot before = Snapshot.SpecRow.of(row);
        boolean canComplete = row.status() == SpecStatus.DRAFT || row.status() == SpecStatus.PLANNED
                || row.status() == SpecStatus.ACTIVE || row.status() == SpecStatus.BLOCKED;
        if (canComplete && mark) {
            Spec after = row.transitioned(SpecStatus.COMPLETED, now).ownedBy(nextOwner, now);
            long version = rows.saveSpec(after).version();
            return new TargetUpdate(HistoryOp.TRANSITION, row.status().name(), SpecStatus.COMPLETED.name(), oldOwner,
                    newOwner, version, before, known);
        }
        Spec after = row.ownedBy(nextOwner, now);
        long version = rows.saveSpec(after).version();
        HistoryOp op = Objects.equals(oldOwner, newOwner) ? null : HistoryOp.EDIT;
        return new TargetUpdate(op, row.status().name(), row.status().name(), oldOwner, newOwner, version, before,
                known);
    }

    private TargetUpdate updateTask(WriteContext ctx, TaskId id, Supplier<Known> afterTheTarget, AuthorId author,
            boolean mark, Instant now) {
        ProjectId pinnedProject = ctx.pinTask(rows, id);
        ctx.authorize(gate, List.of(new Target(pinnedProject, Scope.DELIVERABLE)));
        Task row = rows.taskForWrite(id, null);
        Known known = afterTheTarget.get();
        row.requireNotVerified();
        ActorId nextOwner = known.exists() ? row.ownerActorId() : author;
        String oldOwner = row.ownerActorId() == null ? null : row.ownerActorId().value();
        String newOwner = nextOwner == null ? null : nextOwner.value();
        Snapshot before = Snapshot.TaskRow.of(row);
        boolean canComplete = row.state() == TaskState.PLANNED || row.state() == TaskState.ACTIVE
                || row.state() == TaskState.BLOCKED;
        TaskState nextState = canComplete && mark ? TaskState.COMPLETED : row.state();
        Task after = new Task(row.id(), row.projectId(), row.title(), row.purpose(), row.successCondition(), nextState,
                nextOwner, row.originRef(), row.originQuote(), row.version(), row.createdAt(), now, row.createdBy(),
                row.idKey());
        long version = rows.saveTask(after).version();
        if (nextState != row.state()) {
            return new TargetUpdate(HistoryOp.TRANSITION, row.state().name(), nextState.name(), oldOwner, newOwner,
                    version, before, known);
        }
        HistoryOp op = Objects.equals(oldOwner, newOwner) ? null : HistoryOp.EDIT;
        return new TargetUpdate(op, row.state().name(), row.state().name(), oldOwner, newOwner, version, before,
                known);
    }

    private record DeliverableWrite(long deliverableId, long historyId) {
    }

    private DeliverableWrite insertDeliverable(WriteContext ctx, DeliverableTarget target, String targetId,
            String delivHistoryId, CodeValue kind, String deliverableRef, String location, String summary,
            String checkRef, CheckResult checkResult, Instant checkedAt, Evidence evidence, Instant now,
            String attachment) {
        Deliverable row = Deliverable.record(ids.next(Counter.DELIVERABLE), target, targetId, kind, deliverableRef,
                location, summary, checkRef, checkResult, checkedAt, attachment, ctx.author(), now);
        long deliverableId = inserted(() -> deliverables.insert(row), target.token() + " " + targetId).id();
        ChangeSet changes = ChangeSet.empty()
                .with("target_entity", null, target.token())
                .with("target_id", null, targetId)
                .with("kind", null, kind.value())
                .with("deliverable_ref", null, deliverableRef)
                .with("location", null, location)
                .with("summary", null, summary)
                .with("check_ref", null, checkRef)
                .with("check_result", null, name(checkResult));
        long historyId = ctx.appendHistory(history, HistoryWriter.Entry.of(HistoryEntity.DELIVERABLE, delivHistoryId,
                HistoryOp.CREATE, changes).withEvidence(evidence).withAttachment(attachment));
        return new DeliverableWrite(deliverableId, historyId);
    }

    private static ChangeSet amendedChanges(Deliverable known, String checkRef, CheckResult checkResult,
            Instant checkedAt) {
        if (checkRef == null) {
            throw new TransitionException(TransitionCode.DELIVERABLE_ALREADY_RECORDED, List.of());
        }
        ChangeSet changes = ChangeSet.empty()
                .withIfChanged("check_ref", known.checkRef(), checkRef)
                .withIfChanged("check_result", ChangeSet.text(name(known.checkResult())), name(checkResult))
                .withIfChanged("checked_at", ChangeSet.text(known.checkedAt()), ChangeSet.text(checkedAt));
        if (changes.isEmpty()) {
            throw new TransitionException(TransitionCode.NOTHING_CHANGES, List.of());
        }
        return changes;
    }

    private DeliverableWrite amendDeliverable(WriteContext ctx, Deliverable known, String delivHistoryId,
            String checkRef, CheckResult checkResult, Instant checkedAt, Evidence evidence, ChangeSet changes,
            String attachment) {
        deliverables.update(known.withCheck(checkRef, checkResult, checkedAt));
        long historyId = ctx.appendHistory(history, HistoryWriter.Entry.of(HistoryEntity.DELIVERABLE, delivHistoryId,
                HistoryOp.EDIT, changes).withEvidence(evidence).withBefore(Snapshot.DeliverableRow.of(known))
                .withAttachment(attachment));
        return new DeliverableWrite(known.id(), historyId);
    }

    private static String name(CheckResult checkResult) {
        return checkResult == null ? null : checkResult.name();
    }

    private static <T> T inserted(Supplier<T> insert, String targetLabel) {
        try {
            return insert.get();
        } catch (DuplicateKeyException e) {
            throw new ConflictException(ConflictCode.DUPLICATE_KEY, List.of("deliverable of " + targetLabel), e);
        }
    }
}
