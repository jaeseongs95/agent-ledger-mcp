package org.pgledger.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.pgledger.application.read.Cursors;
import org.pgledger.application.read.DetailService;
import org.pgledger.application.read.HistoryService;
import org.pgledger.application.read.HistoryService.HistoryQuery;
import org.pgledger.application.receipt.ReceiptService;
import org.pgledger.application.support.Expect;
import org.pgledger.application.support.LedgerAppContext;
import org.pgledger.application.support.LedgerServiceTest;
import org.pgledger.application.support.Scenario;
import org.pgledger.application.write.BatchOpInput;
import org.pgledger.application.write.BatchService;
import org.pgledger.application.write.Rows;
import org.pgledger.application.write.SaveService;
import org.pgledger.application.write.TransitionService;
import org.pgledger.application.write.ReviewService;
import org.pgledger.application.write.WhoamiService;
import org.pgledger.application.write.WriteResult.SaveResult;
import org.pgledger.database.repository.ConditionRepository;
import org.pgledger.database.repository.Counter;
import org.pgledger.database.repository.HistoryQueryRepository;
import org.pgledger.database.repository.HistoryRepository;
import org.pgledger.database.repository.IdAllocator;
import org.pgledger.domain.context.ActorScope;
import org.pgledger.domain.error.ConflictCode;
import org.pgledger.domain.error.ConflictException;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.TransitionCode;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationReason;
import org.pgledger.domain.model.Condition;
import org.pgledger.domain.model.Defect;
import org.pgledger.domain.model.HistoryEntry;
import org.pgledger.domain.model.Task;
import org.pgledger.domain.rule.ActorAssurance;
import org.pgledger.domain.rule.Channel;
import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.rule.HistoryOp;
import org.pgledger.domain.value.AuthorId;
import org.pgledger.domain.value.ConditionId;
import org.pgledger.domain.value.JsonDocument;
import org.pgledger.domain.value.ImportedActorId;
import org.pgledger.domain.value.PageCursor;
import org.pgledger.domain.value.OperationKey;
import org.pgledger.domain.error.ValidationCode;

/**
 * SVC-PRN-01 .. SVC-PRN-07, -11, -12: the correctness defects that the audit of the development principles found in the
 * application module (plan 2, F1, F4, F5) and the refactors of its 2.5 items, on the PostgreSQL of the test PC.
 */
@LedgerServiceTest
class PrinciplesTest {

    private static final String IMPORTED_AUTHOR = "claude:old";

    /** Puts an imported TRANSITION row of the task into the history, as an import does (the states are text). */
    private static void importedTransition(Scenario s, String taskId, String from, String to) {
        IdAllocator ids = s.bean(IdAllocator.class);
        s.ctx().tx(() -> s.bean(HistoryRepository.class).insert(new HistoryEntry(ids.next(Counter.HISTORY),
                Instant.parse("2027-01-01T00:00:00Z"), new ImportedActorId(IMPORTED_AUTHOR), "ledger_author",
                ActorAssurance.IMPORTED_LOGIN, HistoryEntity.TASK, taskId, HistoryOp.TRANSITION, from, to,
                JsonDocument.EMPTY_OBJECT, null, "imported by a migration", null, null, null,
                new OperationKey(UUID.randomUUID().toString()), "import-" + UUID.randomUUID())));
    }

    // --------------------------------------------------------------------------------------------------- F1

    @Test
    @DisplayName("SVC-PRN-01: an imported transition whose state is NULL or none of the task states is not a report: the detail of the task reads it and names the real reporter")
    void prn01_detailIgnoresImportedTransitionsOfUnknownStates(Scenario s) {
        SaveResult task = s.newTask("t");
        s.bean(TransitionService.class).transition(Scenario.AUTHOR,
                s.move("task", task.id(), 1, "REPORTED_DONE", Scenario.EVIDENCE));
        // later rows than the real report: were they reports, they would be the latest one
        importedTransition(s, task.id(), null, "REPORTED_DONE");
        importedTransition(s, task.id(), "WEIRD", "REPORTED_DONE");
        importedTransition(s, task.id(), "PLANNED", "WEIRD2");

        assertThat(s.bean(DetailService.class).task(task.id()).completedBy()).isEqualTo(Scenario.AUTHOR);

        SaveResult onlyImported = s.newTask("t2");
        importedTransition(s, onlyImported.id(), null, "REPORTED_DONE");
        importedTransition(s, onlyImported.id(), "WEIRD", "WEIRD2");
        assertThat(s.bean(DetailService.class).task(onlyImported.id()).completedBy()).isNull();
    }

    @Test
    @DisplayName("SVC-PRN-02: a transition of a task whose history holds such rows is decided by the rules of the state machine, never by an exception of the parser")
    void prn02_transitionIgnoresImportedTransitionsOfUnknownStates(Scenario s) {
        SaveResult task = s.newTask("t");
        s.bean(TransitionService.class).transition(Scenario.AUTHOR,
                s.move("task", task.id(), 1, "REPORTED_DONE", Scenario.EVIDENCE));
        importedTransition(s, task.id(), null, "REPORTED_DONE");
        importedTransition(s, task.id(), "WEIRD", "WEIRD2");

        // the real report still counts: the reporter cannot verify the task himself ...
        Expect.failure(() -> s.bean(ReviewService.class).review(Scenario.AUTHOR,
                s.move("task", task.id(), 2, "VERIFIED", Scenario.EVIDENCE)), TransitionCode.SAME_SESSION);
        // ... and another session can
        var verified = s.bean(ReviewService.class).review(Scenario.REVIEWER,
                s.move("task", task.id(), 2, "VERIFIED", Scenario.EVIDENCE));
        assertThat(verified.to()).isEqualTo("VERIFIED");
    }

    // --------------------------------------------------------------------------------------------------- F4

    @Test
    @DisplayName("SVC-PRN-03 (changed by VRF-026): a free task id with a number of 19 digits has no number and raises no counter; one of 18 digits is a number above the next number and is refused (ID_NUMBER_AHEAD), so it cannot stop the automatic ids; neither is a NumberFormatException")
    void prn03_taskIdNumbers(Scenario s) {
        SaveService save = s.bean(SaveService.class);
        String free = s.code() + "-T-" + "9".repeat(19);
        assertThat(save.save(Scenario.AUTHOR, s.create("task", free, s.taskFields("long"))).id()).isEqualTo(free);
        assertThat(s.count("SELECT next_task_no FROM project WHERE id = ?", s.projectId())).isZero();

        String issued = s.code() + "-T-123456789012345678";
        Expect.failure(() -> save.save(Scenario.AUTHOR, s.create("task", issued, s.taskFields("max"))),
                ValidationCode.ID_NUMBER_AHEAD);
        assertThat(s.count("SELECT next_task_no FROM project WHERE id = ?", s.projectId())).isZero();
        assertThat(s.newTask("automatic").id()).isEqualTo(s.taskId(1));
    }

    @ParameterizedTest(name = "a history cursor whose id is \"{0}\"")
    @ValueSource(strings = {"12x", "-5", "9999999999999999999", "1234567890123456789", "0x10", " 7"})
    @DisplayName("SVC-PRN-04: a cursor that is well formed but whose id is no number of at most 18 digits is a damaged cursor (VALIDATION_FAILED on cursor), never a NumberFormatException")
    void prn04_historyCursorWithAnId(String id, LedgerAppContext ctx) {
        HistoryService history = ctx.bean(HistoryService.class);
        ValidationException refused = (ValidationException) Expect.failure(() -> history.history(query(ctx, id)),
                ValidationCode.VALIDATION_FAILED);
        assertThat(refused.issues().getFirst().path()).isEqualTo("cursor");
        assertThat(refused.issues().getFirst().reason()).isEqualTo(ValidationReason.CURSOR_DAMAGED);
    }

    @Test
    @DisplayName("SVC-PRN-04: a cursor with an id of 18 digits is read")
    void prn04_historyCursorWithTheLargestId(LedgerAppContext ctx) {
        var page = ctx.bean(HistoryService.class).history(query(ctx, "999999999999999999"));
        assertThat(page.items()).isEmpty();
    }

    /** A history call whose cursor is the one of the call without criteria, carrying {@code id} as its position. */
    private static HistoryQuery query(LedgerAppContext ctx, String id) {
        HistoryQueryRepository.Filter none = new HistoryQueryRepository.Filter(null, null, null, null, null, null,
                null, null, null, null);
        String cursor = ctx.bean(Cursors.class).encode(PageCursor.Kind.HISTORY, none, null, id);
        return new HistoryQuery(null, null, null, null, null, null, null, null, null, null, null, null, cursor);
    }

    // --------------------------------------------------------------------------------------------------- F5

    @Test
    @DisplayName("SVC-PRN-06: the stale save of a defect, a task and a condition is a VERSION_CONFLICT with the version the row was read at and the version in the database now")
    void prn06_staleSaves(Scenario s) {
        SaveResult defect = s.newDefect("d");
        SaveResult task = s.newTask("t");
        SaveResult condition = s.newCondition("1", "r", "c");
        Defect staleDefect = s.defect(defect.id());
        Task staleTask = s.task(task.id());
        Condition staleCondition = s.bean(ConditionRepository.class).findById(ConditionId.of(condition.id()))
                .orElseThrow();
        SaveService save = s.bean(SaveService.class);
        save.save(Scenario.AUTHOR, s.edit("defect", defect.id(), 1, Map.of("title", "d2")));
        save.save(Scenario.AUTHOR, s.edit("task", task.id(), 1, Map.of("title", "t2")));
        save.save(Scenario.AUTHOR, s.edit("condition", condition.id(), 1, Map.of("condition_text", "changed")));
        Rows rows = s.bean(Rows.class);

        // a write runs inside the scope of its actor (the rows are audited with it)
        assertThat(Expect.failure(() -> ScopedValue.where(ActorScope.CURRENT, AuthorId.of(Scenario.AUTHOR))
                .call(() -> rows.saveDefect(staleDefect)), ConflictCode.VERSION_CONFLICT).args())
                .containsExactly("defect", defect.id(), "1", "2");
        assertThat(Expect.failure(() -> ScopedValue.where(ActorScope.CURRENT, AuthorId.of(Scenario.AUTHOR))
                .call(() -> rows.saveTask(staleTask)), ConflictCode.VERSION_CONFLICT).args())
                .containsExactly("task", task.id(), "1", "2");
        assertThat(Expect.failure(() -> ScopedValue.where(ActorScope.CURRENT, AuthorId.of(Scenario.AUTHOR))
                .call(() -> rows.saveCondition(staleCondition)), ConflictCode.VERSION_CONFLICT).args())
                .containsExactly("condition", condition.id(), "1", "2");
        // nothing was written by the refused saves
        assertThat(s.defect(defect.id()).title()).isEqualTo("d2");
    }

    @Test
    @DisplayName("SVC-PRN-07: only the one save of the rows makes a VERSION_CONFLICT of a save: no operation has a copy that could put a placeholder in its arguments; the one other maker is CommonCodeService (TXF-002), whose conditional update of a common_code row is no row of Rows (defect, task, condition) and whose arguments are another shape (common_code, group/code, versions)")
    void prn07_oneVersionConflictOfASave() throws java.io.IOException {
        try (Stream<Path> files = Files.walk(Path.of("src", "main", "java"))) {
            List<String> makers = files.filter(file -> file.toString().endsWith(".java")).filter(file -> {
                try {
                    return Files.readString(file).contains("ConflictCode.VERSION_CONFLICT");
                } catch (java.io.IOException failure) {
                    throw new java.io.UncheckedIOException(failure);
                }
            }).map(file -> file.getFileName().toString()).toList();
            assertThat(makers).containsExactlyInAnyOrder("CommonCodeService.java", "Rows.java");
        }
    }

    // ------------------------------------------------------------------------------------------ 2.6 input checks

    static Stream<Arguments> batchOperationsWithoutAKey() {
        String reason = Scenario.REASON;
        // changed: the operations are the records of the one entry (the text form of ops is gone), and a missing key and a
        // JSON null are the same null of the record (the former pair of cases of a missing and a null "to" is one case)
        return Stream.of(
                Arguments.of(new BatchOpInput.Transition("task", "X-T-0001", 1L, null, reason, null, null, null, null),
                        "ops[0].to", "transition"),
                Arguments.of(new BatchOpInput.Transition("task", "X-T-0001", null, "REPORTED_DONE", reason, null, null,
                        null, null), "ops[0].expected_version", "transition"),
                Arguments.of(new BatchOpInput.RecordFix("X-D-0001", "r", null, reason, null, null, null, null, null,
                        null), "ops[0].commit_sha", "record_fix"),
                Arguments.of(new BatchOpInput.RecordCase("X-D-0001", null, reason, null, null, null, null),
                        "ops[0].observation", "record_case"),
                Arguments.of(new BatchOpInput.Link("defect_task", "X-D-0001", null, reason, null), "ops[0].b", "link"),
                Arguments.of(new BatchOpInput.Correct(null, reason, null), "ops[0].history_id", "correct"),
                Arguments.of(new BatchOpInput.Save("defect", null, reason, null, null), "ops[0].fields", "save"));
    }

    @ParameterizedTest(name = "{1} of a {2}")
    @MethodSource("batchOperationsWithoutAKey")
    @DisplayName("SVC-PRN-10: a required key that is missing (null) is REQUIRED on its path in the request, the failure names the operation, and nothing is written (changed: the operations are built as records with a null for the missing key)")
    void prn10_missingKey(BatchOpInput operation, String path, String op, Scenario s) {
        ValidationException refused = (ValidationException) Expect.failure(() -> s.bean(BatchService.class)
                .batch(Scenario.AUTHOR, Scenario.uuid(), List.of(operation)), ValidationCode.VALIDATION_FAILED);

        assertThat(refused.issues().getFirst().path()).isEqualTo(path);
        assertThat(refused.issues().getFirst().reason()).isEqualTo(ValidationReason.REQUIRED);
        assertThat(refused.details()).containsEntry("op_index", List.of("1")).containsEntry("op", List.of(op));
    }

    // ------------------------------------------------------------------------------------------ 2.5 exceptions

    @Test
    @DisplayName("SVC-PRN-11: finishing a receipt that was never reserved is an INTEGRITY_VIOLATION of the infrastructure, not an IllegalStateException")
    void prn11_receiptIntegrity(Scenario s) {
        ReceiptService receipts = s.bean(ReceiptService.class);
        Expect.failure(() -> s.ctx().tx(() -> {
            receipts.finish(new OperationKey(UUID.randomUUID().toString()), AuthorId.of(Scenario.AUTHOR), Map.of());
            return null;
        }), InfrastructureCode.INTEGRITY_VIOLATION);
    }

    @Test
    @DisplayName("SVC-PRN-12: whoami answers a value that is no author as data (authorFormatOk false, the text, no grants), for either channel; a real author gets its grants")
    void prn12_whoamiAnswersAMalformedAuthor(Scenario s) {
        WhoamiService whoami = s.bean(WhoamiService.class);
        var author = whoami.whoami("not-an-author", Channel.AUTHOR);
        assertThat(author.authorFormatOk()).isFalse();
        assertThat(author.author()).isEqualTo("not-an-author");
        assertThat(author.grants()).isEmpty();
        assertThat(author.canVerifyLogin()).isFalse();
        assertThat(whoami.whoami("not-an-author", Channel.REVIEWER).canVerifyLogin()).isTrue();

        var granted = whoami.whoami(Scenario.AUTHOR, Channel.AUTHOR);
        assertThat(granted.authorFormatOk()).isTrue();
        assertThat(granted.author()).isEqualTo(Scenario.AUTHOR);
        // the author of the scenarios has the grants of every scenario of the context: those of this project are four
        assertThat(granted.grants().stream().filter(grant -> Objects.equals(grant.project(), s.projectId()))
                .map(WhoamiService.GrantView::scope).toList()).containsExactlyInAnyOrder("defect", "task", "fix",
                        "verify");
    }
}
