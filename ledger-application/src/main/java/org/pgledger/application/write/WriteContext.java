// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.application.write;

import java.util.Collection;
import java.util.List;
import org.pgledger.application.gate.GateSession;
import org.pgledger.application.gate.Target;
import org.pgledger.application.gate.WriteGate;
import org.pgledger.application.history.HistoryWriter;
import org.pgledger.database.repository.CommonCodeRepository;
import org.pgledger.domain.error.ConflictCode;
import org.pgledger.domain.error.ConflictException;
import org.pgledger.domain.rule.CodeDirection;
import org.pgledger.domain.rule.CodeGroup;
import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.value.AuthorId;
import org.pgledger.domain.value.CodeValue;
import org.pgledger.domain.value.CriterionId;
import org.pgledger.domain.value.IssueId;
import org.pgledger.domain.value.OperationKey;
import org.pgledger.domain.value.ProjectId;
import org.pgledger.domain.value.Reason;
import org.pgledger.domain.value.SpecId;
import org.pgledger.domain.value.TaskId;

/**
 * What the body of a write knows: the trusted session, the validated reason, the operation key, and an optional
 * {@link BatchBuffer} when running inside {@link BatchService#batch}.
 */
public record WriteContext(GateSession session, Reason reason, OperationKey operationKey, BatchBuffer batch) {

    public WriteContext(GateSession session, Reason reason, OperationKey operationKey) {
        this(session, reason, operationKey, null);
    }

    public AuthorId author() {
        return session.author();
    }

    public String login() {
        return session.channel().login();
    }

    boolean isBatched() {
        return batch != null;
    }

    void pinProject(Rows rows, ProjectId id) {
        if (batch == null) {
            rows.pinProject(id);
        } else if (!batch.hasPinnedProject(id)) {
            throw new ConflictException(ConflictCode.CONCURRENT_MODIFICATION, List.of("project", id.value()));
        }
    }

    void requirePinnedIssue(IssueId id) {
        if (batch != null && !batch.hasPinnedIssue(id)) {
            throw new ConflictException(ConflictCode.CONCURRENT_MODIFICATION,
                    List.of(HistoryEntity.ISSUE.token(), id.value()));
        }
    }

    ProjectId pinSpec(Rows rows, SpecId id) {
        if (batch == null) {
            return rows.pinSpec(id);
        }
        if (!batch.hasPinnedSpec(id)) {
            throw new ConflictException(ConflictCode.CONCURRENT_MODIFICATION,
                    List.of(HistoryEntity.SPEC.token(), id.value()));
        }
        return rows.spec(id).projectId();
    }

    ProjectId pinTask(Rows rows, TaskId id) {
        if (batch == null) {
            return rows.pinTask(id);
        }
        if (!batch.hasPinnedTask(id)) {
            throw new ConflictException(ConflictCode.CONCURRENT_MODIFICATION,
                    List.of(HistoryEntity.TASK.token(), id.value()));
        }
        return rows.task(id).projectId();
    }

    ProjectId pinCriterion(Rows rows, CriterionId id) {
        if (batch == null) {
            return rows.pinCriterion(id);
        }
        if (!batch.hasPinnedCriterion(id)) {
            throw new ConflictException(ConflictCode.CONCURRENT_MODIFICATION,
                    List.of(HistoryEntity.CRITERION.token(), id.value()));
        }
        return rows.criterion(id).projectId();
    }

    void markCreatedIssue(IssueId id) {
        if (batch != null) {
            batch.markPinnedIssue(id);
        }
    }

    void markCreatedSpec(SpecId id) {
        if (batch != null) {
            batch.markPinnedSpec(id);
        }
    }

    void markCreatedTask(TaskId id) {
        if (batch != null) {
            batch.markPinnedTask(id);
        }
    }

    void markCreatedCriterion(CriterionId id) {
        if (batch != null) {
            batch.markPinnedCriterion(id);
        }
    }

    /**
     * Authorizes {@code targets} against {@code session_grant}. For single writes, acquires and stamps the grants in
     * canonical ascending order via {@link WriteGate#authorizeAll}. Inside a batch, verifies every target was already
     * locked in Phase 1.5 (refusing missing grants with {@code SESSION_NOT_GRANTED} and never acquiring out-of-order
     * {@code session_grant} locks during Phase 2).
     */
    void authorize(WriteGate gate, Collection<Target> targets) {
        if (batch == null) {
            gate.authorizeAll(author(), targets);
            return;
        }
        for (Target target : targets) {
            if (!batch.isAuthorized(target)) {
                gate.requireLive(author(), target);
                throw new ConflictException(ConflictCode.CONCURRENT_MODIFICATION,
                        List.of("session_grant", target.project().value() + ":" + target.scope().token()));
            }
        }
    }

    /**
     * Holds the code of the relation kind in the direction the caller read (see {@link CommonCodeRepository#holdDirection}),
     * after the entities and the grants and before the first number: the last lock of the order of {@code LockOrder}
     * except the numbers. Inside a batch the entry was held in Phase 1.6 (in the order of the codes), never here.
     *
     * @throws ConflictException CONCURRENT_MODIFICATION when the direction is another one now
     */
    void holdRelationDirection(CommonCodeRepository codes, String kind, CodeDirection direction) {
        boolean held = batch == null
                ? codes.holdDirection(CodeGroup.RELATION_KIND, CodeValue.of(kind), direction)
                : batch.hasHeldRelationKind(kind, direction);
        if (!held) {
            throw new ConflictException(ConflictCode.CONCURRENT_MODIFICATION, List.of("common_code", kind));
        }
    }

    /** Appends history immediately for a single write, or buffers it for canonical Tier 9 flush in a batch. */
    long appendHistory(HistoryWriter writer, HistoryWriter.Entry entry) {
        return batch == null ? writer.append(this, entry) : batch.deferHistory(this, entry);
    }
}
