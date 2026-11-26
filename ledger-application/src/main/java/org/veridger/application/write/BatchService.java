// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application.write;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.veridger.application.gate.WriteGate;
import org.veridger.application.history.HistoryWriter;
import org.veridger.application.receipt.ReceiptService;
import org.veridger.application.validation.ViolationTranslator;
import org.veridger.database.repository.IdAllocator;
import org.veridger.database.repository.IssueCaseRepository;
import org.veridger.domain.error.LedgerException;
import org.veridger.domain.error.ValidationException;
import org.veridger.domain.error.ValidationReason;
import org.veridger.domain.model.BatchOpKind;
import org.veridger.domain.model.ReceiptOperation;
import org.veridger.domain.rule.Channel;
import org.veridger.domain.value.AuthorId;
import org.veridger.domain.value.OperationKey;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Executes up to 200 author-channel write operations atomically in a single transaction with batch-level and
 * per-operation receipt idempotency.
 *
 * <p>To prevent AB-BA deadlocks with concurrent single writes, grant revocations, and other batches, new batches
 * acquire all entity and {@code session_grant} locks upfront in global canonical order via {@link BatchLockPlanner}
 * and defer {@code ISSUE_CASE} and {@code HISTORY} writes to the end of the transaction via {@link BatchBuffer}.
 */
@Service
@Transactional
public class BatchService {

    private final SaveOperation save;
    private final TransitionOperation transition;
    private final RecordDeliverableOperation recordDeliverable;
    private final RecordCaseOperation recordCase;
    private final LinkOperation link;
    private final CorrectOperation correct;
    private final WriteGate gate;
    private final ReceiptService receipts;
    private final Validator validator;
    private final BatchLockPlanner lockPlanner;
    private final IdAllocator ids;
    private final IssueCaseRepository cases;
    private final HistoryWriter history;

    public BatchService(SaveOperation save, TransitionOperation transition,
            RecordDeliverableOperation recordDeliverable, RecordCaseOperation recordCase, LinkOperation link,
            CorrectOperation correct, WriteGate gate, ReceiptService receipts, Validator validator,
            BatchLockPlanner lockPlanner, IdAllocator ids, IssueCaseRepository cases, HistoryWriter history) {
        this.save = save;
        this.transition = transition;
        this.recordDeliverable = recordDeliverable;
        this.recordCase = recordCase;
        this.link = link;
        this.correct = correct;
        this.gate = gate;
        this.receipts = receipts;
        this.validator = validator;
        this.lockPlanner = lockPlanner;
        this.ids = ids;
        this.cases = cases;
        this.history = history;
    }

    public record Recorded(int count) {
    }

    public BatchResult batch(String actor, String operationKey, List<BatchOpInput> ops) {
        AuthorId author = gate.verifyAuthor(actor, operationKey);
        if (ops == null || ops.isEmpty() || ops.size() > BatchOpKind.MAX_OPS_PER_BATCH) {
            throw ValidationException.invalid("ops", ValidationReason.MUST_HOLD_ITEMS, "1",
                    String.valueOf(BatchOpKind.MAX_OPS_PER_BATCH));
        }
        OperationKey batchKey = OperationKey.parse(operationKey);
        for (int i = 0; i < ops.size(); i++) {
            if (ops.get(i) == null) {
                throw ValidationException.invalid("ops[" + i + "]", ValidationReason.REQUIRED);
            }
        }
        boolean first = receipts.reserve(batchKey, ReceiptOperation.BATCH, BatchDigest.of(ops), author, Channel.AUTHOR,
                Recorded.class) instanceof ReceiptService.Reservation.Reserved<Recorded>;
        if (!first) {
            List<WriteResult> replayed = new ArrayList<>(ops.size());
            for (int i = 0; i < ops.size(); i++) {
                BatchOpInput op = ops.get(i);
                String opKey = batchKey.derive(i).toString();
                try {
                    requireKeys(op, i);
                    replayed.add(run(actor, operationKey, op, opKey, null));
                } catch (LedgerException failure) {
                    throw failure.withDetails(Map.of("op_index", List.of(Integer.toString(i + 1)),
                            "op", List.of(kind(op).token())));
                }
            }
            return new BatchResult(WriteStatus.COMMITTED, replayed.size(), replayed);
        }
        // Validate required keys before acquiring any locks so invalid batches fail fast without locking rows
        for (int i = 0; i < ops.size(); i++) {
            BatchOpInput op = ops.get(i);
            try {
                requireKeys(op, i);
            } catch (LedgerException failure) {
                throw failure.withDetails(Map.of("op_index", List.of(Integer.toString(i + 1)),
                        "op", List.of(kind(op).token())));
            }
        }
        BatchBuffer batch = lockPlanner.acquireLocks(author, ops);
        for (int i = 0; i < ops.size(); i++) {
            BatchOpInput op = ops.get(i);
            String opKey = batchKey.derive(i).toString();
            try {
                run(actor, operationKey, op, opKey, batch);
            } catch (LedgerException failure) {
                throw failure.withDetails(Map.of("op_index", List.of(Integer.toString(i + 1)),
                        "op", List.of(kind(op).token())));
            }
        }
        List<WriteResult> results = batch.flush(ids, cases, history, receipts);
        receipts.finish(batchKey, author, new Recorded(results.size()));
        return new BatchResult(WriteStatus.COMMITTED, results.size(), results);
    }

    private void requireKeys(BatchOpInput op, int index) {
        Set<ConstraintViolation<BatchOpInput>> violations = validator.validate(op);
        if (!violations.isEmpty()) {
            throw new ViolationTranslator("ops[" + index + "]").translate(violations);
        }
    }

    private static BatchOpKind kind(BatchOpInput op) {
        return switch (op) {
            case BatchOpInput.Save s -> BatchOpKind.SAVE;
            case BatchOpInput.Transition t -> BatchOpKind.TRANSITION;
            case BatchOpInput.RecordDeliverable d -> BatchOpKind.RECORD_DELIVERABLE;
            case BatchOpInput.RecordCase c -> BatchOpKind.RECORD_CASE;
            case BatchOpInput.Link l -> BatchOpKind.LINK;
            case BatchOpInput.Correct c -> BatchOpKind.CORRECT;
        };
    }

    private WriteResult run(String actor, String boundKey, BatchOpInput op, String id, BatchBuffer batch) {
        return switch (op) {
            case BatchOpInput.Save s -> save.run(actor, boundKey, Channel.AUTHOR,
                    new SaveCommand(id, s.entity(), s.id(), s.expectedVersion(), s.fields(), s.reason()), batch);
            case BatchOpInput.Transition t -> transition.run(actor, boundKey, Channel.AUTHOR,
                    new TransitionCommand(id, t.entity(), t.id(), t.expectedVersion(), t.to(), t.reason(),
                            t.evidenceUri(), t.evidenceSha256(), t.mergedInto(), t.attachmentId()), batch);
            case BatchOpInput.RecordDeliverable d -> recordDeliverable.run(actor, boundKey, Channel.AUTHOR,
                    new RecordDeliverableCommand(id, d.targetEntity(), d.targetId(), d.kind(), d.deliverableRef(),
                            d.reason(), d.location(), d.summary(), d.checkRef(), d.checkResult(), d.checkedAt(),
                            d.markDone(), d.attachmentId()), batch);
            case BatchOpInput.RecordCase c -> recordCase.run(actor, boundKey, Channel.AUTHOR,
                    new RecordCaseCommand(id, c.issueId(), c.observation(), c.reason(), c.evidence(),
                            c.sourceQuote(), c.observedAt(), c.attachmentId()), batch);
            case BatchOpInput.Link l -> link.run(actor, boundKey, Channel.AUTHOR,
                    new LinkCommand(id, l.kind(), l.a(), l.b(), l.reason(), l.remove()), batch);
            case BatchOpInput.Correct c -> correct.run(actor, boundKey, Channel.AUTHOR,
                    new CorrectCommand(id, c.historyId(), c.reason(), c.attachmentId()), batch);
        };
    }
}
