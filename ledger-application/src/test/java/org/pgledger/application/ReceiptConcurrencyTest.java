package org.pgledger.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.application.support.Parallel;
import org.pgledger.application.support.Parallel.Outcome;
import org.pgledger.application.support.Fields;
import org.pgledger.application.support.Scenario;
import org.pgledger.application.support.LedgerServiceTest;
import org.pgledger.application.write.SaveCommand;
import org.pgledger.application.write.SaveService;
import org.pgledger.application.write.WriteResult.SaveResult;
import org.pgledger.domain.error.ConflictCode;
import org.pgledger.domain.error.ErrorCode;
import org.pgledger.domain.error.LedgerException;

/** SVC-RCP-09 .. SVC-RCP-11: concurrent duplicates on the PostgreSQL of the test PC, with real threads. */
@LedgerServiceTest
class ReceiptConcurrencyTest {

    private static SaveCommand create(Scenario s, String operationKey, String title) {
        return new SaveCommand(operationKey,"defect", null, 0L, Fields.of(s.defectFields(title)), Scenario.REASON);
    }

    private static Optional<ErrorCode> code(Outcome<?> outcome) {
        return Optional.ofNullable(outcome.failure()).map(e -> ((LedgerException) e).code());
    }

    @Test
    @DisplayName("SVC-RCP-09 (principle 4.4): 16 threads with the same operation key commit exactly once: every call that succeeds sees the one response (a replay), every other call ends with the translated DUPLICATE_KEY (it met the unique key of the operation key at the same moment), never a raw exception")
    void rcp09_sameRequestSamePayload(Scenario s) {
        String id = Scenario.uuid();
        List<Callable<SaveResult>> calls = IntStream.range(0, 16)
                .<Callable<SaveResult>>mapToObj(i -> () -> s.bean(SaveService.class).save(Scenario.AUTHOR,
                        create(s, id, "same")))
                .toList();
        List<Outcome<SaveResult>> outcomes = Parallel.all(calls);
        assertThat(outcomes).allSatisfy(o -> assertThat(code(o)).isIn(Optional.empty(),
                Optional.of(ConflictCode.DUPLICATE_KEY)));
        assertThat(outcomes.stream().filter(Outcome::succeeded)).as("at least the call that committed").isNotEmpty();
        assertThat(outcomes.stream().filter(Outcome::succeeded).map(Outcome::value).distinct()).hasSize(1);
        assertThat(s.count("SELECT COUNT(*) FROM defect WHERE project_id = ?", s.projectId())).isEqualTo(1);
        assertThat(s.count("SELECT COUNT(*) FROM history WHERE operation_key = ?", id)).isEqualTo(1);
        assertThat(s.count("SELECT COUNT(*) FROM request_receipt WHERE operation_key = ?", id)).isEqualTo(1);
    }

    @Test
    @DisplayName("SVC-RCP-10 (principle 4.4; one author, because the receipts are per author, OPK-020): 16 threads, 4 payloads: one payload wins; a call with another payload ends with OPERATION_CONFLICT (it read the receipt) or DUPLICATE_KEY (it met the unique key at the same moment); a call with the winning payload answers the stored response or ends with DUPLICATE_KEY")
    void rcp10_sameRequestFourPayloads(Scenario s) {
        String id = Scenario.uuid();
        List<String> titles = IntStream.range(0, 16).mapToObj(i -> "payload " + (i % 4)).toList();
        List<Callable<SaveResult>> calls = new ArrayList<>();
        for (String title : titles) {
            calls.add(() -> s.bean(SaveService.class).save(Scenario.AUTHOR, create(s, id, title)));
        }
        List<Outcome<SaveResult>> outcomes = Parallel.all(calls);
        SaveResult winner = outcomes.stream().filter(Outcome::succeeded).findFirst().orElseThrow().value();
        String winningTitle = s.defect(winner.id()).title();
        assertThat(s.count("SELECT COUNT(*) FROM defect WHERE project_id = ?", s.projectId())).isEqualTo(1);
        for (int i = 0; i < titles.size(); i++) {
            Optional<ErrorCode> code = code(outcomes.get(i));
            if (Objects.equals(titles.get(i), winningTitle)) {
                assertThat(code).as("payload of the winner").isIn(Optional.empty(), Optional.of(ConflictCode.DUPLICATE_KEY));
            } else {
                assertThat(code).as("another payload").isIn(Optional.of(ConflictCode.OPERATION_CONFLICT),
                        Optional.of(ConflictCode.DUPLICATE_KEY));
            }
        }
        assertThat(outcomes.stream().filter(Outcome::succeeded).map(Outcome::value).distinct()).hasSize(1);
    }

    @Test
    @DisplayName("SVC-RCP-11: after the stress and 200 mixed writes every receipt is complete")
    void rcp11_everyReceiptIsComplete(Scenario s) {
        String actor = s.actorWith("defect", "task");
        String id = Scenario.uuid();
        List<Callable<Object>> calls = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            int n = i % 4;
            calls.add(() -> s.bean(SaveService.class).save(actor, create(s, id, "stress " + n)));
        }
        for (int i = 0; i < 200; i++) {
            int n = i;
            calls.add(() -> n % 2 == 0
                    ? s.bean(SaveService.class).save(actor, create(s, Scenario.uuid(), "mixed " + n))
                    : s.bean(SaveService.class).save(actor, new SaveCommand(Scenario.uuid(), "task", null, 0L,
                            Fields.of(s.taskFields("mixed " + n)), Scenario.REASON)));
        }
        List<Outcome<Object>> outcomes = Parallel.all(calls, 12);
        // a stress write may lose the race of its operation key: that is a business failure, anything else is not
        assertThat(outcomes).allSatisfy(o -> assertThat(o.failure()).satisfiesAnyOf(
                failure -> assertThat(failure).isNull(),
                failure -> assertThat(failure).isInstanceOf(LedgerException.class)));
        String mine = "SELECT COUNT(*) FROM request_receipt WHERE author_id = ?";
        assertThat(s.count(mine, actor)).isEqualTo(201);
        assertThat(s.count(mine + " AND response IS NULL", actor)).isZero();
        assertThat(s.count(mine + " AND CHAR_LENGTH(payload_sha256) = 64", actor)).isEqualTo(201);
        assertThat(s.count(mine + " AND operation = 'SAVE' AND login = 'ledger_author'", actor)).isEqualTo(201);
        assertThat(s.count(mine + " AND created_at = TIMESTAMP '2026-11-05 10:00:00'", actor)).isEqualTo(201);
    }
}
