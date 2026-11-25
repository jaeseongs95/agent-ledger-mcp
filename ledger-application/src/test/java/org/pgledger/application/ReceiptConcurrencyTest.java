// SPDX-License-Identifier: AGPL-3.0-or-later

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
import org.pgledger.application.support.Fields;
import org.pgledger.application.support.LedgerServiceTest;
import org.pgledger.application.support.Parallel;
import org.pgledger.application.support.Parallel.Outcome;
import org.pgledger.application.support.Scenario;
import org.pgledger.application.write.SaveCommand;
import org.pgledger.application.write.SaveService;
import org.pgledger.application.write.WriteResult.SaveResult;
import org.pgledger.domain.error.ConflictCode;
import org.pgledger.domain.error.ErrorCode;
import org.pgledger.domain.error.LedgerException;

/** SVC-RCP-09 .. SVC-RCP-11: concurrent duplicates on PostgreSQL with real threads. */
@LedgerServiceTest
class ReceiptConcurrencyTest {

    private static SaveCommand create(Scenario s, String operationKey, String title) {
        return new SaveCommand(operationKey, "issue", null, 0L, Fields.of(s.issueFields(title)), Scenario.REASON);
    }

    private static Optional<ErrorCode> code(Outcome<?> outcome) {
        return Optional.ofNullable(outcome.failure()).map(e -> ((LedgerException) e).code());
    }

    @Test
    @DisplayName("SVC-RCP-09: 16 threads with the same operation key commit once and replay or translate DUPLICATE_KEY")
    void rcp09_sameRequestSamePayload(Scenario s) {
        String id = Scenario.uuid();
        List<Callable<SaveResult>> calls = IntStream.range(0, 16)
                .<Callable<SaveResult>>mapToObj(i -> () -> s.bean(SaveService.class).save(Scenario.AUTHOR,
                        create(s, id, "same")))
                .toList();
        List<Outcome<SaveResult>> outcomes = Parallel.all(calls);
        assertThat(outcomes).allSatisfy(o -> assertThat(code(o)).isIn(Optional.empty(),
                Optional.of(ConflictCode.DUPLICATE_KEY)));
        assertThat(outcomes.stream().filter(Outcome::succeeded)).isNotEmpty();
        assertThat(outcomes.stream().filter(Outcome::succeeded).map(Outcome::value).distinct()).hasSize(1);
        assertThat(s.count("SELECT COUNT(*) FROM issue WHERE project_id = ?", s.projectId())).isEqualTo(1);
        assertThat(s.count("SELECT COUNT(*) FROM history WHERE operation_key = ?", id)).isEqualTo(1);
        assertThat(s.count("SELECT COUNT(*) FROM request_receipt WHERE operation_key = ?", id)).isEqualTo(1);
    }

    @Test
    @DisplayName("SVC-RCP-10: 16 threads with 4 payloads under one operation key")
    void rcp10_sameRequestFourPayloads(Scenario s) {
        String id = Scenario.uuid();
        List<String> titles = IntStream.range(0, 16).mapToObj(i -> "payload " + (i % 4)).toList();
        List<Callable<SaveResult>> calls = new ArrayList<>();
        for (String title : titles) {
            calls.add(() -> s.bean(SaveService.class).save(Scenario.AUTHOR, create(s, id, title)));
        }
        List<Outcome<SaveResult>> outcomes = Parallel.all(calls);
        SaveResult winner = outcomes.stream().filter(Outcome::succeeded).findFirst().orElseThrow().value();
        String winningTitle = s.issue(winner.id()).title();
        assertThat(s.count("SELECT COUNT(*) FROM issue WHERE project_id = ?", s.projectId())).isEqualTo(1);
        for (int i = 0; i < titles.size(); i++) {
            Optional<ErrorCode> code = code(outcomes.get(i));
            if (Objects.equals(titles.get(i), winningTitle)) {
                assertThat(code).isIn(Optional.empty(), Optional.of(ConflictCode.DUPLICATE_KEY));
            } else {
                assertThat(code).isIn(Optional.of(ConflictCode.OPERATION_CONFLICT),
                        Optional.of(ConflictCode.DUPLICATE_KEY));
            }
        }
        assertThat(outcomes.stream().filter(Outcome::succeeded).map(Outcome::value).distinct()).hasSize(1);
    }

    @Test
    @DisplayName("SVC-RCP-11: after stress and 200 mixed writes every receipt is complete")
    void rcp11_everyReceiptIsComplete(Scenario s) {
        String actor = s.actorWith("issue", "task");
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
        assertThat(outcomes).allSatisfy(o -> assertThat(o.failure()).satisfiesAnyOf(
                failure -> assertThat(failure).isNull(),
                failure -> assertThat(failure).isInstanceOf(LedgerException.class)));
        assertThat(s.count("SELECT COUNT(*) FROM request_receipt WHERE response IS NULL")).isZero();
    }
}
