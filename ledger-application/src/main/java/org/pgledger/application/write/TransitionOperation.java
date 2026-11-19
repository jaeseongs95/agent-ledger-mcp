// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.application.write;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.pgledger.application.attachment.AttachmentLinks;
import org.pgledger.application.gate.Lookup;
import org.pgledger.application.gate.Resolution;
import org.pgledger.application.gate.Target;
import org.pgledger.application.gate.WriteGate;
import org.pgledger.application.history.ChangeSet;
import org.pgledger.application.history.CriterionHistory;
import org.pgledger.application.history.HistoryKey;
import org.pgledger.application.history.HistoryWriter;
import org.pgledger.application.history.Snapshot;
import org.pgledger.application.history.SpecHistory;
import org.pgledger.application.history.TaskHistory;
import org.pgledger.application.json.LedgerJson;
import org.pgledger.application.receipt.ReceiptService;
import org.pgledger.application.write.WriteResult.TransitionResult;
import org.pgledger.database.repository.DeliverableRepository;
import org.pgledger.database.repository.HistoryRepository;
import org.pgledger.database.repository.IssueCaseRepository;
import org.pgledger.database.repository.MergeChainRepository;
import org.pgledger.domain.error.TrustCode;
import org.pgledger.domain.error.TrustException;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationReason;
import org.pgledger.domain.model.Criterion;
import org.pgledger.domain.model.Deliverable;
import org.pgledger.domain.model.HistoryEntry;
import org.pgledger.domain.model.Issue;
import org.pgledger.domain.model.ReceiptOperation;
import org.pgledger.domain.model.Spec;
import org.pgledger.domain.model.Task;
import org.pgledger.domain.rule.CriterionReports;
import org.pgledger.domain.rule.CriterionState;
import org.pgledger.domain.rule.DeliverableHistory;
import org.pgledger.domain.rule.DeliverableTarget;
import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.rule.HistoryOp;
import org.pgledger.domain.rule.IssueStatus;
import org.pgledger.domain.rule.LifecycleState;
import org.pgledger.domain.rule.MergeTarget;
import org.pgledger.domain.rule.Scope;
import org.pgledger.domain.rule.SpecReports;
import org.pgledger.domain.rule.SpecStatus;
import org.pgledger.domain.rule.TaskReports;
import org.pgledger.domain.rule.TaskState;
import org.pgledger.domain.value.ActorId;
import org.pgledger.domain.value.CanonicalPayload;
import org.pgledger.domain.value.CriterionId;
import org.pgledger.domain.value.EntityId;
import org.pgledger.domain.value.Evidence;
import org.pgledger.domain.value.IssueId;
import org.pgledger.domain.value.ProjectId;
import org.pgledger.domain.value.SessionKey;
import org.pgledger.domain.value.SpecId;
import org.pgledger.domain.value.TaskId;
import org.pgledger.domain.value.Tokens;
import org.springframework.stereotype.Component;

/**
 * Transitions an issue, specification, task, or milestone criterion with one {@code TRANSITION} history row.
 * Entering or leaving verification states ({@code VERIFIED}, {@code MET}, {@code NOT_MET}) requires {@code Scope.VERIFY}
 * and the reviewer channel, plus independent-verifier separation from creators, owners, deliverable authors/amenders,
 * case reporters, and completion reporters.
 */
@Component
public class TransitionOperation extends WriteOperation<TransitionCommand, TransitionResult> {

    private static final HistoryEntity[] STATEFUL = {
        HistoryEntity.ISSUE, HistoryEntity.SPEC, HistoryEntity.TASK, HistoryEntity.CRITERION
    };

    private final WriteGate gate;
    private final Rows rows;
    private final DeliverableRepository deliverables;
    private final IssueCaseRepository cases;
    private final MergeChainRepository mergeChain;
    private final HistoryRepository historyRows;
    private final HistoryWriter history;
    private final LedgerJson json;
    private final AttachmentLinks attachments;
    private final Clock clock;

    public TransitionOperation(WriteGate gate, ReceiptService receipts, Rows rows,
            DeliverableRepository deliverables, IssueCaseRepository cases, MergeChainRepository mergeChain,
            HistoryRepository historyRows, HistoryWriter history, LedgerJson json, AttachmentLinks attachments,
            Clock clock) {
        super(gate, receipts, ReceiptOperation.TRANSITION, TransitionResult.class);
        this.gate = gate;
        this.rows = rows;
        this.deliverables = deliverables;
        this.cases = cases;
        this.mergeChain = mergeChain;
        this.historyRows = historyRows;
        this.history = history;
        this.json = json;
        this.attachments = attachments;
        this.clock = clock;
    }

    @Override
    protected Lookup resolve(TransitionCommand c) {
        return Lookup.verifiable(() -> Tokens.find(c.entity(), STATEFUL).map(entity -> switch (entity) {
            case ISSUE -> IssueId.parse(c.id()).map(id -> rows.findIssue(id)
                    .map(row -> issueTarget(row, c))
                    .orElseGet(() -> Resolution.unresolved(Rows.notFound("issue", id.value()))))
                    .orElseGet(() -> Resolution.unresolved(IssueId.malformed()));
            case SPEC -> SpecId.parse(c.id()).map(id -> rows.findSpec(id)
                    .map(row -> target(entity, row.projectId(), row.status(), c, SpecStatus.values()))
                    .orElseGet(() -> Resolution.unresolved(Rows.notFound("spec", id.value()))))
                    .orElseGet(() -> Resolution.unresolved(SpecId.malformed()));
            case TASK -> TaskId.parse(c.id()).map(id -> rows.findTask(id)
                    .map(row -> target(entity, row.projectId(), row.state(), c, TaskState.values()))
                    .orElseGet(() -> Resolution.unresolved(Rows.notFound("task", id.value()))))
                    .orElseGet(() -> Resolution.unresolved(TaskId.malformed()));
            case CRITERION -> CriterionId.parse(c.id()).map(id -> rows.findCriterion(id)
                    .map(row -> target(entity, row.projectId(), row.state(), c, CriterionState.values()))
                    .orElseGet(() -> Resolution.unresolved(Rows.notFound("criterion", id.value()))))
                    .orElseGet(() -> Resolution.unresolved(CriterionId.malformed()));
            case DELIVERABLE, ISSUE_TASK, ISSUE_SPEC, SPEC_TASK, ISSUE_RELATION, CRITERION_LINK, ISSUE_CASE ->
                throw new IllegalStateException("STATEFUL has no such entity");
        }).orElseGet(() -> Resolution.unresolved(Tokens.unknown("entity", c.entity(), STATEFUL))));
    }

    private Resolution issueTarget(Issue row, TransitionCommand c) {
        return Tokens.find(c.to(), IssueStatus.values()).map(to -> {
            Target own = new Target(row.projectId(), Scope.forTransition(HistoryEntity.ISSUE, row.status(), to));
            if (to != IssueStatus.MERGED) {
                return Resolution.of(own);
            }
            return mergeTargetProject(c).map(project -> Resolution.of(own,
                    new Target(project, HistoryEntity.ISSUE.writeScope()))).orElseGet(() -> Resolution.of(own));
        }).orElseGet(() -> Resolution.unresolved(Tokens.unknown("to", c.to(), IssueStatus.values())));
    }

    private Optional<ProjectId> mergeTargetProject(TransitionCommand c) {
        return Optional.ofNullable(c.mergedInto()).filter(text -> !text.isBlank()).flatMap(IssueId::parse)
                .flatMap(rows::findIssue).map(Issue::projectId);
    }

    private static <S extends Enum<S> & LifecycleState> Resolution target(HistoryEntity entity, ProjectId project,
            S from, TransitionCommand c, S[] states) {
        return Tokens.find(c.to(), states)
                .map(to -> Resolution.of(new Target(project, Scope.forTransition(entity, from, to))))
                .orElseGet(() -> Resolution.unresolved(Tokens.unknown("to", c.to(), states)));
    }

    @Override
    protected Prepared<TransitionResult> prepare(TransitionCommand c) {
        HistoryEntity entity = Tokens.parse("entity", c.entity(), STATEFUL);
        if (c.expectedVersion() == null) {
            throw ValidationException.invalid("expected_version", ValidationReason.REQUIRED);
        }
        EntityId.of(c.id());
        Evidence evidence = Evidence.of(c.evidenceUri(), c.evidenceSha256());
        IssueId mergedInto = c.mergedInto() == null || c.mergedInto().isBlank() ? null
                : IssueId.of(c.mergedInto());
        CanonicalPayload.Fields payload = CanonicalPayload.Fields.empty().text("entity", entity.token())
                .text("id", c.id()).text("v", Long.toString(c.expectedVersion())).text("to", c.to())
                .text("evidence_uri", evidence.uri())
                .text("evidence_sha256", evidence.sha256() == null ? null : evidence.sha256().hex())
                .text("merged_into", mergedInto == null ? null : mergedInto.value())
                .text("attachment_id", c.attachmentId());
        long version = c.expectedVersion();
        String attachment = c.attachmentId();
        return new Prepared<>(payload, ctx -> {
            attachments.requireOwnedBy(attachment, ctx.author());
            return switch (entity) {
                case ISSUE -> issue(ctx, IssueId.of(c.id()), version,
                        Tokens.parse("to", c.to(), IssueStatus.values()), evidence, mergedInto, attachment);
                case SPEC -> spec(ctx, SpecId.of(c.id()), version,
                        Tokens.parse("to", c.to(), SpecStatus.values()), evidence, attachment);
                case TASK -> task(ctx, TaskId.of(c.id()), version,
                        Tokens.parse("to", c.to(), TaskState.values()), evidence, attachment);
                case CRITERION -> criterion(ctx, CriterionId.of(c.id()), version,
                        Tokens.parse("to", c.to(), CriterionState.values()), evidence, attachment);
                case DELIVERABLE, ISSUE_TASK, ISSUE_SPEC, SPEC_TASK, ISSUE_RELATION, CRITERION_LINK, ISSUE_CASE ->
                    throw new IllegalStateException("Tokens.parse refused it");
            };
        });
    }

    private void recheck(WriteContext ctx, HistoryEntity entity, ProjectId project, LifecycleState from,
            LifecycleState to) {
        Scope scope = Scope.forTransition(entity, from, to);
        if (scope == Scope.VERIFY && !ctx.session().channel().mayVerify()) {
            throw new TrustException(TrustCode.VERIFY_NEEDS_REVIEWER_LOGIN, List.of());
        }
        gate.authorize(ctx.author(), new Target(project, scope));
    }

    private TransitionResult issue(WriteContext ctx, IssueId id, long expected, IssueStatus to, Evidence evidence,
            IssueId mergedInto, String attachment) {
        Instant now = clock.instant();
        if (to == IssueStatus.MERGED && mergedInto != null && !Objects.equals(id, mergedInto)) {
            if (id.value().compareTo(mergedInto.value()) <= 0) {
                mergeChain.pinMergeSource(id);
                mergeChain.pinMergeTarget(mergedInto);
            } else {
                mergeChain.pinMergeTarget(mergedInto);
                mergeChain.pinMergeSource(id);
            }
        } else if (to == IssueStatus.VERIFIED) {
            mergeChain.pinVerifyCandidate(id);
        }
        Issue row = rows.issueForWrite(id, expected);
        recheck(ctx, HistoryEntity.ISSUE, row.projectId(), row.status(), to);
        List<SessionKey> contributors = deliverableContributors(DeliverableTarget.ISSUE, id.value());
        DeliverableHistory delivHistory = new DeliverableHistory(contributors, reporters(row, to));
        MergeTarget target = null;
        if (to == IssueStatus.MERGED && mergedInto != null) {
            Issue targetRow = rows.issue(mergedInto);
            target = new MergeTarget(targetRow.id(), targetRow.status());
        }
        row.status().requireTransitionTo(to, evidence, delivHistory, ctx.author().sessionKey(), id, target);
        Issue saved = rows.saveIssue(row.transitioned(to, to == IssueStatus.MERGED ? mergedInto : null, now));
        ChangeSet changes = to == IssueStatus.MERGED
                ? ChangeSet.empty().with("merged_into", null, mergedInto.value()) : ChangeSet.empty();
        long historyId = history.append(ctx, HistoryWriter.Entry.of(HistoryEntity.ISSUE, id.value(),
                HistoryOp.TRANSITION, changes).withStates(row.status().name(), to.name()).withEvidence(evidence)
                .withBefore(Snapshot.IssueRow.of(row)).withAttachment(attachment));
        return result(ctx, HistoryEntity.ISSUE, id.value(), row.status().name(), to.name(), saved.version(),
                historyId);
    }

    /**
     * Collects every session that recorded or amended a deliverable on the target entity (D-0029).
     */
    private List<SessionKey> deliverableContributors(DeliverableTarget target, String targetId) {
        List<Deliverable> rowsOfTarget = deliverables.findByTargetEntityAndTargetIdOrderById(target, targetId);
        List<SessionKey> contributors = new ArrayList<>();
        for (Deliverable d : rowsOfTarget) {
            contributors.add(d.recordedBy().sessionKey());
            String delivHistoryId = HistoryKey.deliverableEntityId(target.token(), targetId, d.kind().value(),
                    d.deliverableRef());
            historyRows.findByEntityAndEntityIdOrderById(HistoryEntity.DELIVERABLE, delivHistoryId)
                    .forEach(entry -> contributors.add(entry.authorId().sessionKey()));
        }
        return contributors;
    }

    /**
     * Collects every session that ever owned the entity across its current row and historical before-snapshots so
     * deliverable ownership changes cannot erase an earlier owner from self-verification checks.
     */
    private List<SessionKey> ownerContributors(HistoryEntity entity, String entityId, ActorId currentOwner) {
        List<SessionKey> owners = new ArrayList<>();
        if (currentOwner != null) {
            owners.add(currentOwner.sessionKey());
        }
        for (HistoryEntry entry : historyRows.findByEntityAndEntityIdOrderById(entity, entityId)) {
            if (entry.beforeData() != null) {
                String beforeOwner = switch (entity) {
                    case ISSUE -> json.read(entry.beforeData(), Snapshot.IssueRow.class).ownerActorId();
                    case SPEC -> json.read(entry.beforeData(), Snapshot.SpecRow.class).ownerActorId();
                    case TASK -> json.read(entry.beforeData(), Snapshot.TaskRow.class).ownerActorId();
                    default -> null;
                };
                if (beforeOwner != null && !beforeOwner.isBlank()) {
                    owners.add(ActorId.ofStored(beforeOwner).sessionKey());
                }
            }
        }
        return owners;
    }

    private List<SessionKey> reporters(Issue row, IssueStatus to) {
        if (to != IssueStatus.VERIFIED) {
            return List.of();
        }
        List<Issue> sources = new ArrayList<>(List.of(row));
        mergeChain.mergedInto(row.id()).forEach(merged -> sources.add(rows.issue(merged)));
        List<SessionKey> reporters = new ArrayList<>(
                ownerContributors(HistoryEntity.ISSUE, row.id().value(), row.ownerActorId()));
        for (HistoryEntry entry : historyRows.findByEntityAndEntityIdOrderById(HistoryEntity.ISSUE, row.id().value())) {
            if (entry.authorId() == null) {
                continue;
            }
            if (entry.op() == HistoryOp.EDIT) {
                reporters.add(entry.authorId().sessionKey());
            } else if (entry.op() == HistoryOp.TRANSITION && IssueStatus.RESOLVED.name().equals(entry.toState())) {
                reporters.add(entry.authorId().sessionKey());
            }
        }
        for (Issue source : sources) {
            if (source.createdBy() != null) {
                reporters.add(source.createdBy().sessionKey());
            }
            cases.findByIssueIdOrderById(source.id()).forEach(c -> reporters.add(c.recordedBy().sessionKey()));
        }
        return reporters;
    }

    private TransitionResult spec(WriteContext ctx, SpecId id, long expected, SpecStatus to, Evidence evidence,
            String attachment) {
        Instant now = clock.instant();
        Spec row = rows.specForWrite(id, expected);
        recheck(ctx, HistoryEntity.SPEC, row.projectId(), row.status(), to);
        List<HistoryEntry> entries = historyRows.findByEntityAndEntityIdOrderById(HistoryEntity.SPEC, id.value());
        SpecReports reports = new SpecHistory(entries).reports();
        List<SessionKey> contributors = new ArrayList<>(deliverableContributors(DeliverableTarget.SPEC, id.value()));
        contributors.addAll(ownerContributors(HistoryEntity.SPEC, id.value(), row.ownerActorId()));
        if (row.createdBy() != null) {
            contributors.add(row.createdBy().sessionKey());
        }
        for (HistoryEntry entry : entries) {
            if (entry.op() == HistoryOp.EDIT && entry.authorId() != null) {
                contributors.add(entry.authorId().sessionKey());
            }
        }
        row.status().requireTransitionTo(to, evidence, ctx.author().sessionKey(), contributors, reports);
        Spec saved = rows.saveSpec(row.transitioned(to, now));
        long historyId = history.append(ctx, HistoryWriter.Entry.of(HistoryEntity.SPEC, id.value(),
                HistoryOp.TRANSITION, ChangeSet.empty()).withStates(row.status().name(), to.name())
                .withEvidence(evidence).withBefore(Snapshot.SpecRow.of(row)).withAttachment(attachment));
        return result(ctx, HistoryEntity.SPEC, id.value(), row.status().name(), to.name(), saved.version(), historyId);
    }

    private TransitionResult task(WriteContext ctx, TaskId id, long expected, TaskState to, Evidence evidence,
            String attachment) {
        Instant now = clock.instant();
        Task row = rows.taskForWrite(id, expected);
        recheck(ctx, HistoryEntity.TASK, row.projectId(), row.state(), to);
        List<HistoryEntry> entries = historyRows.findByEntityAndEntityIdOrderById(HistoryEntity.TASK, id.value());
        TaskReports reports = new TaskHistory(entries).reports();
        List<SessionKey> registrants = new ArrayList<>(deliverableContributors(DeliverableTarget.TASK, id.value()));
        registrants.addAll(ownerContributors(HistoryEntity.TASK, id.value(), row.ownerActorId()));
        if (row.createdBy() != null) {
            registrants.add(row.createdBy().sessionKey());
        }
        for (HistoryEntry entry : entries) {
            if (entry.op() == HistoryOp.EDIT && entry.authorId() != null) {
                registrants.add(entry.authorId().sessionKey());
            }
        }
        row.state().requireTransitionTo(to, evidence, ctx.author().sessionKey(), registrants, reports);
        Task saved = rows.saveTask(row.transitioned(to, now));
        long historyId = history.append(ctx, HistoryWriter.Entry.of(HistoryEntity.TASK, id.value(),
                HistoryOp.TRANSITION, ChangeSet.empty()).withStates(row.state().name(), to.name())
                .withEvidence(evidence).withBefore(Snapshot.TaskRow.of(row)).withAttachment(attachment));
        return result(ctx, HistoryEntity.TASK, id.value(), row.state().name(), to.name(), saved.version(),
                historyId);
    }

    private TransitionResult criterion(WriteContext ctx, CriterionId id, long expected, CriterionState to,
            Evidence evidence, String attachment) {
        Criterion row = rows.criterionForWrite(id, expected);
        recheck(ctx, HistoryEntity.CRITERION, row.projectId(), row.state(), to);
        List<HistoryEntry> entries = historyRows.findByEntityAndEntityIdOrderById(HistoryEntity.CRITERION, id.value());
        CriterionReports reports = new CriterionHistory(entries).reports();
        List<SessionKey> contributors = new ArrayList<>();
        if (row.createdBy() != null) {
            contributors.add(row.createdBy().sessionKey());
        }
        for (HistoryEntry entry : entries) {
            if (entry.op() == HistoryOp.EDIT && entry.authorId() != null) {
                contributors.add(entry.authorId().sessionKey());
            }
        }
        row.state().requireTransitionTo(to, evidence, ctx.author().sessionKey(), contributors, reports);
        Criterion saved = rows.saveCriterion(row.transitioned(to));
        long historyId = history.append(ctx, HistoryWriter.Entry.of(HistoryEntity.CRITERION, id.value(),
                HistoryOp.TRANSITION, ChangeSet.empty()).withStates(row.state().name(), to.name())
                .withEvidence(evidence).withBefore(Snapshot.CriterionRow.of(row)).withAttachment(attachment));
        return result(ctx, HistoryEntity.CRITERION, id.value(), row.state().name(), to.name(), saved.version(),
                historyId);
    }

    private static TransitionResult result(WriteContext ctx, HistoryEntity entity, String id, String from,
            String to, long version, long historyId) {
        return new TransitionResult(WriteStatus.COMMITTED, entity.token(), id, from, to, version, historyId,
                ctx.author().value(), ctx.login());
    }
}
