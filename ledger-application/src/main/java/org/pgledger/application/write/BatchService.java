package org.pgledger.application.write;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.pgledger.application.gate.WriteGate;
import org.pgledger.application.receipt.ReceiptService;
import org.pgledger.application.validation.ViolationTranslator;
import org.pgledger.domain.error.LedgerException;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationReason;
import org.pgledger.domain.model.BatchOpKind;
import org.pgledger.domain.model.ReceiptOperation;
import org.pgledger.domain.rule.Channel;
import org.pgledger.domain.value.AuthorId;
import org.pgledger.domain.value.OperationKey;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Up to 200 write operations in one transaction: they all commit or none does. Every operation runs through the
 * same steps as its single tool (gate, grants, reason, receipt, business rules) through the author channel, so a
 * verification cannot be part of a batch. The batch has one operation key; every operation gets a key derived from the
 * key of the batch and its position, and the batch has a receipt of its own under its key with the digest of all its
 * operations, so sending the same batch again returns the stored results instead of writing twice and the same key with
 * other operations is refused. A failure names the operation (details {@code op_index}, 1-based, and {@code op}) and rolls everything back.
 */
@Service
@Transactional
public class BatchService {

    private final SaveOperation save;
    private final TransitionOperation transition;
    private final RecordFixOperation recordFix;
    private final RecordCaseOperation recordCase;
    private final LinkOperation link;
    private final CorrectOperation correct;
    private final WriteGate gate;
    private final ReceiptService receipts;
    private final Validator validator;

    public BatchService(SaveOperation save, TransitionOperation transition, RecordFixOperation recordFix,
            RecordCaseOperation recordCase, LinkOperation link, CorrectOperation correct, WriteGate gate,
            ReceiptService receipts, Validator validator) {
        this.save = save;
        this.transition = transition;
        this.recordFix = recordFix;
        this.recordCase = recordCase;
        this.link = link;
        this.correct = correct;
        this.gate = gate;
        this.receipts = receipts;
        this.validator = validator;
    }

    /** What the receipt of a batch key stores: the number of operations that were written. */
    public record Recorded(int count) {
    }

    /**
     * The order of the checks is that of every write (and of {@code artifact-begin}): the host attestation first, then the
     * cheap checks of the input (the number of operations), then the operation key, then the content. The receipt of the
     * batch key is reserved with the digest of the whole content before any operation runs: the same key with the same
     * operations is the same batch again (each operation then answers from its own receipt, which has a key derived from the
     * batch key and its position, and passes its own gate again); the same key with other operations (one added, removed,
     * changed or moved) or under another tool is OPERATION_CONFLICT and nothing is written.
     */
    public BatchResult batch(String actor, String operationKey, List<BatchOpInput> ops) {
        AuthorId author = gate.verifyAuthor(actor, operationKey);
        if (ops == null || ops.isEmpty() || ops.size() > BatchOpKind.MAX_OPS_PER_BATCH) {
            throw ValidationException.invalid("ops", ValidationReason.MUST_HOLD_ITEMS, "1", String.valueOf(BatchOpKind.MAX_OPS_PER_BATCH));
        }
        OperationKey batchKey = OperationKey.parse(operationKey);
        boolean first = receipts.reserve(batchKey, ReceiptOperation.BATCH, BatchDigest.of(ops), author, Channel.AUTHOR,
                Recorded.class) instanceof ReceiptService.Reservation.Reserved<Recorded>;
        List<WriteResult> results = new ArrayList<>();
        for (int i = 0; i < ops.size(); i++) {
            BatchOpInput op = ops.get(i);
            String opKey = batchKey.derive(i).toString();
            try {
                requireKeys(op, i);
                results.add(run(actor, operationKey, op, opKey));
            } catch (LedgerException failure) {
                throw failure.withDetails(Map.of("op_index", List.of(Integer.toString(i + 1)),
                        "op", List.of(kind(op).token())));
            }
        }
        if (first) {
            receipts.finish(batchKey, author, new Recorded(results.size()));
        }
        return new BatchResult(WriteStatus.COMMITTED, results.size(), results);
    }

    /**
     * The required keys of an operation are the constraints of its record; a violation is named by the path of the key in
     * the request ({@code ops[1].reason}). The operation is validated just before it runs, so the operations before it
     * have run (and are rolled back with the batch) exactly as when the keys were checked by hand.
     */
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
            case BatchOpInput.RecordFix f -> BatchOpKind.RECORD_FIX;
            case BatchOpInput.RecordCase c -> BatchOpKind.RECORD_CASE;
            case BatchOpInput.Link l -> BatchOpKind.LINK;
            case BatchOpInput.Correct c -> BatchOpKind.CORRECT;
        };
    }

    /** {@code boundKey} is the operation key of the whole batch as the caller sent it: what the attestation is bound to. */
    private WriteResult run(String actor, String boundKey, BatchOpInput op, String id) {
        return switch (op) {
            case BatchOpInput.Save s -> save.run(actor, boundKey, Channel.AUTHOR,
                    new SaveCommand(id, s.entity(), s.id(), s.expectedVersion(), s.fields(), s.reason()));
            case BatchOpInput.Transition t -> transition.run(actor, boundKey, Channel.AUTHOR,
                    new TransitionCommand(id, t.entity(), t.id(), t.expectedVersion(), t.to(), t.reason(),
                            t.evidenceUri(), t.evidenceSha256(), t.mergedInto(), t.attachmentId()));
            case BatchOpInput.RecordFix f -> recordFix.run(actor, boundKey, Channel.AUTHOR,
                    new RecordFixCommand(id, f.defectId(), f.repo(), f.commitSha(), f.reason(), f.branch(),
                            f.ciRef(), f.ciConclusion(), f.ciCompletedAt(), f.markSourceFixed(), f.attachmentId()));
            case BatchOpInput.RecordCase c -> recordCase.run(actor, boundKey, Channel.AUTHOR,
                    new RecordCaseCommand(id, c.defectId(), c.observation(), c.reason(), c.evidence(),
                            c.userQuote(), c.observedAt(), c.attachmentId()));
            case BatchOpInput.Link l -> link.run(actor, boundKey, Channel.AUTHOR,
                    new LinkCommand(id, l.kind(), l.a(), l.b(), l.reason(), l.remove()));
            case BatchOpInput.Correct c -> correct.run(actor, boundKey, Channel.AUTHOR,
                    new CorrectCommand(id, c.historyId(), c.reason(), c.attachmentId()));
        };
    }
}
