// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application.history;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.veridger.application.json.LedgerJson;
import org.veridger.application.write.WriteContext;
import org.veridger.database.repository.Counter;
import org.veridger.database.repository.HistoryRepository;
import org.veridger.database.repository.IdAllocator;
import org.veridger.domain.model.HistoryEntry;
import org.veridger.domain.rule.ActorAssurance;
import org.veridger.domain.rule.HistoryEntity;
import org.veridger.domain.rule.HistoryOp;
import org.veridger.domain.value.Evidence;
import org.springframework.stereotype.Component;

/**
 * The only writer of history rows, and the only place that turns a replaced row into {@code before_data}. A row is
 * inserted in the transaction of the write; there is no update and no delete anywhere.
 */
@Component
public class HistoryWriter {

    /** What one history row says; the author, login, time, reason and operation key come from the write context. */
    public record Entry(HistoryEntity entity, String entityId, HistoryOp op, String fromState, String toState,
            ChangeSet changes, Snapshot before, Evidence evidence, Long correctsId, String attachmentId) {

        public static Entry of(HistoryEntity entity, String entityId, HistoryOp op, ChangeSet changes) {
            return new Entry(entity, entityId, op, null, null, changes, null, Evidence.NONE, null, null);
        }

        public Entry withBefore(Snapshot snapshot) {
            return new Entry(entity, entityId, op, fromState, toState, changes, snapshot, evidence, correctsId,
                    attachmentId);
        }

        public Entry withEvidence(Evidence value) {
            return new Entry(entity, entityId, op, fromState, toState, changes, before, value, correctsId,
                    attachmentId);
        }

        public Entry withStates(String from, String to) {
            return new Entry(entity, entityId, op, from, to, changes, before, evidence, correctsId, attachmentId);
        }

        public Entry correcting(long historyId) {
            return new Entry(entity, entityId, op, fromState, toState, changes, before, evidence, historyId,
                    attachmentId);
        }

        /** The row points to this attachment (a plain id, no foreign key); the write has checked it, null means none. */
        public Entry withAttachment(String id) {
            return new Entry(entity, entityId, op, fromState, toState, changes, before, evidence, correctsId, id);
        }
    }

    private final HistoryRepository history;
    private final IdAllocator ids;
    private final LedgerJson json;
    private final Clock clock;

    public HistoryWriter(HistoryRepository history, IdAllocator ids, LedgerJson json, Clock clock) {
        this.history = history;
        this.ids = ids;
        this.json = json;
        this.clock = clock;
    }

    /** One context/entry pair to persist in a batch flush. */
    public record BatchItem(WriteContext context, Entry entry) {
    }

    /** Appends the row and returns its history id. */
    public long append(WriteContext context, Entry entry) {
        long id = ids.next(Counter.HISTORY);
        return insertRow(id, clock.instant(), context, entry);
    }

    /**
     * Appends multiple history rows in order, reserving their contiguous ID block in a single {@code id_counter}
     * update+select pair instead of two round-trips per row.
     */
    public long[] appendAll(List<BatchItem> items) {
        if (items.isEmpty()) {
            return new long[0];
        }
        long firstId = ids.reserveRange(Counter.HISTORY, items.size());
        long[] assigned = new long[items.size()];
        for (int i = 0; i < items.size(); i++) {
            BatchItem item = items.get(i);
            assigned[i] = insertRow(firstId + i, clock.instant(), item.context(), item.entry());
        }
        return assigned;
    }

    private long insertRow(long id, Instant at, WriteContext context, Entry entry) {
        HistoryEntry row = new HistoryEntry(id, at, context.author(), context.login(),
                ActorAssurance.DECLARED, entry.entity(), entry.entityId(), entry.op(), entry.fromState(),
                entry.toState(), json.write(entry.changes()),
                entry.before() == null ? null : json.write(entry.before()), context.reason().text(),
                entry.evidence().uri(), entry.evidence().sha256(), entry.correctsId(), context.operationKey(), null,
                entry.attachmentId());
        return history.insert(row).id();
    }
}
