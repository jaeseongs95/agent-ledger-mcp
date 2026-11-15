package org.pgledger.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.application.history.Snapshot;
import org.pgledger.application.support.Expect;
import org.pgledger.application.support.Fields;
import org.pgledger.application.support.LedgerServiceTest;
import org.pgledger.application.support.Scenario;
import org.pgledger.application.write.BatchOpInput;
import org.pgledger.application.write.BatchResult;
import org.pgledger.application.write.BatchService;
import org.pgledger.application.write.SaveCommand;
import org.pgledger.application.write.SaveService;
import org.pgledger.application.write.WriteResult;
import org.pgledger.application.write.WriteResult.LinkResult;
import org.pgledger.application.write.WriteResult.SaveResult;
import org.pgledger.domain.error.ConflictCode;
import org.pgledger.domain.error.ErrorCode;
import org.pgledger.domain.error.LedgerException;
import org.pgledger.domain.error.TrustCode;
import org.pgledger.domain.error.ValidationCode;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.value.OperationKey;
import tools.jackson.databind.json.JsonMapper;

/** SVC-BAT-01, -02, -04 .. -12 (SVC-BAT-03 needs both databases and is in the rollback test). */
@LedgerServiceTest
class BatchTest {

    private static final JsonMapper JSON = new JsonMapper();

    private BatchOpInput.Save saveDefect(Scenario s, String title) {
        return new BatchOpInput.Save("defect", Fields.of(s.defectFields(title)), Scenario.REASON, null, null);
    }

    private BatchResult batch(Scenario s, String actor, String operationKey, List<BatchOpInput> ops) {
        return s.bean(BatchService.class).batch(actor, operationKey, ops);
    }

    @Test
    @DisplayName("SVC-BAT-01: a later operation sees what an earlier one wrote")
    void bat01_sequence(Scenario s) {
        s.newDefect("one");
        s.newDefect("two");
        BatchResult result = batch(s, Scenario.AUTHOR, Scenario.uuid(), List.of(saveDefect(s, "three"),
                saveDefect(s, "four"),
                new BatchOpInput.Link("same_family", s.defectId(3), s.defectId(4), Scenario.REASON, null)));
        assertThat(result.count()).isEqualTo(3);
        assertThat(((SaveResult) result.results().get(0)).id()).isEqualTo(s.defectId(3));
        assertThat(((SaveResult) result.results().get(1)).id()).isEqualTo(s.defectId(4));
        assertThat(((LinkResult) result.results().get(2)).entity()).isEqualTo("defect_relation");
    }

    @Test
    @DisplayName("SVC-BAT-04: a session without grants")
    void bat04_noGrant(Scenario s) {
        Expect.failure(() -> batch(s, Scenario.NOGRANT, Scenario.uuid(), List.of(saveDefect(s, "x"))),
                TrustCode.SESSION_NOT_GRANTED);
    }

    // ---------------------------------------------------------------------- 06

    private static Map<String, Object> save(Scenario s, String key, Object value) {
        Map<String, Object> op = new java.util.LinkedHashMap<>();
        op.put("op", "save");
        op.put("entity", "defect");
        op.put("fields", s.defectFields("x"));
        op.put("reason", Scenario.REASON);
        if (key != null) {
            op.put(key, value);
        }
        return op;
    }

    @Test
    @DisplayName("SVC-BAT-06: an unknown key names its path and nothing is written")
    void bat06_unknownKey(Scenario s) {
        assertRefused(s, List.of(save(s, "titel", "x")), "ops[0].titel");
    }

    @Test
    @DisplayName("SVC-BAT-06: a missing expected_version names its path")
    void bat06_missingExpectedVersion(Scenario s) {
        SaveResult d = s.newDefect("d");
        assertRefused(s, List.of(Map.of("op", "transition", "entity", "defect", "id", d.id(), "to", "DISPUTED",
                "reason", Scenario.REASON)), "ops[0].expected_version");
    }

    @Test
    @DisplayName("SVC-BAT-06: a missing reason in the second operation rolls the first back")
    void bat06_missingReasonLater(Scenario s) {
        Map<String, Object> noReason = save(s, null, null);
        noReason.remove("reason");
        assertRefused(s, List.of(save(s, null, null), noReason), "ops[1].reason");
    }

    @Test
    @DisplayName("SVC-BAT-06: an unknown op")
    void bat06_unknownOp(Scenario s) {
        assertRefused(s, List.of(Map.of("op", "review", "entity", "defect")), "ops[0]");
    }

    @Test
    @DisplayName("SVC-BAT-06: an operation without op")
    void bat06_noOp(Scenario s) {
        assertRefused(s, List.of(Map.of("entity", "defect")), "ops[0]");
    }

    private void assertRefused(Scenario s, List<Map<String, Object>> ops, String path) {
        String json = JSON.writeValueAsString(ops);
        long defects = s.count("SELECT COUNT(*) FROM defect WHERE project_id = ?", s.projectId());
        ValidationException e = (ValidationException) Expect.failure(() -> s.bean(BatchService.class)
                .batch(Scenario.AUTHOR, Scenario.uuid(), json), ValidationCode.VALIDATION_FAILED);
        assertThat(e.issues().get(0).path()).startsWith(path);
        assertThat(s.count("SELECT COUNT(*) FROM defect WHERE project_id = ?", s.projectId())).isEqualTo(defects);
    }

    // ---------------------------------------------------------------------- 07

    /** A batch of n operations: accepted or refused. */
    record Size(int count, ErrorCode expected) {
        @Override
        public String toString() {
            return count + " operations";
        }
    }

    static Stream<Size> sizes() {
        return Stream.of(new Size(0, ValidationCode.VALIDATION_FAILED), new Size(1, null), new Size(200, null),
                new Size(201, ValidationCode.VALIDATION_FAILED));
    }

    @ParameterizedTest
    @MethodSource("sizes")
    @DisplayName("SVC-BAT-07: 1 to 200 operations")
    void bat07_sizes(Size size, Scenario s) {
        List<Map<String, Object>> ops = IntStream.range(0, size.count()).mapToObj(i -> save(s, null, null)).toList();
        String json = JSON.writeValueAsString(ops);
        assertThat(Expect.outcome(() -> s.bean(BatchService.class).batch(Scenario.AUTHOR, Scenario.uuid(), json)))
                .isEqualTo(Optional.ofNullable(size.expected()));
    }

    @Test
    @DisplayName("SVC-BAT-07: ops that is not an array")
    void bat07_notAnArray(Scenario s) {
        String json = JSON.writeValueAsString(Map.of("op", "save"));
        ValidationException e = (ValidationException) Expect.failure(() -> s.bean(BatchService.class).batch(
                Scenario.AUTHOR, Scenario.uuid(), json), ValidationCode.VALIDATION_FAILED);
        assertThat(e.issues().get(0).path()).startsWith("ops");
    }

    // ---------------------------------------------------------------------- 08

    @Test
    @DisplayName("SVC-BAT-08 (OPK-005): an operation of a batch has no key of its own: operation i runs under the key derived from the key of the batch and its position, and that derived key is bound to the operation (changed: an operation used to be able to carry its own request id)")
    void bat08_derivedKeys(Scenario s) {
        String batchId = Scenario.uuid();
        OperationKey batchKey = OperationKey.parse(batchId);
        BatchResult first = batch(s, Scenario.AUTHOR, batchId, List.of(saveDefect(s, "zero"), saveDefect(s, "one")));
        // the receipts of the operations are under the derived keys, none under the key of the batch itself
        assertThat(s.count("SELECT COUNT(*) FROM request_receipt WHERE operation_key = ?", batchId)).isZero();
        assertThat(s.count("SELECT COUNT(*) FROM request_receipt WHERE operation_key IN (?, ?)",
                batchKey.derive(0).toString(), batchKey.derive(1).toString())).isEqualTo(2);
        // a direct write with the derived key and the same content is the replay of the operation
        for (int i = 0; i < 2; i++) {
            String title = i == 0 ? "zero" : "one";
            SaveResult byDerivedKey = s.bean(SaveService.class).save(Scenario.AUTHOR, new SaveCommand(
                    batchKey.derive(i).toString(), "defect", null, 0L, Fields.of(s.defectFields(title)),
                    Scenario.REASON));
            assertThat(byDerivedKey).isEqualTo(first.results().get(i));
        }
        // with other content it is a conflict
        Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR, new SaveCommand(
                batchKey.derive(0).toString(), "defect", null, 0L, Fields.of(s.defectFields("other")), Scenario.REASON)),
                ConflictCode.OPERATION_CONFLICT);
        assertThat(s.count("SELECT COUNT(*) FROM defect WHERE project_id = ?", s.projectId())).isEqualTo(2);
    }

    @Test
    @DisplayName("OPK-005 (service): one operation key for the batch: the batch key sent again returns the stored results and writes once; the history row of operation i carries the key derived for i, and the derived keys differ per position and per batch key")
    void opk005_oneKeyForTheBatch(Scenario s) {
        String batchId = Scenario.uuid();
        OperationKey batchKey = OperationKey.parse(batchId);
        List<BatchOpInput> ops = List.of(saveDefect(s, "one"), saveDefect(s, "two"), saveDefect(s, "three"));
        BatchResult first = batch(s, Scenario.AUTHOR, batchId, ops);
        long history = s.count("SELECT COUNT(*) FROM history");
        long receipts = s.count("SELECT COUNT(*) FROM request_receipt");

        assertThat(batch(s, Scenario.AUTHOR, batchId, ops)).isEqualTo(first);

        assertThat(s.count("SELECT COUNT(*) FROM history")).isEqualTo(history);
        assertThat(s.count("SELECT COUNT(*) FROM request_receipt")).isEqualTo(receipts);
        assertThat(s.count("SELECT COUNT(*) FROM defect WHERE project_id = ?", s.projectId())).isEqualTo(3);
        for (int i = 0; i < ops.size(); i++) {
            SaveResult written = (SaveResult) first.results().get(i);
            assertThat(s.historyRow(written.historyId()).operationKey()).isEqualTo(batchKey.derive(i));
        }
        // deterministic by position, different per position and per batch key
        assertThat(batchKey.derive(1)).isEqualTo(OperationKey.parse(batchId).derive(1));
        assertThat(batchKey.derive(0)).isNotEqualTo(batchKey.derive(1)).isNotEqualTo(batchKey);
        assertThat(batchKey.derive(0)).isNotEqualTo(OperationKey.parse(Scenario.uuid()).derive(0));
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"operationKey", "request_id", "requestId"})
    @DisplayName("OPK-005 (service): an operation of a batch carries no key: an operationKey or request id inside an op is an unknown key at ops[0].<key> and nothing is written")
    void opk005_noKeyInAnOperation(String name, Scenario s) {
        assertRefused(s, List.of(save(s, name, Scenario.uuid())), "ops[0]." + name);
    }

    // ---------------------------------------------------------------------- 09

    @Test
    @DisplayName("SVC-BAT-09: a batch of 8 operations of several kinds")
    void bat09_eightOperations(Scenario s) {
        SaveResult d = s.newDefect("d");
        String t1 = s.taskId(1);
        String t2 = s.taskId(2);
        String c1 = s.conditionId("1");
        String c2 = s.conditionId("2");
        BatchResult result = batch(s, Scenario.AUTHOR, Scenario.uuid(), List.of(
                new BatchOpInput.Save("task", Fields.of(s.taskFields("one")), Scenario.REASON, t1, null),
                new BatchOpInput.Save("condition", Fields.of(s.conditionFields("r", "one")), Scenario.REASON, c1, null),
                new BatchOpInput.Link("defect_task", d.id(), t1, Scenario.REASON, null),
                new BatchOpInput.Link("condition_task", c1, t1, Scenario.REASON, null),
                new BatchOpInput.Link("condition_defect", c1, d.id(), Scenario.REASON, null),
                new BatchOpInput.Save("task", Fields.of(s.taskFields("two")), Scenario.REASON, t2, null),
                new BatchOpInput.Save("condition", Fields.of(s.conditionFields("r", "two")), Scenario.REASON, c2, null),
                new BatchOpInput.Link("condition_task", c2, t2, Scenario.REASON, null)));
        assertThat(result.count()).isEqualTo(8);
    }

    // ---------------------------------------------------------------------- 10

    @Test
    @DisplayName("SVC-BAT-10: each operation passes the gate of its own tool")
    void bat10_gatesPerOperation(Scenario s) {
        SaveResult d = s.newDefect("d");
        List<BatchOpInput> ops = List.of(
                new BatchOpInput.RecordFix(d.id(), "repo/e2e", Scenario.commit('a'), Scenario.REASON, null, null, null,
                        null, null, null),
                new BatchOpInput.RecordCase(d.id(), "seen", Scenario.REASON, null, null, null, null),
                new BatchOpInput.Correct(d.historyId(), Scenario.REASON, null));
        String fixOnly = s.actorWith("fix");
        LedgerException e = Expect.failure(() -> batch(s, fixOnly, Scenario.uuid(), ops), TrustCode.SESSION_NOT_GRANTED);
        assertThat(e.details()).containsEntry("op_index", List.of("2")).containsEntry("scope", List.of("defect"));
        assertThat(s.count("SELECT COUNT(*) FROM defect_fix WHERE defect_id = ?", d.id())).isZero();
        String enough = s.actorWith("fix", "defect");
        assertThat(batch(s, enough, Scenario.uuid(), ops).count()).isEqualTo(3);
    }

    // ---------------------------------------------------------------------- 11

    @Test
    @DisplayName("SVC-BAT-11 (OPK-004): the same batch operation key with other operations is a conflict")
    void bat11_conflict(Scenario s) {
        String id = Scenario.uuid();
        batch(s, Scenario.AUTHOR, id, List.of(saveDefect(s, "one")));
        Expect.failure(() -> batch(s, Scenario.AUTHOR, id, List.of(saveDefect(s, "something else"))),
                ConflictCode.OPERATION_CONFLICT);
    }

    // ---------------------------------------------------------------------- 12

    @Test
    @DisplayName("SVC-BAT-12: two edits of one row: the second records the state the first left")
    void bat12_sameRowTwice(Scenario s) {
        SaveResult d = s.newDefect("d");
        BatchResult result = batch(s, Scenario.AUTHOR, Scenario.uuid(), List.of(
                new BatchOpInput.Save("defect", Fields.of("title", "x1"),Scenario.REASON, d.id(), 1L),
                new BatchOpInput.Save("defect", Fields.of("title", "x2"),Scenario.REASON, d.id(), 2L)));
        SaveResult second = (SaveResult) result.results().get(1);
        assertThat(second.version()).isEqualTo(3);
        Snapshot.DefectRow before = s.before(second.historyId(), Snapshot.DefectRow.class);
        assertThat(before.title()).isEqualTo("x1");
        assertThat(before.version()).isEqualTo(2);
    }
}
