// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.application;

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
import org.agentledger.application.read.Cursors;
import org.agentledger.application.read.DetailService;
import org.agentledger.application.read.HistoryService;
import org.agentledger.application.read.HistoryService.HistoryQuery;
import org.agentledger.application.receipt.ReceiptService;
import org.agentledger.application.support.Expect;
import org.agentledger.application.support.LedgerAppContext;
import org.agentledger.application.support.LedgerServiceTest;
import org.agentledger.application.support.Scenario;
import org.agentledger.application.write.BatchOpInput;
import org.agentledger.application.write.BatchService;
import org.agentledger.application.write.ReviewService;
import org.agentledger.application.write.Rows;
import org.agentledger.application.write.SaveService;
import org.agentledger.application.write.TransitionService;
import org.agentledger.application.write.WhoamiService;
import org.agentledger.application.write.WriteResult.SaveResult;
import org.agentledger.database.repository.Counter;
import org.agentledger.database.repository.CriterionRepository;
import org.agentledger.database.repository.HistoryQueryRepository;
import org.agentledger.database.repository.HistoryRepository;
import org.agentledger.database.repository.IdAllocator;
import org.agentledger.domain.context.ActorScope;
import org.agentledger.domain.error.ConflictCode;
import org.agentledger.domain.error.InfrastructureCode;
import org.agentledger.domain.error.TransitionCode;
import org.agentledger.domain.error.ValidationCode;
import org.agentledger.domain.error.ValidationException;
import org.agentledger.domain.error.ValidationReason;
import org.agentledger.domain.model.Criterion;
import org.agentledger.domain.model.HistoryEntry;
import org.agentledger.domain.model.Issue;
import org.agentledger.domain.model.Spec;
import org.agentledger.domain.model.Task;
import org.agentledger.domain.rule.ActorAssurance;
import org.agentledger.domain.rule.Channel;
import org.agentledger.domain.rule.HistoryEntity;
import org.agentledger.domain.rule.HistoryOp;
import org.agentledger.domain.value.AuthorId;
import org.agentledger.domain.value.CriterionId;
import org.agentledger.domain.value.ImportedActorId;
import org.agentledger.domain.value.JsonDocument;
import org.agentledger.domain.value.OperationKey;
import org.agentledger.domain.value.PageCursor;

/**
 * SVC-PRN-01 .. SVC-PRN-07, -10 .. -12: correctness invariants in the application module (F1, F4, F5, 2.5, 2.6).
 */
@LedgerServiceTest
class PrinciplesTest {

    private static final String IMPORTED_AUTHOR = "claude:old";

    private static void importedTransition(Scenario s, String taskId, String from, String to) {
        IdAllocator ids = s.bean(IdAllocator.class);
        s.ctx().tx(() -> s.bean(HistoryRepository.class).insert(new HistoryEntry(ids.next(Counter.HISTORY),
                Instant.parse("2027-01-01T00:00:00Z"), new ImportedActorId(IMPORTED_AUTHOR), "ledger_author",
                ActorAssurance.IMPORTED_LOGIN, HistoryEntity.TASK, taskId, HistoryOp.TRANSITION, from, to,
                JsonDocument.EMPTY_OBJECT, null, "imported by a migration", null, null, null,
                new OperationKey(UUID.randomUUID().toString()), "import-" + UUID.randomUUID())));
    }

    @Test
    @DisplayName("SVC-PRN-01: an imported transition whose state is NULL or none of the task states is not a report: the detail of the task reads it and names the real reporter")
    void prn01_detailIgnoresImportedTransitionsOfUnknownStates(Scenario s) {
        SaveResult task = s.newTask("t");
        s.bean(TransitionService.class).transition(Scenario.AUTHOR,
                s.move("task", task.id(), 1, "COMPLETED", Scenario.EVIDENCE));
        importedTransition(s, task.id(), null, "COMPLETED");
        importedTransition(s, task.id(), "WEIRD", "COMPLETED");
        importedTransition(s, task.id(), "PLANNED", "WEIRD2");

        assertThat(s.bean(DetailService.class).task(task.id()).completedBy()).isEqualTo(Scenario.AUTHOR);

        SaveResult onlyImported = s.newTask("t2");
        importedTransition(s, onlyImported.id(), null, "COMPLETED");
        importedTransition(s, onlyImported.id(), "WEIRD", "WEIRD2");
        assertThat(s.bean(DetailService.class).task(onlyImported.id()).completedBy()).isNull();
    }

    @Test
    @DisplayName("SVC-PRN-02: a transition of a task whose history holds such rows is decided by the rules of the state machine, never by an exception of the parser")
    void prn02_transitionIgnoresImportedTransitionsOfUnknownStates(Scenario s) {
        SaveResult task = s.newTask("t");
        s.bean(TransitionService.class).transition(Scenario.AUTHOR,
                s.move("task", task.id(), 1, "COMPLETED", Scenario.EVIDENCE));
        importedTransition(s, task.id(), null, "COMPLETED");
        importedTransition(s, task.id(), "WEIRD", "WEIRD2");

        Expect.failure(() -> s.bean(ReviewService.class).review(Scenario.AUTHOR,
                s.move("task", task.id(), 2, "VERIFIED", Scenario.EVIDENCE)), TransitionCode.SAME_SESSION);
        var verified = s.bean(ReviewService.class).review(Scenario.REVIEWER,
                s.move("task", task.id(), 2, "VERIFIED", Scenario.EVIDENCE));
        assertThat(verified.to()).isEqualTo("VERIFIED");
    }

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

    private static HistoryQuery query(LedgerAppContext ctx, String id) {
        HistoryQueryRepository.Filter none = new HistoryQueryRepository.Filter(null, null, null, null, null, null,
                null, null, null, null);
        String cursor = ctx.bean(Cursors.class).encode(PageCursor.Kind.HISTORY, none, null, id);
        return new HistoryQuery(null, null, null, null, null, null, null, null, null, null, null, null, cursor);
    }

    @Test
    @DisplayName("SVC-PRN-06: the stale save of an issue, a spec, a task and a criterion is a VERSION_CONFLICT with the version the row was read at and the version in the database now")
    void prn06_staleSaves(Scenario s) {
        SaveResult issue = s.newIssue("i");
        SaveResult spec = s.newSpec("sp");
        SaveResult task = s.newTask("t");
        SaveResult criterion = s.newCriterion("1", "r", "c");
        Issue staleIssue = s.issue(issue.id());
        Spec staleSpec = s.spec(spec.id());
        Task staleTask = s.task(task.id());
        Criterion staleCriterion = s.bean(CriterionRepository.class).findById(CriterionId.of(criterion.id()))
                .orElseThrow();
        SaveService save = s.bean(SaveService.class);
        save.save(Scenario.AUTHOR, s.edit("issue", issue.id(), 1, Map.of("title", "i2")));
        save.save(Scenario.AUTHOR, s.edit("spec", spec.id(), 1, Map.of("title", "sp2")));
        save.save(Scenario.AUTHOR, s.edit("task", task.id(), 1, Map.of("title", "t2")));
        save.save(Scenario.AUTHOR, s.edit("criterion", criterion.id(), 1, Map.of("criterion_text", "changed")));
        Rows rows = s.bean(Rows.class);

        assertThat(Expect.failure(() -> ScopedValue.where(ActorScope.CURRENT, AuthorId.of(Scenario.AUTHOR))
                .call(() -> rows.saveIssue(staleIssue)), ConflictCode.VERSION_CONFLICT).args())
                .containsExactly("issue", issue.id(), "1", "2");
        assertThat(Expect.failure(() -> ScopedValue.where(ActorScope.CURRENT, AuthorId.of(Scenario.AUTHOR))
                .call(() -> rows.saveSpec(staleSpec)), ConflictCode.VERSION_CONFLICT).args())
                .containsExactly("spec", spec.id(), "1", "2");
        assertThat(Expect.failure(() -> ScopedValue.where(ActorScope.CURRENT, AuthorId.of(Scenario.AUTHOR))
                .call(() -> rows.saveTask(staleTask)), ConflictCode.VERSION_CONFLICT).args())
                .containsExactly("task", task.id(), "1", "2");
        assertThat(Expect.failure(() -> ScopedValue.where(ActorScope.CURRENT, AuthorId.of(Scenario.AUTHOR))
                .call(() -> rows.saveCriterion(staleCriterion)), ConflictCode.VERSION_CONFLICT).args())
                .containsExactly("criterion", criterion.id(), "1", "2");
        assertThat(s.issue(issue.id()).title()).isEqualTo("i2");
    }

    @Test
    @DisplayName("SVC-PRN-07: only Rows.java and CommonCodeService.java make a VERSION_CONFLICT")
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

    static Stream<Arguments> batchOperationsWithoutAKey() {
        String reason = Scenario.REASON;
        return Stream.of(
                Arguments.of(new BatchOpInput.Transition("task", "X-T-0001", 1L, null, reason, null, null, null, null),
                        "ops[0].to", "transition"),
                Arguments.of(new BatchOpInput.Transition("task", "X-T-0001", null, "COMPLETED", reason, null, null,
                        null, null), "ops[0].expected_version", "transition"),
                Arguments.of(new BatchOpInput.RecordDeliverable("issue", "X-I-0001", "code_change", null, reason, null,
                        "Deliverable summary", null, null, null, null, null), "ops[0].deliverable_ref",
                        "record_deliverable"),
                Arguments.of(new BatchOpInput.RecordCase("X-I-0001", null, reason, null, null, null, null),
                        "ops[0].observation", "record_case"),
                Arguments.of(new BatchOpInput.Link("issue_task", "X-I-0001", null, reason, null), "ops[0].b", "link"),
                Arguments.of(new BatchOpInput.Correct(null, reason, null), "ops[0].history_id", "correct"),
                Arguments.of(new BatchOpInput.Save("issue", null, reason, null, null), "ops[0].fields", "save"));
    }

    @ParameterizedTest(name = "{1} of a {2}")
    @MethodSource("batchOperationsWithoutAKey")
    @DisplayName("SVC-PRN-10: a required key that is missing (null) is REQUIRED on its path in the request, the failure names the operation, and nothing is written")
    void prn10_missingKey(BatchOpInput operation, String path, String op, Scenario s) {
        ValidationException refused = (ValidationException) Expect.failure(() -> s.bean(BatchService.class)
                .batch(Scenario.AUTHOR, Scenario.uuid(), List.of(operation)), ValidationCode.VALIDATION_FAILED);

        assertThat(refused.issues().getFirst().path()).isEqualTo(path);
        assertThat(refused.issues().getFirst().reason()).isEqualTo(ValidationReason.REQUIRED);
        assertThat(refused.details()).containsEntry("op_index", List.of("1")).containsEntry("op", List.of(op));
    }

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
        assertThat(granted.grants().stream().filter(grant -> Objects.equals(grant.project(), s.projectId()))
                .map(WhoamiService.GrantView::scope).toList()).containsExactlyInAnyOrder("issue", "spec", "task",
                        "deliverable", "verify");
    }
}
