// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.agentledger.application.history.HistoryKey;
import org.agentledger.application.history.Snapshot;
import org.agentledger.application.support.HeldTransaction;
import org.agentledger.application.support.LedgerServiceTest;
import org.agentledger.application.support.Parallel;
import org.agentledger.application.support.Scenario;
import org.agentledger.application.write.RecordDeliverableCommand;
import org.agentledger.application.write.RecordDeliverableService;
import org.agentledger.domain.model.HistoryEntry;
import org.agentledger.domain.rule.HistoryEntity;
import org.agentledger.domain.rule.HistoryOp;
import org.agentledger.testing.database.LockWaits;

/**
 * R9-D0054: an amendment of the check facts of a deliverable works on the row as it is when the target entity is locked,
 * not on the row that was read before the lock. The two amendments meet deterministically: a second connection holds
 * the lock of the target (it amends first and stays open), the amendment under test reads, waits for the lock in
 * {@code pg_stat_activity}, and goes on only when the first has committed.
 */
@LedgerServiceTest
class DeliverableStaleReadTest {

    private static RecordDeliverableCommand amend(String entity, String id, String ref, String checkRef,
            String result) {
        return new RecordDeliverableCommand(Scenario.uuid(), entity, id, "code_change", ref, Scenario.REASON,
                "repo/e2e", "Deliverable summary", checkRef, result, null, Boolean.FALSE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"spec", "task"})
    @DisplayName("R9-D0054: the second amendment starts from the check facts the first one committed while it waited for the target")
    void theSecondAmendmentUsesTheRowOfTheFirst(String entity, Scenario s) throws Exception {
        String id = "spec".equals(entity) ? s.newSpec("s").id() : s.newTask("t").id();
        String ref = Scenario.ref('r');
        var service = s.bean(RecordDeliverableService.class);
        service.recordDeliverable(Scenario.AUTHOR, amend(entity, id, ref, null, null));

        HeldTransaction<Object> first = HeldTransaction.start(s.ctx(),
                () -> service.recordDeliverable(Scenario.AUTHOR, amend(entity, id, ref, "ci/1", "failed")));
        int holder = first.awaitHeld();
        Future<?> second = Parallel.start(
                () -> service.recordDeliverable(Scenario.AUTHOR, amend(entity, id, ref, "ci/2", "passed")));
        LockWaits.awaitWaitingFor(s.ctx().jdbc(), holder);
        first.release();
        second.get(60, TimeUnit.SECONDS);

        String key = HistoryKey.deliverableEntityId(entity, id, "code_change", ref);
        List<HistoryEntry> rows = s.history(HistoryEntity.DELIVERABLE, key);
        assertThat(rows).extracting(HistoryEntry::op).containsExactly(HistoryOp.CREATE, HistoryOp.EDIT,
                HistoryOp.EDIT);
        HistoryEntry last = rows.getLast();
        assertThat(s.changes(last.id()).entries().get("check_ref"))
                .as("the change of the second amendment starts from the check_ref the first one wrote")
                .satisfies(change -> {
                    assertThat(change.before()).isEqualTo("ci/1");
                    assertThat(change.after()).isEqualTo("ci/2");
                });
        assertThat(s.before(last.id(), Snapshot.DeliverableRow.class).checkRef())
                .as("the snapshot before the second amendment is the row the first one wrote").isEqualTo("ci/1");
        assertThat(s.before(last.id(), Snapshot.DeliverableRow.class).checkResult()).isEqualTo("FAILED");
    }
}
