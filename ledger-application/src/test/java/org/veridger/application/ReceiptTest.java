// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.veridger.application.support.Expect;
import org.veridger.application.support.Fields;
import org.veridger.application.support.LedgerServiceTest;
import org.veridger.application.support.MergeFixture;
import org.veridger.application.support.Scenario;
import org.veridger.application.write.BatchOpInput;
import org.veridger.application.write.BatchResult;
import org.veridger.application.write.BatchService;
import org.veridger.application.write.CorrectCommand;
import org.veridger.application.write.CorrectService;
import org.veridger.application.write.LinkCommand;
import org.veridger.application.write.LinkService;
import org.veridger.application.write.RecordCaseCommand;
import org.veridger.application.write.RecordCaseService;
import org.veridger.application.write.RecordDeliverableCommand;
import org.veridger.application.write.RecordDeliverableService;
import org.veridger.application.write.ReviewService;
import org.veridger.application.write.SaveCommand;
import org.veridger.application.write.SaveService;
import org.veridger.application.write.TransitionCommand;
import org.veridger.application.write.TransitionService;
import org.veridger.application.write.WriteResult.SaveResult;
import org.veridger.application.write.WriteResult.TransitionResult;
import org.veridger.apptest.LedgerTestRunners;
import org.veridger.database.repository.ReceiptRepository;
import org.veridger.domain.error.ConflictCode;
import org.veridger.domain.error.LedgerException;
import org.veridger.domain.error.LookupCode;
import org.veridger.domain.error.TrustCode;
import org.veridger.domain.error.ValidationCode;
import org.veridger.domain.error.ValidationReason;
import org.veridger.domain.model.Receipt;
import org.veridger.domain.model.ReceiptOperation;
import org.veridger.domain.rule.Channel;
import org.veridger.domain.rule.HistoryEntity;
import org.veridger.domain.rule.HistoryOp;
import org.veridger.domain.value.AuthorId;
import org.veridger.domain.value.OperationKey;
import org.veridger.domain.value.Sha256;

/** SVC-RCP-01 .. SVC-RCP-08, -12 .. -18, OPK-001 .. -004, OPK-020: idempotency of the write services. */
@LedgerServiceTest
class ReceiptTest {

    @Test
    @DisplayName("SVC-RCP-16 (TXA-04): reserve answers one of two kinds that differ in shape, not an Optional: its answer is a sealed type with exactly the records Reserved and Replayed")
    void rcp16_reserveAnswersASealedResult() {
        java.lang.reflect.Method reserve = java.util.Arrays.stream(
                org.veridger.application.receipt.ReceiptService.class.getDeclaredMethods())
                .filter(method -> "reserve".equals(method.getName())).findFirst().orElseThrow();
        Class<?> answer = reserve.getReturnType();

        assertThat(answer).as("two states are not one Optional (principle 3.3)").isNotEqualTo(java.util.Optional.class);
        assertThat(answer.isSealed()).isTrue();
        assertThat(java.util.Arrays.stream(answer.getPermittedSubclasses())).allMatch(Class::isRecord)
                .extracting(Class::getSimpleName).containsExactlyInAnyOrder("Reserved", "Replayed");
    }

    /** A response that a test stores in a receipt and reads back. */
    record Answer(String text) {
    }

    @Test
    @DisplayName("SVC-RCP-17 (TXF-001, principle 4.4): a reserve of an operation key that has a receipt reads it first and answers Replayed with the stored response")
    void rcp17_aStoredReceiptIsReplayedFromTheRead(Scenario s) {
        org.veridger.application.receipt.ReceiptService service = s.bean(
                org.veridger.application.receipt.ReceiptService.class);
        OperationKey first = OperationKey.parse(Scenario.uuid());
        OperationKey next = OperationKey.parse(Scenario.uuid());
        AuthorId author = AuthorId.of(Scenario.AUTHOR);
        Sha256 digest = Sha256.ofText("payload " + first);

        org.veridger.application.receipt.ReceiptService.Reservation<Answer> again = s.ctx().tx(() -> {
            service.reserve(first, ReceiptOperation.SAVE, digest, author, Channel.AUTHOR, Answer.class);
            service.finish(first, author, new Answer("first"));
            org.veridger.application.receipt.ReceiptService.Reservation<Answer> loser = service.reserve(first,
                    ReceiptOperation.SAVE, digest, author, Channel.AUTHOR, Answer.class);
            service.reserve(next, ReceiptOperation.SAVE, Sha256.ofText("payload " + next), author, Channel.AUTHOR,
                    Answer.class);
            service.finish(next, author, new Answer("next"));
            return loser;
        });

        assertThat(again).isEqualTo(new org.veridger.application.receipt.ReceiptService.Reservation.Replayed<>(
                new Answer("first")));
        assertThat(receipts(s, first.toString())).isEqualTo(1);
        assertThat(receipts(s, next.toString())).isEqualTo(1);
        assertThat(s.count("SELECT COUNT(*) FROM request_receipt WHERE operation_key = ? AND response IS NOT NULL",
                next.toString())).isEqualTo(1);
    }

    private static ReceiptRepository blind(ReceiptRepository real) {
        return new ReceiptRepository() {
            @Override
            public Receipt insert(Receipt aggregate) {
                return real.insert(aggregate);
            }

            @Override
            public java.util.Optional<Receipt> findByAuthorIdAndOperationKey(org.veridger.domain.value.ActorId authorId,
                    OperationKey operationKey) {
                return java.util.Optional.empty();
            }
        };
    }

    @Test
    @DisplayName("SVC-RCP-18 (TXF-001, principle 4.4): a reserve whose read missed the receipt of another call breaks the unique key and ends with DUPLICATE_KEY")
    void rcp18_aReserveThatMissedTheReceiptEndsWithDuplicateKey(Scenario s) {
        org.veridger.application.receipt.ReceiptService real = s.bean(
                org.veridger.application.receipt.ReceiptService.class);
        org.veridger.application.receipt.ReceiptService blind = new org.veridger.application.receipt.ReceiptService(
                blind(s.bean(ReceiptRepository.class)),
                s.bean(org.veridger.database.repository.ReceiptCompletionRepository.class),
                s.bean(org.veridger.application.json.LedgerJson.class), s.bean(java.time.Clock.class));
        OperationKey id = OperationKey.parse(Scenario.uuid());
        AuthorId author = AuthorId.of(Scenario.AUTHOR);
        Sha256 digest = Sha256.ofText("payload " + id);
        s.ctx().tx(() -> {
            real.reserve(id, ReceiptOperation.SAVE, digest, author, Channel.AUTHOR, Answer.class);
            real.finish(id, author, new Answer("winner"));
            return null;
        });

        Expect.failure(() -> s.ctx().tx(() -> blind.reserve(id, ReceiptOperation.SAVE, digest, author, Channel.AUTHOR,
                Answer.class)), ConflictCode.DUPLICATE_KEY);

        assertThat(receipts(s, id.toString())).isEqualTo(1);
        assertThat(s.ctx().tx(() -> real.reserve(id, ReceiptOperation.SAVE, digest, author, Channel.AUTHOR, Answer.class)))
                .as("a sequential repeat answers from the first call").isEqualTo(
                        new org.veridger.application.receipt.ReceiptService.Reservation.Replayed<>(new Answer("winner")));
    }

    private static SaveCommand create(Scenario s, String operationKey, String title) {
        return new SaveCommand(operationKey, "issue", null, 0L, Fields.of(s.issueFields(title)), Scenario.REASON);
    }

    private static long receipts(Scenario s, String operationKey) {
        return s.count("SELECT COUNT(*) FROM request_receipt WHERE operation_key = ?", operationKey);
    }

    private static long issues(Scenario s) {
        return s.count("SELECT COUNT(*) FROM issue WHERE project_id = ?", s.projectId());
    }

    @Test
    @DisplayName("SVC-RCP-01 (OPK-003): the same request again (the same operation key) returns the stored response and writes nothing")
    void rcp01_replay(Scenario s) {
        String id = Scenario.uuid();
        SaveResult first = s.bean(SaveService.class).save(Scenario.AUTHOR, create(s, id, "i"));
        long history = s.count("SELECT COUNT(*) FROM history");
        SaveResult second = s.bean(SaveService.class).save(Scenario.AUTHOR, create(s, id, "i"));
        assertThat(second).isEqualTo(first);
        assertThat(second.historyId()).isEqualTo(first.historyId());
        assertThat(issues(s)).isEqualTo(1);
        assertThat(s.count("SELECT COUNT(*) FROM history")).isEqualTo(history);
        assertThat(receipts(s, id)).isEqualTo(1);
    }

    static Stream<Named<BiFunction<Scenario, SaveCommand, SaveCommand>>> changedSaves() {
        return Stream.of(
                Named.of("another reason", (s, c) -> new SaveCommand(c.operationKey(), c.entity(), c.id(),
                        c.expectedVersion(), c.fields(), "another reason, long enough")),
                Named.of("another id", (s, c) -> new SaveCommand(c.operationKey(), c.entity(), s.issueId(2),
                        c.expectedVersion(), c.fields(), c.reason())),
                Named.of("another expected version", (s, c) -> new SaveCommand(c.operationKey(), c.entity(), c.id(), 5L,
                        c.fields(), c.reason())),
                Named.of("another field value", (s, c) -> new SaveCommand(c.operationKey(), c.entity(), c.id(),
                        c.expectedVersion(), Fields.of("title", "other title"), c.reason())));
    }

    @ParameterizedTest
    @MethodSource("changedSaves")
    @DisplayName("SVC-RCP-02 (OPK-004): the same operation key with another payload is a conflict")
    void rcp02_conflict(BiFunction<Scenario, SaveCommand, SaveCommand> change, Scenario s) {
        s.newIssue("one");
        s.newIssue("two");
        String id = Scenario.uuid();
        SaveCommand base = new SaveCommand(id, "issue", s.issueId(1), 1L, Fields.of("title", "edited"),
                Scenario.REASON);
        s.bean(SaveService.class).save(Scenario.AUTHOR, base);
        long history = s.count("SELECT COUNT(*) FROM history");
        Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR, change.apply(s, base)),
                ConflictCode.OPERATION_CONFLICT);
        assertThat(s.issue(s.issueId(1)).title()).isEqualTo("edited");
        assertThat(s.count("SELECT COUNT(*) FROM history")).isEqualTo(history);
    }

    @Test
    @DisplayName("SVC-RCP-03 (OPK-004): the same operation key for another operation is a conflict")
    void rcp03_anotherOperation(Scenario s) {
        String id = Scenario.uuid();
        SaveResult i = s.bean(SaveService.class).save(Scenario.AUTHOR, create(s, id, "i"));
        Expect.failure(() -> s.bean(TransitionService.class).transition(Scenario.AUTHOR, new TransitionCommand(id,
                "issue", i.id(), 1L, "DISPUTED", Scenario.REASON, Scenario.EVIDENCE, null, null)),
                ConflictCode.OPERATION_CONFLICT);
    }

    @Test
    @DisplayName("SVC-RCP-04 (OPK-004, OPK-020): the same operation key from another author writes its own write and has its own receipt")
    void rcp04_anotherAuthor(Scenario s) {
        String id = Scenario.uuid();
        SaveResult mine = s.bean(SaveService.class).save(Scenario.AUTHOR, create(s, id, "i"));
        SaveResult theirs = s.bean(SaveService.class).save(Scenario.ISSUE_ONLY, create(s, id, "i"));
        assertThat(theirs.id()).isNotEqualTo(mine.id());
        assertThat(issues(s)).isEqualTo(2);
        assertThat(receipts(s, id)).isEqualTo(2);
        assertThat(s.count("SELECT COUNT(*) FROM request_receipt WHERE operation_key = ? AND author_id = ?", id,
                Scenario.ISSUE_ONLY)).isEqualTo(1);
    }

    @Test
    @DisplayName("SVC-RCP-04 (OPK-004): the same operation key and payload of the same author through the other channel (reviewer) is still a conflict")
    void rcp04_anotherChannel(Scenario s) {
        String id = Scenario.uuid();
        SaveResult i = s.newIssue("i");
        TransitionCommand dispute = new TransitionCommand(id, "issue", i.id(), 1L, "DISPUTED", Scenario.REASON,
                Scenario.EVIDENCE, null, null);
        s.bean(TransitionService.class).transition(Scenario.AUTHOR, dispute);
        Expect.failure(() -> s.bean(ReviewService.class).review(Scenario.AUTHOR, dispute),
                ConflictCode.OPERATION_CONFLICT);
    }

    static Stream<Named<BiFunction<Scenario, SaveCommand, SaveCommand>>> samePayloadSpelledDifferently() {
        return Stream.of(
                Named.of("reason padded with spaces", (s, c) -> new SaveCommand(c.operationKey(), c.entity(), c.id(),
                        c.expectedVersion(), c.fields(), "   " + c.reason() + "   ")),
                Named.of("expected version absent instead of 0", (s, c) -> new SaveCommand(c.operationKey(), c.entity(),
                        c.id(), null, c.fields(), c.reason())),
                Named.of("blank id instead of absent", (s, c) -> new SaveCommand(c.operationKey(), c.entity(), "  ",
                        c.expectedVersion(), c.fields(), c.reason())),
                Named.of("an optional field blank instead of absent", (s, c) -> {
                    Map<String, String> fields = new LinkedHashMap<>(s.issueFields("i"));
                    fields.put("owner_actor_id", "   ");
                    return new SaveCommand(c.operationKey(), c.entity(), c.id(), c.expectedVersion(), Fields.of(fields),
                            c.reason());
                }),
                Named.of("an optional field null instead of absent", (s, c) -> {
                    Map<String, String> fields = new LinkedHashMap<>(s.issueFields("i"));
                    fields.put("owner_actor_id", null);
                    return new SaveCommand(c.operationKey(), c.entity(), c.id(), c.expectedVersion(), Fields.of(fields),
                            c.reason());
                }));
    }

    @ParameterizedTest
    @MethodSource("samePayloadSpelledDifferently")
    @DisplayName("SVC-RCP-05 / SVC-NORM-04: the same payload spelled differently is a replay")
    void rcp05_normalisedDigest(BiFunction<Scenario, SaveCommand, SaveCommand> spell, Scenario s) {
        String id = Scenario.uuid();
        SaveResult first = s.bean(SaveService.class).save(Scenario.AUTHOR, create(s, id, "i"));
        assertThat(s.bean(SaveService.class).save(Scenario.AUTHOR, spell.apply(s, create(s, id, "i")))).isEqualTo(first);
        assertThat(issues(s)).isEqualTo(1);
    }

    @Test
    @DisplayName("SVC-RCP-06: a failed version check leaves no receipt, the corrected retry works")
    void rcp06_versionConflict(Scenario s) {
        SaveResult i = s.newIssue("i");
        String id = Scenario.uuid();
        Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR, new SaveCommand(id, "issue", i.id(), 9L,
                Fields.of("title", "x"), Scenario.REASON)), ConflictCode.VERSION_CONFLICT);
        assertThat(receipts(s, id)).isZero();
        assertThat(s.bean(SaveService.class).save(Scenario.AUTHOR, new SaveCommand(id, "issue", i.id(), 1L,
                Fields.of("title", "x"), Scenario.REASON)).version()).isEqualTo(2);
        assertThat(receipts(s, id)).isEqualTo(1);
    }

    @Test
    @DisplayName("SVC-RCP-06: a not-found failure leaves no receipt, the corrected retry works")
    void rcp06_notFound(Scenario s) {
        SaveResult i = s.newIssue("i");
        String id = Scenario.uuid();
        Expect.failure(() -> s.bean(TransitionService.class).transition(Scenario.AUTHOR, new TransitionCommand(id,
                "issue", s.issueId(77), 1L, "DISPUTED", Scenario.REASON, Scenario.EVIDENCE, null, null)),
                LookupCode.NOT_FOUND);
        assertThat(receipts(s, id)).isZero();
        assertThat(s.bean(TransitionService.class).transition(Scenario.AUTHOR, new TransitionCommand(id, "issue",
                i.id(), 1L, "DISPUTED", Scenario.REASON, Scenario.EVIDENCE, null, null)).to()).isEqualTo("DISPUTED");
    }

    @Test
    @DisplayName("SVC-RCP-06: a grant failure leaves no receipt, the retry after the grant works")
    void rcp06_grant(Scenario s) {
        String id = Scenario.uuid();
        String actor = "claude:late-grant";
        Expect.failure(() -> s.bean(SaveService.class).save(actor, create(s, id, "i")),
                TrustCode.SESSION_NOT_GRANTED);
        assertThat(receipts(s, id)).isZero();
        s.grant(actor, s.projectId(), "issue");
        assertThat(s.bean(SaveService.class).save(actor, create(s, id, "i")).version()).isEqualTo(1);
    }

    @Test
    @DisplayName("SVC-RCP-07: a transition is replayed after the version moved on")
    void rcp07_transition(Scenario s) {
        SaveResult i = s.newIssue("i");
        TransitionCommand dispute = new TransitionCommand(Scenario.uuid(), "issue", i.id(), 1L, "DISPUTED",
                Scenario.REASON, Scenario.EVIDENCE, null, null);
        TransitionResult first = s.bean(TransitionService.class).transition(Scenario.AUTHOR, dispute);
        s.bean(TransitionService.class).transition(Scenario.AUTHOR, new TransitionCommand(Scenario.uuid(), "issue", i.id(),
                2L, "OPEN", Scenario.REASON, Scenario.EVIDENCE, null, null));
        assertThat(s.bean(TransitionService.class).transition(Scenario.AUTHOR, dispute)).isEqualTo(first);
    }

    @Test
    @DisplayName("SVC-RCP-07: a case is replayed after its issue was merged")
    void rcp07_caseAfterMerge(Scenario s) {
        SaveResult source = s.newIssue("source");
        SaveResult target = s.newIssue("target");
        RecordCaseCommand observed = new RecordCaseCommand(Scenario.uuid(), source.id(), "seen", Scenario.REASON,
                null, null, null);
        var first = s.bean(RecordCaseService.class).recordCase(Scenario.AUTHOR, observed);
        MergeFixture.merge(s, source.id(), 1, target.id());
        assertThat(s.bean(RecordCaseService.class).recordCase(Scenario.AUTHOR, observed)).isEqualTo(first);
    }

    @Test
    @DisplayName("SVC-RCP-07: a deliverable is replayed after the issue was verified")
    void rcp07_deliverable(Scenario s) {
        SaveResult i = s.newIssue("i");
        RecordDeliverableCommand deliverable = s.deliverableCommand("issue", i.id(), Scenario.ref('a'));
        var first = s.bean(RecordDeliverableService.class).recordDeliverable(Scenario.AUTHOR, deliverable);
        s.bean(ReviewService.class).review(Scenario.REVIEWER, s.move("issue", i.id(), 2, "VERIFIED",
                Scenario.EVIDENCE));
        assertThat(s.bean(RecordDeliverableService.class).recordDeliverable(Scenario.AUTHOR, deliverable))
                .isEqualTo(first);
    }

    @Test
    @DisplayName("SVC-RCP-07: a link is replayed after it was removed")
    void rcp07_link(Scenario s) {
        SaveResult i = s.newIssue("i");
        SaveResult t = s.newTask("t");
        LinkCommand link = new LinkCommand(Scenario.uuid(), "issue_task", i.id(), t.id(), Scenario.REASON, null);
        var first = s.bean(LinkService.class).link(Scenario.AUTHOR, link);
        s.bean(LinkService.class).link(Scenario.AUTHOR, new LinkCommand(Scenario.uuid(), "issue_task", i.id(), t.id(),
                Scenario.REASON, true));
        assertThat(s.bean(LinkService.class).link(Scenario.AUTHOR, link)).isEqualTo(first);
    }

    @Test
    @DisplayName("SVC-RCP-08 (OPK-001): without an operation key a call is refused with REQUIRED at operationKey and writes nothing")
    void rcp08_noOperationKey(Scenario s) {
        long receipts = s.count("SELECT COUNT(*) FROM request_receipt");
        long history = s.count("SELECT COUNT(*) FROM history");
        for (int attempt = 0; attempt < 2; attempt++) {
            var failure = Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR, create(s, null, "i")),
                    ValidationCode.VALIDATION_FAILED);
            assertThat(failure.args()).containsExactly("operationKey");
            assertThat(Expect.firstIssue(failure).path()).isEqualTo("operationKey");
            assertThat(Expect.firstIssue(failure).reason()).isEqualTo(ValidationReason.REQUIRED);
        }
        assertThat(issues(s)).isZero();
        assertThat(s.count("SELECT COUNT(*) FROM request_receipt")).isEqualTo(receipts);
        assertThat(s.count("SELECT COUNT(*) FROM history")).isEqualTo(history);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"", " ", "   ", "\t\n"})
    @DisplayName("SVC-RCP-12 (OPK-002): an operation key that is empty or blank is refused (MUST_BE_CHARS_RANGE 1..128 at operationKey)")
    void rcp12_blankOperationKey(String operationKey, Scenario s) {
        assertRefusedAsLength(s, operationKey);
    }

    @Test
    @DisplayName("SVC-RCP-12 (OPK-002): an operation key of 129 characters is refused, one of 128 is written")
    void rcp12_operationKeyLength(Scenario s) {
        assertRefusedAsLength(s, "k".repeat(129));
        assertThat(issues(s)).isZero();
        String longest = "k".repeat(128);
        assertThat(s.bean(SaveService.class).save(Scenario.AUTHOR, create(s, longest, "i")).version()).isEqualTo(1);
        assertThat(receipts(s, longest)).isEqualTo(1);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"not-a-uuid", "12345", "0123456789012345678901234567890123456", "k", "ключ-操作 key"})
    @DisplayName("SVC-RCP-12: any other text of 1 to 128 characters is an operation key, not only a UUID")
    void rcp12_anyTextIsAKey(String operationKey, Scenario s) {
        assertThat(s.bean(SaveService.class).save(Scenario.AUTHOR, create(s, operationKey, "i")).version()).isEqualTo(1);
        assertThat(receipts(s, operationKey)).isEqualTo(1);
    }

    private static void assertRefusedAsLength(Scenario s, String operationKey) {
        long receipts = s.count("SELECT COUNT(*) FROM request_receipt");
        var failure = Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR,
                create(s, operationKey, "i")), ValidationCode.VALIDATION_FAILED);
        assertThat(failure.args()).containsExactly("operationKey");
        assertThat(Expect.firstIssue(failure).path()).isEqualTo("operationKey");
        assertThat(Expect.firstIssue(failure).reason()).isEqualTo(ValidationReason.MUST_BE_CHARS_RANGE);
        assertThat(Expect.firstIssue(failure).reasonArgs()).containsExactly("1", "128");
        assertThat(s.count("SELECT COUNT(*) FROM request_receipt")).isEqualTo(receipts);
    }

    @Test
    @DisplayName("SVC-RCP-13: the stored response survives a restart of the application")
    void rcp13_restart(Scenario s) {
        String id = Scenario.uuid();
        SaveResult first = s.bean(SaveService.class).save(Scenario.AUTHOR, create(s, id, "i"));
        LedgerTestRunners.restartOf(s.ctx(), restarted -> assertThat(
                restarted.getBean(SaveService.class).save(Scenario.AUTHOR, create(s, id, "i"))).isEqualTo(first));
        assertThat(issues(s)).isEqualTo(1);
    }

    @Test
    @DisplayName("SVC-RCP-14 (OPK-005): a batch replay returns the stored results, and every operation replays on its own under its derived key")
    void rcp14_batch(Scenario s) {
        String id = Scenario.uuid();
        List<BatchOpInput> ops = List.of(
                new BatchOpInput.Save("issue", Fields.of(s.issueFields("one")), Scenario.REASON, null, null),
                new BatchOpInput.Save("task", Fields.of(s.taskFields("two")), Scenario.REASON, null, null));
        BatchResult first = s.bean(BatchService.class).batch(Scenario.AUTHOR, id, ops);
        assertThat(s.bean(BatchService.class).batch(Scenario.AUTHOR, id, ops)).isEqualTo(first);
        OperationKey derived = OperationKey.parse(id).derive(1);
        assertThat(s.bean(SaveService.class).save(Scenario.AUTHOR, new SaveCommand(derived.toString(), "task", null,
                0L, Fields.of(s.taskFields("two")), Scenario.REASON))).isEqualTo(first.results().get(1));
        assertThat(issues(s)).isEqualTo(1);
    }

    record KeyedWrite(String name, Function<Scenario, BiFunction<String, String, Object>> prepare) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<KeyedWrite> keyedWrites() {
        return Stream.of(
                new KeyedWrite("save", s -> (key, reason) -> s.bean(SaveService.class).save(Scenario.AUTHOR,
                        new SaveCommand(key, "issue", null, 0L, Fields.of(s.issueFields("i")), reason))),
                new KeyedWrite("transition", s -> {
                    SaveResult i = s.newIssue("i");
                    return (key, reason) -> s.bean(TransitionService.class).transition(Scenario.AUTHOR,
                            new TransitionCommand(key, "issue", i.id(), 1L, "DISPUTED", reason, Scenario.EVIDENCE,
                                    null, null));
                }),
                new KeyedWrite("record_deliverable", s -> {
                    SaveResult i = s.newIssue("i");
                    return (key, reason) -> s.bean(RecordDeliverableService.class).recordDeliverable(Scenario.AUTHOR,
                            new RecordDeliverableCommand(key, "issue", i.id(), "code_change", Scenario.ref('a'), reason,
                                    "repo/e2e", "Deliverable summary", null, null, null, null));
                }),
                new KeyedWrite("record_case", s -> {
                    SaveResult i = s.newIssue("i");
                    return (key, reason) -> s.bean(RecordCaseService.class).recordCase(Scenario.AUTHOR,
                            new RecordCaseCommand(key, i.id(), "seen", reason, null, null, null));
                }),
                new KeyedWrite("link", s -> {
                    SaveResult i = s.newIssue("i");
                    SaveResult t = s.newTask("t");
                    return (key, reason) -> s.bean(LinkService.class).link(Scenario.AUTHOR,
                            new LinkCommand(key, "issue_task", i.id(), t.id(), reason, null));
                }),
                new KeyedWrite("correct", s -> {
                    SaveResult i = s.newIssue("i");
                    return (key, reason) -> s.bean(CorrectService.class).correct(Scenario.AUTHOR,
                            new CorrectCommand(key, i.historyId(), reason));
                }),
                new KeyedWrite("review", s -> {
                    SaveResult i = s.newIssue("i");
                    return (key, reason) -> s.bean(ReviewService.class).review(Scenario.AUTHOR,
                            new TransitionCommand(key, "issue", i.id(), 1L, "DISPUTED", reason, Scenario.EVIDENCE,
                                    null, null));
                }),
                new KeyedWrite("review_correct", s -> {
                    SaveResult i = s.verifiedIssue("i");
                    long verification = s.history(HistoryEntity.ISSUE, i.id()).stream()
                            .filter(h -> h.op() == HistoryOp.TRANSITION && "VERIFIED".equals(h.toState())).findFirst()
                            .orElseThrow().id();
                    return (key, reason) -> s.bean(ReviewService.class).reviewCorrect(Scenario.REVIEWER,
                            new CorrectCommand(key, verification, reason));
                }),
                new KeyedWrite("batch", s -> (key, reason) -> s.bean(BatchService.class).batch(Scenario.AUTHOR, key,
                        List.of(new BatchOpInput.Save("issue", Fields.of(s.issueFields("i")), reason, null, null)))));
    }

    @ParameterizedTest
    @MethodSource("keyedWrites")
    @DisplayName("OPK-003 (service): the same operation key with the same arguments returns the same answer and writes exactly once")
    void opk003_replay(KeyedWrite write, Scenario s) {
        BiFunction<String, String, Object> call = write.prepare().apply(s);
        String key = Scenario.uuid();
        Object first = call.apply(key, Scenario.REASON);
        long history = s.count("SELECT COUNT(*) FROM history");
        long receipts = s.count("SELECT COUNT(*) FROM request_receipt");

        assertThat(call.apply(key, Scenario.REASON)).isEqualTo(first);

        assertThat(s.count("SELECT COUNT(*) FROM history")).isEqualTo(history);
        assertThat(s.count("SELECT COUNT(*) FROM request_receipt")).isEqualTo(receipts);
    }

    @ParameterizedTest
    @MethodSource("keyedWrites")
    @DisplayName("OPK-004 (service): the same operation key with other arguments is OPERATION_CONFLICT and nothing more is written")
    void opk004_otherArguments(KeyedWrite write, Scenario s) {
        BiFunction<String, String, Object> call = write.prepare().apply(s);
        String key = Scenario.uuid();
        call.apply(key, Scenario.REASON);
        long history = s.count("SELECT COUNT(*) FROM history");
        long receipts = s.count("SELECT COUNT(*) FROM request_receipt");

        Expect.failure(() -> call.apply(key, "another reason, long enough"), ConflictCode.OPERATION_CONFLICT);

        assertThat(s.count("SELECT COUNT(*) FROM history")).isEqualTo(history);
        assertThat(s.count("SELECT COUNT(*) FROM request_receipt")).isEqualTo(receipts);
    }

    @Test
    @DisplayName("OPK-004 (service): an operation key already bound to one write service is OPERATION_CONFLICT for any other write service")
    void opk004_anotherServiceWithTheSameKey(Scenario s) {
        List<BiFunction<String, String, Object>> calls = keyedWrites()
                .filter(write -> !"review_correct".equals(write.name()))
                .map(write -> write.prepare().apply(s)).toList();
        for (int i = 0; i < calls.size(); i++) {
            String key = Scenario.uuid();
            calls.get(i).apply(key, Scenario.REASON);
            long history = s.count("SELECT COUNT(*) FROM history");
            BiFunction<String, String, Object> other = calls.get((i + 1) % calls.size());

            Expect.failure(() -> other.apply(key, Scenario.REASON), ConflictCode.OPERATION_CONFLICT);

            assertThat(s.count("SELECT COUNT(*) FROM history")).isEqualTo(history);
        }
    }

    private static long receiptsOf(Scenario s, String operationKey, String author) {
        return s.count("SELECT COUNT(*) FROM request_receipt WHERE operation_key = ? AND author_id = ?", operationKey,
                author);
    }

    @Test
    @DisplayName("OPK-020 (service): a key that author A used first is free for author B")
    void opk020_anotherAuthorWritesItsOwn(Scenario s) {
        String other = s.actorWith("issue");
        String key = Scenario.uuid();
        SaveResult mine = s.bean(SaveService.class).save(Scenario.AUTHOR, create(s, key, "same content"));
        SaveResult theirs = s.bean(SaveService.class).save(other, create(s, key, "same content"));

        assertThat(theirs.id()).isNotEqualTo(mine.id());
        assertThat(theirs.author()).isEqualTo(other);
        assertThat(issues(s)).isEqualTo(2);
        assertThat(receiptsOf(s, key, Scenario.AUTHOR)).isEqualTo(1);
        assertThat(receiptsOf(s, key, other)).isEqualTo(1);
        assertThat(s.count("SELECT COUNT(*) FROM history WHERE operation_key = ?", key)).isEqualTo(2);

        assertThat(s.bean(SaveService.class).save(Scenario.AUTHOR, create(s, key, "same content"))).isEqualTo(mine);
        assertThat(s.bean(SaveService.class).save(other, create(s, key, "same content"))).isEqualTo(theirs);
        assertThat(issues(s)).as("the replays write nothing").isEqualTo(2);
    }

    @Test
    @DisplayName("OPK-020 (service): the same author with other content under the key is OPERATION_CONFLICT")
    void opk020_theSameAuthorWithOtherContent(Scenario s) {
        String other = s.actorWith("issue");
        String key = Scenario.uuid();
        s.bean(SaveService.class).save(Scenario.AUTHOR, create(s, key, "first"));
        s.bean(SaveService.class).save(other, create(s, key, "first"));
        long history = s.count("SELECT COUNT(*) FROM history");

        Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR, create(s, key, "other content")),
                ConflictCode.OPERATION_CONFLICT);
        Expect.failure(() -> s.bean(SaveService.class).save(other, create(s, key, "other content")),
                ConflictCode.OPERATION_CONFLICT);

        assertThat(issues(s)).isEqualTo(2);
        assertThat(s.count("SELECT COUNT(*) FROM history")).isEqualTo(history);
    }

    @Test
    @DisplayName("OPK-020 (service): the same holds for the batch key")
    void opk020_theBatchKey(Scenario s) {
        String other = s.actorWith("issue");
        String key = Scenario.uuid();
        OperationKey derived = OperationKey.parse(key).derive(0);
        List<BatchOpInput> ops = List.of(new BatchOpInput.Save("issue", Fields.of(s.issueFields("one")),
                Scenario.REASON, null, null));
        BatchResult mine = s.bean(BatchService.class).batch(Scenario.AUTHOR, key, ops);
        BatchResult theirs = s.bean(BatchService.class).batch(other, key, ops);

        assertThat(theirs).isNotEqualTo(mine);
        assertThat(issues(s)).isEqualTo(2);
        for (String author : List.of(Scenario.AUTHOR, other)) {
            assertThat(s.count("SELECT COUNT(*) FROM request_receipt WHERE operation_key = ? AND author_id = ? "
                    + "AND operation = 'BATCH'", key, author)).as("batch receipt of %s", author).isEqualTo(1);
            assertThat(receiptsOf(s, derived.toString(), author)).as("operation receipt of %s", author).isEqualTo(1);
        }
        assertThat(s.bean(BatchService.class).batch(other, key, ops)).isEqualTo(theirs);
        assertThat(issues(s)).as("the replay writes nothing").isEqualTo(2);

        List<BatchOpInput> changed = List.of(new BatchOpInput.Save("issue", Fields.of(s.issueFields("other")),
                Scenario.REASON, null, null));
        Expect.failure(() -> s.bean(BatchService.class).batch(other, key, changed), ConflictCode.OPERATION_CONFLICT);
        Expect.failure(() -> s.bean(BatchService.class).batch(Scenario.AUTHOR, key, changed),
                ConflictCode.OPERATION_CONFLICT);
        assertThat(issues(s)).isEqualTo(2);
    }

    @ParameterizedTest
    @MethodSource("keyedWrites")
    @DisplayName("OPK-001 (service): a write service called without an operation key is VALIDATION_FAILED, REQUIRED at operationKey, and writes nothing")
    void opk001_noKey(KeyedWrite write, Scenario s) {
        BiFunction<String, String, Object> call = write.prepare().apply(s);
        long history = s.count("SELECT COUNT(*) FROM history");
        long receipts = s.count("SELECT COUNT(*) FROM request_receipt");

        LedgerException failure = Expect.failure(() -> call.apply(null, Scenario.REASON),
                ValidationCode.VALIDATION_FAILED);

        assertThat(failure.args()).containsExactly("operationKey");
        assertThat(Expect.firstIssue(failure).path()).isEqualTo("operationKey");
        assertThat(Expect.firstIssue(failure).reason()).isEqualTo(ValidationReason.REQUIRED);
        assertThat(s.count("SELECT COUNT(*) FROM history")).isEqualTo(history);
        assertThat(s.count("SELECT COUNT(*) FROM request_receipt")).isEqualTo(receipts);
    }

    @ParameterizedTest
    @MethodSource("keyedWrites")
    @DisplayName("OPK-002 (service): an operation key that is empty, blank or 129 characters long is VALIDATION_FAILED")
    void opk002_keyLength(KeyedWrite write, Scenario s) {
        BiFunction<String, String, Object> call = write.prepare().apply(s);
        long history = s.count("SELECT COUNT(*) FROM history");
        long receipts = s.count("SELECT COUNT(*) FROM request_receipt");

        for (String bad : List.of("", "   ", "k".repeat(129))) {
            LedgerException failure = Expect.failure(() -> call.apply(bad, Scenario.REASON),
                    ValidationCode.VALIDATION_FAILED);
            assertThat(failure.args()).as("key of %d characters", bad.length()).containsExactly("operationKey");
            assertThat(Expect.firstIssue(failure).reason()).isEqualTo(ValidationReason.MUST_BE_CHARS_RANGE);
            assertThat(Expect.firstIssue(failure).reasonArgs()).containsExactly("1", "128");
        }
        assertThat(s.count("SELECT COUNT(*) FROM history")).isEqualTo(history);
        assertThat(s.count("SELECT COUNT(*) FROM request_receipt")).isEqualTo(receipts);

        String longest = Scenario.uuid() + "k".repeat(128 - 36);
        assertThat(longest).hasSize(128);
        assertThat(call.apply(longest, Scenario.REASON)).isNotNull();
        assertThat(s.count("SELECT COUNT(*) FROM history")).isGreaterThan(history);
    }
}
