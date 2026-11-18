package org.pgledger.application.write;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.pgledger.application.attachment.AttachmentLinks;
import org.pgledger.application.gate.Lookup;
import org.pgledger.application.gate.Target;
import org.pgledger.application.gate.WriteGate;
import org.pgledger.application.history.ChangeSet;
import org.pgledger.application.history.HistoryWriter;
import org.pgledger.application.history.Snapshot;
import org.pgledger.application.receipt.ReceiptService;
import org.pgledger.application.write.WriteResult.RecordFixResult;
import org.pgledger.database.repository.Counter;
import org.pgledger.database.repository.DefectFixRepository;
import org.pgledger.database.repository.IdAllocator;
import org.pgledger.domain.error.ConflictCode;
import org.pgledger.domain.error.ConflictException;
import org.pgledger.domain.error.TransitionCode;
import org.pgledger.domain.error.TransitionException;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationReason;
import org.pgledger.domain.model.Defect;
import org.pgledger.domain.model.DefectFix;
import org.pgledger.domain.model.ReceiptOperation;
import org.pgledger.domain.rule.CiConclusion;
import org.pgledger.domain.rule.DefectStatus;
import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.rule.HistoryOp;
import org.pgledger.domain.rule.Scope;
import org.pgledger.domain.value.ActorId;
import org.pgledger.domain.value.CanonicalPayload;
import org.pgledger.domain.value.CommitSha;
import org.pgledger.domain.value.DefectId;
import org.pgledger.domain.value.Evidence;
import org.pgledger.domain.value.TextRule;
import org.pgledger.domain.value.Tokens;
import org.pgledger.domain.value.UtcInstant;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * Records a fix commit (or new CI data of a known commit). The defect row is read first and written
 * first (by every record, also when only its version and its time of change move) with the conditional save of its version (before any counter is taken), so a race with another write of the defect
 * is decided by that statement (VERSION_CONFLICT) and the state check and the fix insert see the same defect (no write
 * skew); an OPEN defect moves to SOURCE_FIXED in the same
 * transaction unless told not to, and recording a new fix commit makes the recording session the owner.
 * Recording a fix on a MERGED defect is allowed; on a VERIFIED defect it is refused (VERIFIED_DEFECT, {@link Defect#requireNotVerified}).
 */
@Component
public class RecordFixOperation extends WriteOperation<RecordFixCommand, RecordFixResult> {

    private final Rows rows;
    private final DefectFixRepository fixes;
    private final IdAllocator ids;
    private final HistoryWriter history;
    private final AttachmentLinks attachments;
    private final Clock clock;

    public RecordFixOperation(WriteGate gate, ReceiptService receipts, Rows rows,
            DefectFixRepository fixes, IdAllocator ids, HistoryWriter history, AttachmentLinks attachments,
            Clock clock) {
        super(gate, receipts, ReceiptOperation.RECORD_FIX, RecordFixResult.class);
        this.rows = rows;
        this.fixes = fixes;
        this.ids = ids;
        this.history = history;
        this.attachments = attachments;
        this.clock = clock;
    }

    @Override
    protected Lookup resolve(RecordFixCommand c) {
        return Lookup.afterActor(() -> List.of(new Target(rows.defect(DefectId.of(c.defectId())).projectId(),
                Scope.FIX)));
    }

    @Override
    protected Prepared<RecordFixResult> prepare(RecordFixCommand c) {
        DefectId defectId = DefectId.of(c.defectId());
        String repo = TextRule.REPO.required(c.repo());
        CommitSha commit = CommitSha.of(c.commitSha());
        String branch = TextRule.BRANCH.optional(c.branch());
        String ciRef = TextRule.CI_REF.optional(c.ciRef());
        CiConclusion conclusion = c.ciConclusion() == null || c.ciConclusion().isBlank() ? null
                : Tokens.parse("ci_conclusion", c.ciConclusion(), CiConclusion.values());
        Instant completedAt = c.ciCompletedAt() == null || c.ciCompletedAt().isBlank() ? null
                : UtcInstant.parse("ci_completed_at", c.ciCompletedAt()).value();
        if (conclusion != null && ciRef == null) {
            throw ValidationException.invalid("ci_ref", ValidationReason.REQUIRED_WHEN_SET, "ci_conclusion");
        }
        boolean mark = c.markSourceFixed() == null || c.markSourceFixed();
        CanonicalPayload.Fields payload = CanonicalPayload.Fields.empty().text("defect", defectId.value())
                .text("repo", repo).text("commit", commit.value()).text("branch", branch).text("ci_ref", ciRef)
                .text("ci_conclusion", conclusion == null ? null : conclusion.name())
                .text("ci_completed_at", ChangeSet.text(completedAt)).text("mark", Boolean.toString(mark))
                .text("attachment_id", c.attachmentId());
        return new Prepared<>(payload, ctx -> execute(ctx, defectId, repo, commit, branch, ciRef, conclusion,
                completedAt, mark, c.attachmentId()));
    }

    /**
     * The attachment is on the DEFECT_FIX row (the CREATE of a new commit, the EDIT of new CI data): the main row of the
     * operation. The defect's own TRANSITION or EDIT row that the same call may write carries none.
     */
    private RecordFixResult execute(WriteContext ctx, DefectId defectId, String repo, CommitSha commit,
            String branch, String ciRef, CiConclusion conclusion, Instant completedAt, boolean mark,
            String attachment) {
        // before anything is written: a missing or foreign attachment fails the whole record (design 5)
        attachments.requireOwnedBy(attachment, ctx.author());
        Instant now = clock.instant();
        Defect defect = rows.defectForWrite(defectId, null);
        // a VERIFIED defect is changed by no fix (nor by new CI data of one): a reviewer reopens it first
        defect.requireNotVerified();
        String fixEntityId = defectId.value() + "@" + commit.value();
        ActorId author = ctx.author();
        Evidence evidence = Evidence.of(ciRef, null);
        Optional<DefectFix> known = fixes.findByDefectIdAndRepoAndCommitSha(defectId, repo, commit);
        // a commit that is recorded already keeps the owner of the defect; a new one is owned by the recording session
        ActorId nextOwner = known.isPresent() ? defect.ownerActorId() : author;
        String oldOwner = defect.ownerActorId() == null ? null : defect.ownerActorId().value();
        String newOwner = nextOwner == null ? null : nextOwner.value();
        DefectStatus status = defect.status();
        long version = defect.version();
        // The row of the defect is written first, before any counter (an id, a history row) is taken: it is the statement
        // that decides a race with another write of the same defect (VERSION_CONFLICT), and every write of a defect takes
        // its row before the counters, so two writes cannot wait for each other (principle 4.4: no lock statement).
        // What new CI data of a known commit changes is checked before anything is written (a refused amendment leaves nothing).
        ChangeSet amended = known.isPresent() ? amendedChanges(known.get(), ciRef, conclusion, completedAt) : null;
        HistoryOp defectOp = null;
        if (defect.status() == DefectStatus.OPEN && mark) {
            // a fix changes no code field (category, severity), so no code is checked: a defect that holds a retired code
            // (the UNRATED of an import) is fixed all the same
            Defect after = defect.transitioned(DefectStatus.SOURCE_FIXED, null, now).ownedBy(nextOwner, now);
            version = rows.saveDefect(after).version();
            defectOp = HistoryOp.TRANSITION;
            status = DefectStatus.SOURCE_FIXED;
        } else {
            // Also when the owner and the status stay the row is saved (updated_at and the version change): a concurrent
            // transition of the defect, a verification above all, is decided by this conditional statement, so no fix row is
            // left on a defect that was verified in between. Only a change of the owner is a change that the history records
            // (no history row, so no before_data, for the bookkeeping columns alone: it would be an empty edit row per fix).
            Defect after = defect.ownedBy(nextOwner, now);
            version = rows.saveDefect(after).version();
            if (!Objects.equals(oldOwner, newOwner)) {
                defectOp = HistoryOp.EDIT;
            }
        }
        FixWrite fix = known
                .map(row -> amendFix(ctx, row, fixEntityId, ciRef, conclusion, completedAt, evidence, amended,
                        attachment))
                .orElseGet(() -> recordFix(ctx, defectId, fixEntityId, repo, commit, branch, ciRef, conclusion,
                        completedAt, evidence, now, attachment));
        long fixId = fix.fixId();
        long historyId = fix.historyId();
        Long transitionHistoryId = null;
        if (defectOp == HistoryOp.TRANSITION) {
            ChangeSet changes = ChangeSet.empty().with("fix", null, commit.value())
                    .withIfChanged("owner_actor_id", oldOwner, newOwner);
            transitionHistoryId = history.append(ctx, HistoryWriter.Entry.of(HistoryEntity.DEFECT,
                    defectId.value(), HistoryOp.TRANSITION, changes)
                    .withStates(DefectStatus.OPEN.name(), DefectStatus.SOURCE_FIXED.name()).withEvidence(evidence)
                    .withBefore(Snapshot.DefectRow.of(defect)));
        } else if (defectOp == HistoryOp.EDIT) {
            transitionHistoryId = history.append(ctx, HistoryWriter.Entry.of(HistoryEntity.DEFECT,
                    defectId.value(), HistoryOp.EDIT, ChangeSet.empty().with("owner_actor_id", oldOwner, newOwner))
                    .withEvidence(evidence).withBefore(Snapshot.DefectRow.of(defect)));
        }
        return new RecordFixResult(WriteStatus.COMMITTED, defectId.value(), fixId, status.name(), newOwner, version,
                historyId, transitionHistoryId, author.value(), ctx.login());
    }

    /** What writing the fix row did: its id and its history row. */
    private record FixWrite(long fixId, long historyId) {
    }

    /** A commit that was not recorded yet: the fix row and its CREATE history row (the recording session owns the defect). */
    private FixWrite recordFix(WriteContext ctx, DefectId defectId, String fixEntityId, String repo, CommitSha commit,
            String branch, String ciRef, CiConclusion conclusion, Instant completedAt, Evidence evidence,
            Instant now, String attachment) {
        DefectFix fix = DefectFix.record(ids.next(Counter.DEFECT_FIX), defectId, repo, commit, branch, ciRef,
                conclusion, completedAt, ctx.author(), now);
        long fixId = inserted(() -> fixes.insert(fix), defectId.value()).id();
        ChangeSet changes = ChangeSet.empty().with("repo", null, repo).with("branch", null, branch)
                .with("ci_ref", null, ciRef).with("ci_conclusion", null, name(conclusion));
        long historyId = history.append(ctx, HistoryWriter.Entry.of(HistoryEntity.DEFECT_FIX, fixEntityId,
                HistoryOp.CREATE, changes).withEvidence(evidence).withAttachment(attachment));
        return new FixWrite(fixId, historyId);
    }

    /**
     * What new CI data changes in a commit that is recorded. Checked before anything is written, so a refused amendment
     * leaves nothing behind and the error does not depend on what the defect row would have needed.
     *
     * @throws TransitionException FIX_ALREADY_RECORDED when the call carries no CI reference; NOTHING_CHANGES when it
     *         changes nothing
     */
    private static ChangeSet amendedChanges(DefectFix known, String ciRef, CiConclusion conclusion,
            Instant completedAt) {
        if (ciRef == null) {
            throw new TransitionException(TransitionCode.FIX_ALREADY_RECORDED, List.of());
        }
        ChangeSet changes = ChangeSet.empty()
                .withIfChanged("ci_ref", known.ciRef(), ciRef)
                .withIfChanged("ci_conclusion", ChangeSet.text(name(known.ciConclusion())), name(conclusion))
                .withIfChanged("ci_completed_at", ChangeSet.text(known.ciCompletedAt()), ChangeSet.text(completedAt));
        if (changes.isEmpty()) {
            throw new TransitionException(TransitionCode.NOTHING_CHANGES, List.of());
        }
        return changes;
    }

    /** New CI data of a commit that is recorded: the fix row is updated and an EDIT row keeps the old one; the owner stays. */
    private FixWrite amendFix(WriteContext ctx, DefectFix known, String fixEntityId, String ciRef,
            CiConclusion conclusion, Instant completedAt, Evidence evidence, ChangeSet changes, String attachment) {
        fixes.update(known.withCi(ciRef, conclusion, completedAt));
        long historyId = history.append(ctx, HistoryWriter.Entry.of(HistoryEntity.DEFECT_FIX, fixEntityId,
                HistoryOp.EDIT, changes).withEvidence(evidence).withBefore(Snapshot.FixRow.of(known))
                .withAttachment(attachment));
        return new FixWrite(known.id(), historyId);
    }

    private static String name(CiConclusion conclusion) {
        return conclusion == null ? null : conclusion.name();
    }

    /** Runs the insert of a fix; a duplicate key becomes the contract's error, never a raw one. */
    private static <T> T inserted(Supplier<T> insert, String defect) {
        try {
            return insert.get();
        } catch (DuplicateKeyException e) {
            throw new ConflictException(ConflictCode.DUPLICATE_KEY, List.of("fix of " + defect), e);
        }
    }
}
