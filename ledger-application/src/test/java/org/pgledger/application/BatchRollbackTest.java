package org.pgledger.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.application.support.Expect;
import org.pgledger.application.support.Fields;
import org.pgledger.application.support.LedgerServiceTest;
import org.pgledger.application.support.Scenario;
import org.pgledger.application.write.BatchOpInput;
import org.pgledger.application.write.BatchService;
import org.pgledger.application.write.WriteResult.SaveResult;
import org.pgledger.domain.error.ConflictCode;
import org.pgledger.domain.error.LedgerException;

/** SVC-BAT-03: batch is atomic all-or-nothing on PostgreSQL. */
@LedgerServiceTest
class BatchRollbackTest {

    @Test
    @DisplayName("SVC-BAT-03: failing operation names itself and rolls back issues, history, receipts, and counters")
    void bat03_allOrNothing(Scenario s) {
        SaveResult existing = s.newIssue("existing");
        String actor = s.actorWith("issue");
        String operationKey = Scenario.uuid();
        List<BatchOpInput> ops = List.of(
                new BatchOpInput.Save("issue", Fields.of(s.issueFields("created by the batch")), Scenario.REASON, null,
                        null),
                new BatchOpInput.Transition("issue", existing.id(), 9L, "DISPUTED", Scenario.REASON,
                        Scenario.EVIDENCE, null, null, null));
        LedgerException e = Expect.failure(() -> s.bean(BatchService.class).batch(actor, operationKey, ops),
                ConflictCode.VERSION_CONFLICT);
        assertThat(e.details()).containsAllEntriesOf(Map.of("op_index", List.of("2"), "op", List.of("transition")));
        assertThat(s.count("SELECT COUNT(*) FROM issue WHERE project_id = ?", s.projectId())).isEqualTo(1);
        assertThat(s.count("SELECT COUNT(*) FROM history WHERE author_id = ?", actor)).isZero();
        assertThat(s.count("SELECT COUNT(*) FROM request_receipt WHERE author_id = ?", actor)).isZero();
        assertThat(s.newIssue("next").id()).isEqualTo(s.issueId(2));
    }
}
