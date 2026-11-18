package org.pgledger.application.write;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.pgledger.application.attachment.AttachmentLinks;
import org.pgledger.application.gate.Lookup;
import org.pgledger.application.gate.Resolution;
import org.pgledger.application.gate.Target;
import org.pgledger.application.gate.WriteGate;
import org.pgledger.application.history.Change;
import org.pgledger.application.history.ChangeSet;
import org.pgledger.application.history.HistoryKey;
import org.pgledger.application.history.HistoryWriter;
import org.pgledger.application.history.Snapshot;
import org.pgledger.application.json.LedgerJson;
import org.pgledger.application.receipt.ReceiptService;
import org.pgledger.application.write.WriteResult.CorrectResult;
import org.pgledger.database.repository.HistoryRepository;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationReason;
import org.pgledger.domain.model.HistoryEntry;
import org.pgledger.domain.model.ReceiptOperation;
import org.pgledger.domain.rule.HistoryOp;
import org.pgledger.domain.rule.Scope;
import org.pgledger.domain.value.CanonicalPayload;
import org.pgledger.domain.value.ConditionId;
import org.pgledger.domain.value.DefectId;
import org.pgledger.domain.value.TaskId;
import org.springframework.stereotype.Component;

/**
 * Points at a wrong history row with a CORRECT row of the same entity. The wrong row itself is never changed.
 * The scope is the one that writes the corrected entity, and verify when the corrected row is a verification.
 * Correcting a CORRECT row is allowed; cross-project links, relations, merges, and chained corrections check all endpoints.
 */
@Component
public class CorrectOperation extends WriteOperation<CorrectCommand, CorrectResult> {

    private final Rows rows;
    private final HistoryRepository historyRows;
    private final HistoryWriter history;
    private final AttachmentLinks attachments;
    private final LedgerJson json;

    public CorrectOperation(WriteGate gate, ReceiptService receipts, Rows rows, HistoryRepository historyRows,
            HistoryWriter history, AttachmentLinks attachments, LedgerJson json) {
        super(gate, receipts, ReceiptOperation.CORRECT, CorrectResult.class);
        this.rows = rows;
        this.historyRows = historyRows;
        this.history = history;
        this.attachments = attachments;
        this.json = json;
    }

    @Override
    protected Lookup resolve(CorrectCommand c) {
        return Lookup.verifiable(() -> {
            if (c.historyId() == null) {
                return Resolution.unresolved(ValidationException.invalid("history_id", ValidationReason.REQUIRED));
            }
            return historyRows.findById(c.historyId()).map(this::target)
                    .orElseGet(() -> Resolution.unresolved(Rows.notFound("history", c.historyId().toString())));
        });
    }

    /** Every project and scope involved by the corrected history entry (including both ends of links/merges and chains). */
    private Resolution target(HistoryEntry row) {
        Scope scope = row.involvesVerification() ? Scope.VERIFY : row.entity().writeScope();
        HistoryEntry origin = originRow(row);
        List<Resolution> parts = new ArrayList<>();
        switch (origin.entity()) {
            case TASK -> parts.add(rows.taskTarget(TaskId.of(origin.entityId()), scope));
            case CONDITION -> parts.add(rows.conditionTarget(ConditionId.of(origin.entityId()), scope));
            case DEFECT -> {
                parts.add(rows.defectTarget(DefectId.of(origin.entityId()), scope));
                mergedInto(origin).ifPresent(merged -> parts.add(rows.defectTarget(merged, Scope.DEFECT)));
            }
            case DEFECT_FIX, DEFECT_CASE -> {
                String owner = new HistoryKey(origin.entity(), origin.entityId()).ownerId();
                parts.add(rows.defectTarget(DefectId.of(owner), scope));
            }
            case DEFECT_TASK -> {
                String[] ends = origin.entityId().split("\\|", 2);
                parts.add(rows.defectTarget(DefectId.of(ends[0]), scope));
                parts.add(rows.taskTarget(TaskId.of(ends[1]), scope));
            }
            case DEFECT_RELATION -> {
                String[] ends = origin.entityId().split("\\|", 3);
                parts.add(rows.defectTarget(DefectId.of(ends[0]), scope));
                parts.add(rows.defectTarget(DefectId.of(ends[1]), scope));
            }
            case CONDITION_LINK -> addConditionLinkTargets(origin, scope, parts);
        }
        return combine(parts);
    }

    private HistoryEntry originRow(HistoryEntry row) {
        HistoryEntry current = row;
        Set<Long> seen = new HashSet<>();
        while (current.op() == HistoryOp.CORRECT && current.correctsId() != null && seen.add(current.id())) {
            Optional<HistoryEntry> parent = historyRows.findById(current.correctsId());
            if (parent.isEmpty()) {
                return current;
            }
            current = parent.orElseThrow();
        }
        return current;
    }

    private Optional<DefectId> mergedInto(HistoryEntry origin) {
        if (origin.op() != HistoryOp.TRANSITION || !"MERGED".equals(origin.toState()) || origin.changes() == null) {
            return Optional.empty();
        }
        ChangeSet parsed = json.read(origin.changes(), ChangeSet.class);
        return Optional.ofNullable(parsed.entries().get("merged_into")).map(Change::after).filter(v -> !v.isBlank())
                .flatMap(DefectId::parse);
    }

    private void addConditionLinkTargets(HistoryEntry origin, Scope scope, List<Resolution> parts) {
        if (origin.beforeData() != null) {
            Snapshot.ConditionLinkRow snap = json.read(origin.beforeData(), Snapshot.ConditionLinkRow.class);
            parts.add(rows.conditionTarget(ConditionId.of(snap.conditionId()), scope));
            if (snap.taskId() != null) {
                parts.add(rows.taskTarget(TaskId.of(snap.taskId()), scope));
            } else if (snap.defectId() != null) {
                parts.add(rows.defectTarget(DefectId.of(snap.defectId()), scope));
            }
            return;
        }
        String entityId = origin.entityId();
        int sep = entityId.indexOf('|');
        String condId = sep < 0 ? entityId : entityId.substring(0, sep);
        String targetId = sep < 0 ? "" : entityId.substring(sep + 1);
        parts.add(rows.conditionTarget(ConditionId.of(condId), scope));
        Optional<Resolution> defectTarget = DefectId.parse(targetId)
                .flatMap(id -> rows.findDefect(id).map(found -> rows.defectTarget(found.id(), scope)));
        Optional<Resolution> taskTarget = TaskId.parse(targetId)
                .flatMap(id -> rows.findTask(id).map(found -> rows.taskTarget(found.id(), scope)));
        defectTarget.ifPresent(parts::add);
        taskTarget.ifPresent(parts::add);
        if (defectTarget.isEmpty() && taskTarget.isEmpty()) {
            parts.add(rows.taskTarget(TaskId.of(targetId), scope));
        }
    }

    private static Resolution combine(List<Resolution> parts) {
        List<Target> targets = new ArrayList<>();
        for (Resolution part : parts) {
            switch (part) {
                case Resolution.Unresolved u -> {
                    return u;
                }
                case Resolution.Resolved r -> targets.addAll(r.targets());
            }
        }
        return new Resolution.Resolved(targets);
    }

    @Override
    protected Prepared<CorrectResult> prepare(CorrectCommand c) {
        long id = c.requireHistoryId();
        CanonicalPayload.Fields payload = CanonicalPayload.Fields.empty().text("history_id", Long.toString(id))
                .text("attachment_id", c.attachmentId());
        return new Prepared<>(payload, ctx -> {
            // before anything is written: a missing or foreign attachment fails the whole record (design 5)
            attachments.requireOwnedBy(c.attachmentId(), ctx.author());
            HistoryEntry row = historyRows.findById(id)
                    .orElseThrow(() -> Rows.notFound("history", Long.toString(id)));
            long newId = history.append(ctx, HistoryWriter.Entry.of(row.entity(), row.entityId(),
                    HistoryOp.CORRECT, ChangeSet.empty()).correcting(id).withAttachment(c.attachmentId()));
            return new CorrectResult(WriteStatus.COMMITTED, id, newId, ctx.author().value(), ctx.login());
        });
    }
}
