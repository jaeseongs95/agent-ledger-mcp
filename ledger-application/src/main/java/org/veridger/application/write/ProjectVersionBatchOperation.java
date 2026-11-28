// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application.write;

import java.util.List;
import org.springframework.stereotype.Component;
import org.veridger.application.admin.ProjectVersionService;
import org.veridger.application.admin.ProjectVersionService.ItemRef;
import org.veridger.application.gate.GateSession;
import org.veridger.application.gate.Target;
import org.veridger.application.gate.WriteGate;
import org.veridger.application.history.ChangeSet;
import org.veridger.application.history.HistoryWriter;
import org.veridger.application.receipt.ReceiptService;
import org.veridger.application.write.WriteResult.ProjectVersionResult;
import org.veridger.domain.error.InfrastructureCode;
import org.veridger.domain.error.InfrastructureException;
import org.veridger.domain.error.LookupCode;
import org.veridger.domain.error.LookupException;
import org.veridger.domain.model.ReceiptOperation;
import org.veridger.domain.rule.Channel;
import org.veridger.domain.rule.HistoryOp;
import org.veridger.domain.value.AuthorId;
import org.veridger.domain.value.CriterionId;
import org.veridger.domain.value.IssueId;
import org.veridger.domain.value.OperationKey;
import org.veridger.domain.value.ProjectId;
import org.veridger.domain.value.Reason;
import org.veridger.domain.value.SpecId;
import org.veridger.domain.value.TaskId;

/** A project version and its item links are changed together in the author batch. */
@Component
class ProjectVersionBatchOperation {

    private final ProjectVersionService versions;
    private final WriteGate gate;
    private final Rows rows;
    private final ReceiptService receipts;
    private final HistoryWriter history;

    ProjectVersionBatchOperation(ProjectVersionService versions, WriteGate gate, Rows rows, ReceiptService receipts,
            HistoryWriter history) {
        this.versions = versions;
        this.gate = gate;
        this.rows = rows;
        this.receipts = receipts;
        this.history = history;
    }

    ProjectVersionResult run(AuthorId author, BatchOpInput.ProjectVersion change, OperationKey key, BatchBuffer batch) {
        ProjectId project = ProjectId.of(change.projectId());
        ProjectVersionLinks links = ProjectVersionLinks.from(change);
        List<Target> targets = links.targets(project, change);
        WriteContext context = new WriteContext(new GateSession(author, Channel.AUTHOR), Reason.of(change.reason()), key,
                batch);
        if (batch == null) {
            targets.forEach(target -> gate.requireLive(author, target));
        } else {
            context.pinProject(rows, project);
            context.authorize(gate, targets);
        }
        // A separate digest tag keeps this compound operation distinct from the outer batch receipt.
        return switch (receipts.reserve(key, ReceiptOperation.BATCH, BatchDigest.versionReceipt(change), author,
                Channel.AUTHOR, ProjectVersionResult.class)) {
            case ReceiptService.Reservation.Replayed<ProjectVersionResult> replayed -> {
                if (batch != null) {
                    batch.recordReplayed(replayed.response());
                }
                yield replayed.response();
            }
            case ReceiptService.Reservation.Reserved<ProjectVersionResult> reserved -> {
                if (batch == null) {
                    throw new InfrastructureException(InfrastructureCode.INTEGRITY_VIOLATION,
                            List.of("project version receipt missing for " + key));
                }
                yield context.session().bind(() -> apply(change, links, context, batch));
            }
        };
    }

    private ProjectVersionResult apply(BatchOpInput.ProjectVersion change, ProjectVersionLinks links,
            WriteContext context, BatchBuffer batch) {
        links.items().forEach(item -> requirePinned(item, batch));
        var result = versions.applyAuthorizedChange(new ProjectVersionService.Change(change.projectId(), change.name(),
                Boolean.TRUE.equals(change.create()), change.originalName(), change.description(), links.linked(),
                links.unlinked()));
        result.linked().forEach(item -> recordLink(context, item, HistoryOp.LINK, null, result.name()));
        result.unlinked().forEach(item -> recordLink(context, item, HistoryOp.UNLINK, result.name(), null));
        var response = new ProjectVersionResult(change.projectId(), result.id(), result.name(), result.created(),
                result.linked().size(), result.unlinked().size(), context.author().value(), context.reason().text());
        batch.recordPendingReceipt(context.operationKey(), context.author(), response);
        return response;
    }

    private static void requirePinned(ItemRef item, BatchBuffer batch) {
        boolean pinned = switch (item.entity()) {
            case ISSUE -> batch.hasPinnedIssue(IssueId.of(item.id()));
            case SPEC -> batch.hasPinnedSpec(SpecId.of(item.id()));
            case TASK -> batch.hasPinnedTask(TaskId.of(item.id()));
            case CRITERION -> batch.hasPinnedCriterion(CriterionId.of(item.id()));
            case DELIVERABLE, ISSUE_TASK, ISSUE_SPEC, SPEC_TASK, ISSUE_RELATION, CRITERION_LINK, ISSUE_CASE -> false;
        };
        if (!pinned) {
            throw new LookupException(LookupCode.NOT_FOUND, List.of(item.entity().token(), item.id()));
        }
    }

    private void recordLink(WriteContext context, ItemRef item, HistoryOp op, String before, String after) {
        context.appendHistory(history, HistoryWriter.Entry.of(item.entity(), item.id(), op,
                ChangeSet.empty().with("project_version", before, after)));
    }
}
