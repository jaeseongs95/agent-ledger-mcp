// SPDX-License-Identifier: AGPL-3.0-or-later

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
import org.pgledger.database.repository.IdAllocator;
import org.pgledger.database.repository.IssueCaseRepository;
import org.pgledger.database.repository.IssueRepository;
import org.pgledger.domain.error.TransitionCode;
import org.pgledger.domain.error.TransitionException;
import org.pgledger.domain.model.Issue;
import org.pgledger.domain.model.IssueCase;
import org.pgledger.domain.model.ReceiptOperation;
import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.rule.HistoryOp;
import org.pgledger.domain.rule.IssueStatus;
import org.pgledger.domain.rule.Scope;
import org.pgledger.domain.value.CanonicalPayload;
import org.pgledger.domain.value.IssueId;
import org.pgledger.domain.value.TextRule;
import org.pgledger.domain.value.UtcInstant;
import org.springframework.stereotype.Component;

/**
 * Records one observed occurrence under an issue.
 */
@Component
public class RecordCaseOperation extends WriteOperation<RecordCaseCommand, RecordCaseResult> {

    private final Rows rows;
    private final IssueRepository issues;
    private final IssueCaseRepository cases;
    private final IdAllocator ids;
    private final HistoryWriter history;
    private final AttachmentLinks attachments;
    private final Clock clock;

    public RecordCaseOperation(WriteGate gate, ReceiptService receipts, Rows rows, IssueRepository issues,
            IssueCaseRepository cases, IdAllocator ids, HistoryWriter history, AttachmentLinks attachments,
            Clock clock) {
        super(gate, receipts, ReceiptOperation.RECORD_CASE, RecordCaseResult.class);
        this.rows = rows;
        this.issues = issues;
        this.cases = cases;
        this.ids = ids;
        this.history = history;
        this.attachments = attachments;
        this.clock = clock;
    }

    @Override
    protected Lookup resolve(RecordCaseCommand c) {
        return Lookup.afterActor(() -> List.of(new Target(rows.issue(IssueId.of(c.issueId())).projectId(),
                Scope.ISSUE)));
    }

    @Override
    protected Prepared<RecordCaseResult> prepare(RecordCaseCommand c) {
        IssueId issueId = IssueId.of(c.issueId());
        String observation = TextRule.OBSERVATION.required(c.observation());
        String evidence = TextRule.EVIDENCE.optional(c.evidence());
        String quote = TextRule.SOURCE_QUOTE.optional(c.sourceQuote());
        Instant observedAt = c.observedAt() == null || c.observedAt().isBlank() ? null
                : UtcInstant.parse("observed_at", c.observedAt()).value();
        CanonicalPayload.Fields payload = CanonicalPayload.Fields.empty().text("issue", issueId.value())
                .text("observation", observation).text("evidence", evidence).text("source_quote", quote)
                .text("observed_at", ChangeSet.text(observedAt)).text("attachment_id", c.attachmentId());
        return new Prepared<>(payload, ctx -> execute(ctx, issueId, observation, evidence, quote, observedAt,
                c.attachmentId()));
    }

    private RecordCaseResult execute(WriteContext ctx, IssueId issueId, String observation, String evidence,
            String quote, Instant observedAt, String attachment) {
        attachments.requireOwnedBy(attachment, ctx.author());
        if (!issues.nextCaseSeq(issueId)) {
            Issue issue = rows.issue(issueId);
            if (issue.status() == IssueStatus.VERIFIED) {
                throw new TransitionException(TransitionCode.VERIFIED_ENTITY, List.of(issueId.value()));
            }
            throw new TransitionException(TransitionCode.MERGED_ISSUE,
                    List.of(issueId.value(), issue.mergedInto() == null ? "?" : issue.mergedInto().value()));
        }
        long caseId = ids.next(Counter.ISSUE_CASE);
        cases.insert(IssueCase.observe(caseId, issueId, observation, evidence, quote, observedAt, ctx.author(),
                clock.instant()));
        String entityId = issueId.value() + "|" + caseId;
        ChangeSet changes = ChangeSet.empty().with("observation", null, observation).with("evidence", null, evidence)
                .with("source_quote", null, quote).with("observed_at", null, ChangeSet.text(observedAt));
        long historyId = history.append(ctx, HistoryWriter.Entry.of(HistoryEntity.ISSUE_CASE, entityId,
                HistoryOp.CREATE, changes).withAttachment(attachment));
        return new RecordCaseResult(WriteStatus.COMMITTED, HistoryEntity.ISSUE_CASE.token(), entityId, caseId,
                issueId.value(), historyId, ctx.author().value(), ctx.login());
    }
}
