// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application.write;

import java.util.List;
import org.springframework.stereotype.Component;
import org.veridger.application.admin.ProjectVersionService;
import org.veridger.application.gate.GateSession;
import org.veridger.application.gate.Target;
import org.veridger.application.gate.WriteGate;
import org.veridger.application.history.ChangeSet;
import org.veridger.application.history.HistoryWriter;
import org.veridger.application.receipt.ReceiptService;
import org.veridger.application.write.WriteResult.ProjectVersionResult;
import org.veridger.domain.error.InfrastructureCode;
import org.veridger.domain.error.InfrastructureException;
import org.veridger.domain.model.ReceiptOperation;
import org.veridger.domain.rule.Channel;
import org.veridger.domain.rule.HistoryEntity;
import org.veridger.domain.rule.HistoryOp;
import org.veridger.domain.rule.Scope;
import org.veridger.domain.value.AuthorId;
import org.veridger.domain.value.OperationKey;
import org.veridger.domain.value.ProjectId;
import org.veridger.domain.value.Reason;

/** A project version and its issue links are changed together in the author batch. */
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
        Target target = new Target(project, Scope.ISSUE);
        WriteContext context = new WriteContext(new GateSession(author, Channel.AUTHOR), Reason.of(change.reason()), key,
                batch);
        if (batch == null) {
            gate.requireLive(author, target);
        } else {
            context.pinProject(rows, project);
            context.authorize(gate, List.of(target));
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
                yield context.session().bind(() -> apply(change, context, batch));
            }
        };
    }

    private ProjectVersionResult apply(BatchOpInput.ProjectVersion change, WriteContext context, BatchBuffer batch) {
        var result = versions.applyAuthorizedChange(new ProjectVersionService.Change(change.projectId(), change.name(),
                Boolean.TRUE.equals(change.create()), change.originalName(), change.description(), change.linkIssueIds(),
                change.unlinkIssueIds()));
        result.linked().forEach(id -> recordLink(context, id, HistoryOp.LINK, null, result.name()));
        result.unlinked().forEach(id -> recordLink(context, id, HistoryOp.UNLINK, result.name(), null));
        var response = new ProjectVersionResult(change.projectId(), result.id(), result.name(), result.created(),
                result.linked().size(), result.unlinked().size(), context.author().value(), context.reason().text());
        batch.recordPendingReceipt(context.operationKey(), context.author(), response);
        return response;
    }

    private void recordLink(WriteContext context, String id, HistoryOp op, String before, String after) {
        context.appendHistory(history, HistoryWriter.Entry.of(HistoryEntity.ISSUE, id, op,
                ChangeSet.empty().with("project_version", before, after)));
    }
}
