package org.pgledger.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
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

/** SVC-BAT-01, -02, -04 .. -12 (SVC-BAT-03 needs both databases and is in the rollback test). */
@LedgerServiceTest
class BatchTest {

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

    // The wire cases of SVC-BAT-06 and SVC-BAT-07 (an unknown key, an unknown or missing op, ops that is not an array, an
    // operationKey or request_id inside an op) are no longer reachable here: the batch has the one entry with the records
    // (changed: BatchService.batch(String, String, String) and LedgerJson.readInputList are gone, OPK-024); the strict reading of
    // ops is the binding of the tool and is tested in the module ledger-mcp-server (ops[i].<key> UNKNOWN_ARGUMENT).

    @Test
    @DisplayName("SVC-BAT-06 (changed: the operation is a record, a missing key is a null): a missing expected_version names its path")
    void bat06_missingExpectedVersion(Scenario s) {
        SaveResult d = s.newDefect("d");
        assertRefused(s, List.of(new BatchOpInput.Transition("defect", d.id(), null, "DISPUTED", Scenario.REASON, null,
                null, null, null)), "ops[0].expected_version");
    }

    @Test
    @DisplayName("SVC-BAT-06 (changed: the operation is a record, a missing key is a null): a missing reason in the second operation rolls the first back")
    void bat06_missingReasonLater(Scenario s) {
        BatchOpInput noReason = new BatchOpInput.Save("defect", Fields.of(s.defectFields("x")), null, null, null);
        assertRefused(s, List.of(saveDefect(s, "x"), noReason), "ops[1].reason");
    }

    private void assertRefused(Scenario s, List<BatchOpInput> ops, String path) {
        long defects = s.count("SELECT COUNT(*) FROM defect WHERE project_id = ?", s.projectId());
        String batchKey = Scenario.uuid();
        ValidationException e = (ValidationException) Expect.failure(() -> s.bean(BatchService.class)
                .batch(Scenario.AUTHOR, batchKey, ops), ValidationCode.VALIDATION_FAILED);
        assertThat(e.issues().get(0).path()).startsWith(path);
        assertThat(s.count("SELECT COUNT(*) FROM defect WHERE project_id = ?", s.projectId())).isEqualTo(defects);
        assertThat(s.count("SELECT COUNT(*) FROM request_receipt WHERE operation_key = ?", batchKey))
                .as("the receipt of the batch key is rolled back with the batch").isZero();
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
        List<BatchOpInput> ops = IntStream.range(0, size.count()).<BatchOpInput>mapToObj(i -> saveDefect(s, "x")).toList();
        assertThat(Expect.outcome(() -> s.bean(BatchService.class).batch(Scenario.AUTHOR, Scenario.uuid(), ops)))
                .isEqualTo(Optional.ofNullable(size.expected()));
    }

    // ---------------------------------------------------------------------- 08

    @Test
    @DisplayName("SVC-BAT-08 (OPK-005): an operation of a batch has no key of its own: operation i runs under the key derived from the key of the batch and its position, and that derived key is bound to the operation (changed: an operation used to be able to carry its own request id)")
    void bat08_derivedKeys(Scenario s) {
        String batchId = Scenario.uuid();
        OperationKey batchKey = OperationKey.parse(batchId);
        BatchResult first = batch(s, Scenario.AUTHOR, batchId, List.of(saveDefect(s, "zero"), saveDefect(s, "one")));
        // the receipts of the operations are under the derived keys, and the batch has a receipt of its own under its key
        // (changed: there used to be none under the key of the batch itself, OPK-019)
        assertThat(s.count("SELECT COUNT(*) FROM request_receipt WHERE operation_key = ? AND operation = 'BATCH'",
                batchId)).isEqualTo(1);
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

    // OPK-005 (an operation carries no key: operationKey, request_id or requestId inside an op is UNKNOWN_ARGUMENT at
    // ops[i].<key>) is the binding of the tool: it is tested in ledger-mcp-server (OperationKeyTest); the record
    // BatchOpInput has no such component (BatchWireTest).

    // ---------------------------------------------------------------------- OPK-019: the receipt of the batch

    private static long defects(Scenario s) {
        return s.count("SELECT COUNT(*) FROM defect WHERE project_id = ?", s.projectId());
    }

    private static long receiptsOf(Scenario s, String author) {
        return s.count("SELECT COUNT(*) FROM request_receipt WHERE author_id = ?", author);
    }

    @Test
    @DisplayName("OPK-019 (service): the same batch key with the same operations replays the stored results and writes once per operation; a receipt with the operation BATCH exists under the batch key, one per operation under its derived key")
    void opk019_replay(Scenario s) {
        String batchId = Scenario.uuid();
        OperationKey batchKey = OperationKey.parse(batchId);
        List<BatchOpInput> ops = List.of(saveDefect(s, "a"), saveDefect(s, "b"));
        BatchResult first = batch(s, Scenario.AUTHOR, batchId, ops);
        long history = s.count("SELECT COUNT(*) FROM history");
        long receipts = receiptsOf(s, Scenario.AUTHOR);

        assertThat(batch(s, Scenario.AUTHOR, batchId, ops)).isEqualTo(first);

        assertThat(defects(s)).as("one write per operation").isEqualTo(2);
        assertThat(s.count("SELECT COUNT(*) FROM history")).isEqualTo(history);
        assertThat(receiptsOf(s, Scenario.AUTHOR)).as("a replay adds no receipt").isEqualTo(receipts);
        assertThat(s.count("SELECT COUNT(*) FROM request_receipt WHERE operation_key = ? AND operation = 'BATCH'",
                batchId)).isEqualTo(1);
        assertThat(s.count("SELECT COUNT(*) FROM request_receipt WHERE operation_key IN (?, ?) AND operation = 'SAVE'",
                batchKey.derive(0).toString(), batchKey.derive(1).toString())).isEqualTo(2);
        assertThat(s.count("SELECT COUNT(*) FROM request_receipt WHERE operation_key IN (?, ?, ?)", batchId,
                batchKey.derive(0).toString(), batchKey.derive(1).toString()))
                .as("the batch, and its two operations").isEqualTo(3);
    }

    /** A first batch, and the batch that is sent later with the same key. */
    private record Resend(String name, List<BatchOpInput> first, List<BatchOpInput> later) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Resend> resentWithOtherOperations() {
        BatchOpInput a = new BatchOpInput.Save("defect", Fields.of("title", "a"), Scenario.REASON, null, null);
        BatchOpInput b = new BatchOpInput.Save("defect", Fields.of("title", "b"), Scenario.REASON, null, null);
        BatchOpInput aWithAnotherReason = new BatchOpInput.Save("defect", Fields.of("title", "a"),
                "another reason, long enough", null, null);
        BatchOpInput aWithAnotherValue = new BatchOpInput.Save("defect", Fields.of("title", "a changed"), Scenario.REASON,
                null, null);
        return Stream.of(new Resend("one operation added: [A] then [A, B]", List.of(a), List.of(a, b)),
                new Resend("one operation removed: [A, B] then [A]", List.of(a, b), List.of(a)),
                new Resend("operations moved: [A, B] then [B, A]", List.of(a, b), List.of(b, a)),
                new Resend("one operation changed (reason)", List.of(a, b), List.of(aWithAnotherReason, b)),
                new Resend("one operation changed (a value of fields)", List.of(a, b), List.of(aWithAnotherValue, b)));
    }

    @ParameterizedTest
    @MethodSource("resentWithOtherOperations")
    @DisplayName("OPK-019 (service): the same batch key with other operations (added, removed, moved, changed) is OPERATION_CONFLICT and nothing is written")
    void opk019_otherOperations(Resend resend, Scenario s) {
        String batchId = Scenario.uuid();
        // the fields of the operations carry the project of the scenario
        List<BatchOpInput> first = withProject(s, resend.first());
        List<BatchOpInput> later = withProject(s, resend.later());
        batch(s, Scenario.AUTHOR, batchId, first);
        long written = defects(s);
        long history = s.count("SELECT COUNT(*) FROM history");
        long receipts = receiptsOf(s, Scenario.AUTHOR);

        Expect.failure(() -> batch(s, Scenario.AUTHOR, batchId, later), ConflictCode.OPERATION_CONFLICT);

        assertThat(defects(s)).as("no operation of the later batch is written").isEqualTo(written);
        assertThat(s.count("SELECT COUNT(*) FROM history")).isEqualTo(history);
        assertThat(receiptsOf(s, Scenario.AUTHOR)).isEqualTo(receipts);
    }

    /** The save operations of a list with the fields of a defect of the project of the scenario (the title is kept). */
    private static List<BatchOpInput> withProject(Scenario s, List<BatchOpInput> ops) {
        return ops.stream().<BatchOpInput>map(op -> {
            BatchOpInput.Save save = (BatchOpInput.Save) op;
            String title = save.fields().title().orNull();
            return new BatchOpInput.Save(save.entity(), Fields.of(s.defectFields(title)), save.reason(), save.id(),
                    save.expectedVersion());
        }).toList();
    }

    @Test
    @DisplayName("OPK-019 (service): a key used by ledger_save and then by ledger_batch is OPERATION_CONFLICT, and nothing more is written")
    void opk019_saveThenBatch(Scenario s) {
        String key = Scenario.uuid();
        s.bean(SaveService.class).save(Scenario.AUTHOR, new SaveCommand(key, "defect", null, 0L,
                Fields.of(s.defectFields("a")), Scenario.REASON));
        long history = s.count("SELECT COUNT(*) FROM history");
        long receipts = receiptsOf(s, Scenario.AUTHOR);

        Expect.failure(() -> batch(s, Scenario.AUTHOR, key, List.of(saveDefect(s, "a"))), ConflictCode.OPERATION_CONFLICT);

        assertThat(defects(s)).isEqualTo(1);
        assertThat(s.count("SELECT COUNT(*) FROM history")).isEqualTo(history);
        assertThat(receiptsOf(s, Scenario.AUTHOR)).isEqualTo(receipts);
    }

    @Test
    @DisplayName("OPK-019 (service): a key used by ledger_batch and then by ledger_save is OPERATION_CONFLICT, and nothing more is written")
    void opk019_batchThenSave(Scenario s) {
        String key = Scenario.uuid();
        batch(s, Scenario.AUTHOR, key, List.of(saveDefect(s, "a")));
        long history = s.count("SELECT COUNT(*) FROM history");
        long receipts = receiptsOf(s, Scenario.AUTHOR);

        Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR, new SaveCommand(key, "defect", null, 0L,
                Fields.of(s.defectFields("a")), Scenario.REASON)), ConflictCode.OPERATION_CONFLICT);

        assertThat(defects(s)).isEqualTo(1);
        assertThat(s.count("SELECT COUNT(*) FROM history")).isEqualTo(history);
        assertThat(receiptsOf(s, Scenario.AUTHOR)).isEqualTo(receipts);
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
