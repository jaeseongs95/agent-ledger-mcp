package org.pgledger.application.write;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.pgledger.application.attachment.AttachmentLinks;
import org.pgledger.application.gate.Lookup;
import org.pgledger.application.gate.Target;
import org.pgledger.application.gate.WriteGate;
import org.pgledger.application.history.ChangeSet;
import org.pgledger.application.history.HistoryWriter;
import org.pgledger.application.receipt.ReceiptService;
import org.pgledger.application.write.WriteResult.RecordCaseResult;
import org.pgledger.database.repository.Counter;
import org.pgledger.database.repository.DefectCaseRepository;
import org.pgledger.database.repository.DefectRepository;
import org.pgledger.database.repository.IdAllocator;
import org.pgledger.domain.error.TransitionCode;
import org.pgledger.domain.error.TransitionException;
import org.pgledger.domain.model.Defect;
import org.pgledger.domain.model.DefectCase;
import org.pgledger.domain.model.ReceiptOperation;
import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.rule.HistoryOp;
import org.pgledger.domain.rule.Scope;
import org.pgledger.domain.value.CanonicalPayload;
import org.pgledger.domain.value.DefectId;
import org.pgledger.domain.value.TextRule;
import org.pgledger.domain.value.UtcInstant;
import org.springframework.stereotype.Component;

/**
 * Records one observed case under a defect. The case takes its number with a conditional counter UPDATE of the defect row
 * that affects no row for a MERGED defect (the case is refused and names the merge target); a merge updates the same
 * row, so the database decides which of the two came first. The counter is a real write of the use case (the case
 * number): it changes neither the version nor the time of the defect, and no defect history row is written for it.
 */
@Component
public class RecordCaseOperation extends WriteOperation<RecordCaseCommand, RecordCaseResult> {

    private final Rows rows;
    private final DefectRepository defects;
    private final DefectCaseRepository cases;
    private final IdAllocator ids;
    private final HistoryWriter history;
    private final AttachmentLinks attachments;
    private final Clock clock;

    public RecordCaseOperation(WriteGate gate, ReceiptService receipts, Rows rows, DefectRepository defects,
            DefectCaseRepository cases, IdAllocator ids, HistoryWriter history, AttachmentLinks attachments,
            Clock clock) {
        super(gate, receipts, ReceiptOperation.RECORD_CASE, RecordCaseResult.class);
        this.rows = rows;
        this.defects = defects;
        this.cases = cases;
        this.ids = ids;
        this.history = history;
        this.attachments = attachments;
        this.clock = clock;
    }

    @Override
    protected Lookup resolve(RecordCaseCommand c) {
        return Lookup.afterActor(() -> List.of(new Target(rows.defect(DefectId.of(c.defectId())).projectId(),
                Scope.DEFECT)));
    }

    @Override
    protected Prepared<RecordCaseResult> prepare(RecordCaseCommand c) {
        DefectId defectId = DefectId.of(c.defectId());
        String observation = TextRule.OBSERVATION.required(c.observation());
        String evidence = TextRule.EVIDENCE.optional(c.evidence());
        String quote = TextRule.USER_QUOTE.optional(c.userQuote());
        Instant observedAt = c.observedAt() == null || c.observedAt().isBlank() ? null
                : UtcInstant.parse("observed_at", c.observedAt()).value();
        CanonicalPayload.Fields payload = CanonicalPayload.Fields.empty().text("defect", defectId.value())
                .text("observation", observation).text("evidence", evidence).text("user_quote", quote)
                .text("observed_at", ChangeSet.text(observedAt)).text("attachment_id", c.attachmentId());
        return new Prepared<>(payload, ctx -> execute(ctx, defectId, observation, evidence, quote, observedAt,
                c.attachmentId()));
    }

    private RecordCaseResult execute(WriteContext ctx, DefectId defectId, String observation, String evidence,
            String quote, Instant observedAt, String attachment) {
        // before anything is written: a missing or foreign attachment fails the whole record (design 5)
        attachments.requireOwnedBy(attachment, ctx.author());
        if (!defects.nextCaseSeq(defectId)) {
            Defect defect = rows.defect(defectId);
            throw new TransitionException(TransitionCode.MERGED_DEFECT,
                    List.of(defectId.value(), defect.mergedInto() == null ? "?" : defect.mergedInto().value()));
        }
        long caseId = ids.next(Counter.DEFECT_CASE);
        cases.insert(DefectCase.observe(caseId, defectId, observation, evidence, quote, observedAt, ctx.author(),
                clock.instant()));
        String entityId = defectId.value() + "|" + caseId;
        ChangeSet changes = ChangeSet.empty().with("observation", null, observation).with("evidence", null, evidence)
                .with("user_quote", null, quote).with("observed_at", null, ChangeSet.text(observedAt));
        long historyId = history.append(ctx, HistoryWriter.Entry.of(HistoryEntity.DEFECT_CASE, entityId,
                HistoryOp.CREATE, changes).withAttachment(attachment));
        return new RecordCaseResult(WriteStatus.COMMITTED, HistoryEntity.DEFECT_CASE.token(), entityId, caseId,
                defectId.value(), historyId, ctx.author().value(), ctx.login());
    }
}
