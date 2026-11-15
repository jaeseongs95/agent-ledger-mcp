package org.pgledger.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.application.read.HistoryService;
import org.pgledger.application.read.HistoryService.HistoryQuery;
import org.pgledger.application.read.HistoryService.HistoryView;
import org.pgledger.application.support.Expect;
import org.pgledger.application.support.LedgerServiceTest;
import org.pgledger.application.support.Scenario;
import org.pgledger.application.write.BatchOpInput;
import org.pgledger.application.write.BatchResult;
import org.pgledger.application.write.BatchService;
import org.pgledger.application.write.CorrectCommand;
import org.pgledger.application.write.CorrectService;
import org.pgledger.application.write.RecordCaseCommand;
import org.pgledger.application.write.RecordCaseService;
import org.pgledger.application.write.RecordFixCommand;
import org.pgledger.application.write.RecordFixService;
import org.pgledger.application.write.ReviewService;
import org.pgledger.application.write.TransitionCommand;
import org.pgledger.application.write.TransitionService;
import org.pgledger.application.write.WriteResult;
import org.pgledger.application.write.WriteResult.RecordFixResult;
import org.pgledger.application.write.WriteResult.SaveResult;
import org.pgledger.application.write.WriteResult.TransitionResult;
import org.pgledger.database.repository.AttachmentRepository;
import org.pgledger.domain.error.ConflictCode;
import org.pgledger.domain.error.LedgerException;
import org.pgledger.domain.error.LookupCode;
import org.pgledger.domain.error.ValidationCode;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.model.Attachment;
import org.pgledger.domain.value.AuthorId;
import org.pgledger.domain.value.Sha256;

/**
 * ATA-012 (the service side): the optional {@code attachment_id} of the record tools. The attachment rows are inserted through
 * the repository (the body is not needed: the check of a record reads the row only). Each case below is one write: a tool of
 * the author or reviewer channel, or one operation of a batch. Where the id lands: the row the operation writes for its entity.
 */
@LedgerServiceTest
class AttachmentRecordTest {

    private static final Instant WHEN = Instant.parse("2026-11-05T09:00:00Z");

    /** What a case has made ready: the defect it works on and the call (attachment id, request id) that writes. */
    private interface Call {
        Object run(String attachmentId, String requestId);
    }

    private record Prepared(String defectId, Call call) {
    }

    /** One write path; {@code actor} writes it, {@code stranger} is another author whose attachment must be refused. */
    private record Case(String name, String actor, String stranger, Function<Scenario, Prepared> prepare) {

        @Override
        public String toString() {
            return name;
        }
    }

    private static Case author(String name, Function<Scenario, Prepared> prepare) {
        return new Case(name, Scenario.AUTHOR, Scenario.REVIEWER, prepare);
    }

    private static Case reviewer(String name, Function<Scenario, Prepared> prepare) {
        return new Case(name, Scenario.REVIEWER, Scenario.AUTHOR, prepare);
    }

    private static TransitionCommand dispute(Scenario s, String defectId, String attachmentId, String requestId) {
        return new TransitionCommand(requestId, "defect", defectId, 1L, "DISPUTED", Scenario.REASON, Scenario.EVIDENCE,
                null, null, attachmentId);
    }

    private static RecordFixCommand fix(String defectId, String attachmentId, String requestId) {
        return new RecordFixCommand(requestId, defectId, "repo/e2e", Scenario.commit('a'), Scenario.REASON, null, null,
                null, null, null, attachmentId);
    }

    private static RecordCaseCommand observe(String defectId, String attachmentId, String requestId) {
        return new RecordCaseCommand(requestId, defectId, "seen", Scenario.REASON, null, null, null, attachmentId);
    }

    /** The six tools (the reviewer channel's two are review and review_correct) and one batch operation of each kind that a batch holds. */
    static Stream<Case> cases() {
        return Stream.of(
                author("ledger_transition", s -> {
                    SaveResult d = s.newDefect("d");
                    return new Prepared(d.id(), (att, rid) -> s.bean(TransitionService.class).transition(Scenario.AUTHOR,
                            dispute(s, d.id(), att, rid)));
                }),
                reviewer("ledger_review", s -> {
                    SaveResult d = s.sourceFixedDefect("d");
                    return new Prepared(d.id(), (att, rid) -> s.bean(ReviewService.class).review(Scenario.REVIEWER,
                            new TransitionCommand(rid, "defect", d.id(), 2L, "VERIFIED", Scenario.REASON,
                                    Scenario.EVIDENCE, null, null, att)));
                }),
                reviewer("ledger_review_correct", s -> {
                    SaveResult d = s.sourceFixedDefect("d");
                    TransitionResult verified = s.bean(ReviewService.class).review(Scenario.REVIEWER,
                            s.move("defect", d.id(), 2, "VERIFIED", Scenario.EVIDENCE));
                    return new Prepared(d.id(), (att, rid) -> s.bean(ReviewService.class).reviewCorrect(
                            Scenario.REVIEWER, new CorrectCommand(rid, verified.historyId(), Scenario.REASON, att)));
                }),
                author("ledger_correct", s -> {
                    SaveResult d = s.newDefect("d");
                    return new Prepared(d.id(), (att, rid) -> s.bean(CorrectService.class).correct(Scenario.AUTHOR,
                            new CorrectCommand(rid, d.historyId(), Scenario.REASON, att)));
                }),
                author("ledger_record_fix", s -> {
                    SaveResult d = s.newDefect("d");
                    return new Prepared(d.id(), (att, rid) -> s.bean(RecordFixService.class).recordFix(Scenario.AUTHOR,
                            fix(d.id(), att, rid)));
                }),
                author("ledger_record_case", s -> {
                    SaveResult d = s.newDefect("d");
                    return new Prepared(d.id(), (att, rid) -> s.bean(RecordCaseService.class).recordCase(
                            Scenario.AUTHOR, observe(d.id(), att, rid)));
                }),
                author("ledger_batch: transition", s -> {
                    SaveResult d = s.newDefect("d");
                    return new Prepared(d.id(), (att, rid) -> s.bean(BatchService.class).batch(Scenario.AUTHOR, null,
                            List.of(new BatchOpInput.Transition("defect", d.id(), 1L, "DISPUTED", Scenario.REASON,
                                    Scenario.EVIDENCE, null, null, att, rid))));
                }),
                author("ledger_batch: record_fix", s -> {
                    SaveResult d = s.newDefect("d");
                    return new Prepared(d.id(), (att, rid) -> s.bean(BatchService.class).batch(Scenario.AUTHOR, null,
                            List.of(new BatchOpInput.RecordFix(d.id(), "repo/e2e", Scenario.commit('a'),
                                    Scenario.REASON, null, null, null, null, null, att, rid))));
                }),
                author("ledger_batch: record_case", s -> {
                    SaveResult d = s.newDefect("d");
                    return new Prepared(d.id(), (att, rid) -> s.bean(BatchService.class).batch(Scenario.AUTHOR, null,
                            List.of(new BatchOpInput.RecordCase(d.id(), "seen", Scenario.REASON, null, null, null, att,
                                    rid))));
                }),
                author("ledger_batch: correct", s -> {
                    SaveResult d = s.newDefect("d");
                    return new Prepared(d.id(), (att, rid) -> s.bean(BatchService.class).batch(Scenario.AUTHOR, null,
                            List.of(new BatchOpInput.Correct(d.historyId(), Scenario.REASON, att, rid))));
                }));
    }

    // ------------------------------------------------------------------ helpers

    /** An attachment row of {@code author}, as a finished upload leaves it; returns its id. */
    private static String attachmentOf(Scenario s, String author) {
        String id = UUID.randomUUID().toString();
        Attachment row = new Attachment(id, "report.md", "text/markdown", 4, Sha256.ofText("body"),
                AuthorId.of(author), WHEN);
        s.ctx().tx(() -> {
            s.bean(AttachmentRepository.class).insert(row);
            return null;
        });
        return id;
    }

    /** The history row that an operation wrote for its entity (the first operation of a batch). */
    private static long historyId(Object result) {
        return switch (result) {
            case TransitionResult r -> r.historyId();
            case RecordFixResult r -> r.historyId();
            case WriteResult.RecordCaseResult r -> r.historyId();
            case WriteResult.CorrectResult r -> r.historyId();
            case BatchResult r -> historyId(r.results().getFirst());
            default -> throw new IllegalStateException("not a write result: " + result);
        };
    }

    private static long receipts(Scenario s, String requestId) {
        return s.count("SELECT COUNT(*) FROM request_receipt WHERE request_id = ?", requestId);
    }

    private static long historyOf(Scenario s, String defectId) {
        return s.count("SELECT COUNT(*) FROM history WHERE entity_id = ? OR entity_id LIKE ? OR entity_id LIKE ?",
                defectId, defectId + "@%", defectId + "|%");
    }

    private static long referencing(Scenario s, String attachmentId) {
        return s.count("SELECT COUNT(*) FROM history WHERE attachment_id = ?", attachmentId);
    }

    /** What a failed record must leave as it was: the defect, its fixes and cases, its history rows. */
    private record State(long version, long fixes, long cases, long history) {

        static State of(Scenario s, String defectId) {
            return new State(s.defect(defectId).version(),
                    s.count("SELECT COUNT(*) FROM defect_fix WHERE defect_id = ?", defectId),
                    s.count("SELECT COUNT(*) FROM defect_case WHERE defect_id = ?", defectId), historyOf(s, defectId));
        }
    }

    private static HistoryView read(Scenario s, long historyId) {
        List<HistoryView> rows = s.bean(HistoryService.class).history(new HistoryQuery(List.of(historyId), null, null,
                null, null, null, null, null, null, null, null, null, null)).items();
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    // ------------------------------------------------------------------ round trip

    @ParameterizedTest
    @MethodSource("cases")
    @DisplayName("ATA-012: an attachment of the writing author is stored in history.attachment_id on the row of the operation's entity, and the history read returns it")
    void ata012_roundTrip(Case c, Scenario s) {
        Prepared work = c.prepare().apply(s);
        String attachment = attachmentOf(s, c.actor());

        Object result = work.call().run(attachment, Scenario.uuid());

        long row = historyId(result);
        assertThat(s.historyRow(row).attachmentId()).isEqualTo(attachment);
        assertThat(read(s, row).attachmentId()).isEqualTo(attachment);
        assertThat(referencing(s, attachment)).as("only the main row of the operation carries it").isEqualTo(1);
    }

    @Test
    @DisplayName("ATA-012: record_fix puts the id on the DEFECT_FIX row, not on the defect's own TRANSITION row that the same call writes")
    void ata012_recordFixCarriesItOnTheFixRow(Scenario s) {
        SaveResult d = s.newDefect("d");
        String attachment = attachmentOf(s, Scenario.AUTHOR);

        RecordFixResult result = s.bean(RecordFixService.class).recordFix(Scenario.AUTHOR,
                fix(d.id(), attachment, null));

        assertThat(result.transitionHistoryId()).as("the defect moved to SOURCE_FIXED").isNotNull();
        assertThat(s.historyRow(result.historyId()).attachmentId()).isEqualTo(attachment);
        assertThat(s.historyRow(result.transitionHistoryId()).attachmentId()).isNull();
    }

    @Test
    @DisplayName("ATA-012: a record without attachment_id has none, and the history read shows null")
    void ata012_withoutAttachmentIsNull(Scenario s) {
        SaveResult d = s.newDefect("d");

        TransitionResult moved = s.bean(TransitionService.class).transition(Scenario.AUTHOR,
                s.move("defect", d.id(), 1, "DISPUTED", Scenario.EVIDENCE));

        assertThat(s.historyRow(moved.historyId()).attachmentId()).isNull();
        assertThat(read(s, moved.historyId()).attachmentId()).isNull();
        assertThat(read(s, d.historyId()).attachmentId()).isNull();
    }

    @Test
    @DisplayName("ATA-012: the operations of one batch each carry their own id on their own row")
    void ata012_batchOperationsCarryIt(Scenario s) {
        SaveResult d = s.newDefect("d");
        String first = attachmentOf(s, Scenario.AUTHOR);
        String second = attachmentOf(s, Scenario.AUTHOR);

        BatchResult result = s.bean(BatchService.class).batch(Scenario.AUTHOR, null, List.of(
                new BatchOpInput.RecordCase(d.id(), "seen", Scenario.REASON, null, null, null, first, null),
                new BatchOpInput.RecordCase(d.id(), "again", Scenario.REASON, null, null, null, second, null),
                new BatchOpInput.RecordCase(d.id(), "no file", Scenario.REASON, null, null, null, null, null)));

        assertThat(result.count()).isEqualTo(3);
        assertThat(s.historyRow(historyId(result.results().get(0))).attachmentId()).isEqualTo(first);
        assertThat(s.historyRow(historyId(result.results().get(1))).attachmentId()).isEqualTo(second);
        assertThat(s.historyRow(historyId(result.results().get(2))).attachmentId()).isNull();
    }

    // ------------------------------------------------------------------ refusals write nothing

    @ParameterizedTest
    @MethodSource("cases")
    @DisplayName("ATA-012: an id that does not exist fails the whole record with ARTIFACT_NOT_FOUND and writes nothing: no state change, no history row, no receipt")
    void ata012_missingAttachment(Case c, Scenario s) {
        Prepared work = c.prepare().apply(s);
        State before = State.of(s, work.defectId());
        String requestId = Scenario.uuid();
        String missing = Scenario.uuid();

        LedgerException failure = Expect.failure(() -> work.call().run(missing, requestId),
                LookupCode.ARTIFACT_NOT_FOUND);

        assertThat(failure.args()).containsExactly(missing);
        assertThat(State.of(s, work.defectId())).isEqualTo(before);
        assertThat(receipts(s, requestId)).isZero();
        assertThat(s.count("SELECT COUNT(*) FROM history WHERE request_id = ?", requestId)).isZero();
    }

    @ParameterizedTest
    @MethodSource("cases")
    @DisplayName("ATA-012: the attachment of another author fails the whole record with ARTIFACT_NOT_FOUND and writes nothing")
    void ata012_attachmentOfAnotherAuthor(Case c, Scenario s) {
        Prepared work = c.prepare().apply(s);
        String foreign = attachmentOf(s, c.stranger());
        State before = State.of(s, work.defectId());
        String requestId = Scenario.uuid();

        Expect.failure(() -> work.call().run(foreign, requestId), LookupCode.ARTIFACT_NOT_FOUND);

        assertThat(State.of(s, work.defectId())).isEqualTo(before);
        assertThat(receipts(s, requestId)).isZero();
        assertThat(s.count("SELECT COUNT(*) FROM history WHERE request_id = ?", requestId)).isZero();
        assertThat(referencing(s, foreign)).isZero();
    }

    @ParameterizedTest
    @MethodSource("cases")
    @DisplayName("ATA-012: an id that is not a UUID is a validation failure and writes nothing")
    void ata012_notAUuid(Case c, Scenario s) {
        Prepared work = c.prepare().apply(s);
        State before = State.of(s, work.defectId());
        String requestId = Scenario.uuid();

        LedgerException failure = Expect.failure(() -> work.call().run("not-a-uuid", requestId),
                ValidationCode.VALIDATION_FAILED);

        assertThat(failure).isInstanceOf(ValidationException.class);
        assertThat(Expect.firstIssue(failure).path()).isEqualTo("attachment_id");
        assertThat(State.of(s, work.defectId())).isEqualTo(before);
        assertThat(receipts(s, requestId)).isZero();
    }

    @Test
    @DisplayName("ATA-012: in a batch the bad id of a later operation rolls the earlier operations back and names the operation")
    void ata012_batchIsOneTransaction(Scenario s) {
        SaveResult d = s.newDefect("d");
        String mine = attachmentOf(s, Scenario.AUTHOR);
        State before = State.of(s, d.id());
        String firstRequest = Scenario.uuid();

        LedgerException failure = Expect.failure(() -> s.bean(BatchService.class).batch(Scenario.AUTHOR, null, List.of(
                new BatchOpInput.RecordCase(d.id(), "seen", Scenario.REASON, null, null, null, mine, firstRequest),
                new BatchOpInput.RecordCase(d.id(), "bad", Scenario.REASON, null, null, null, Scenario.uuid(), null))),
                LookupCode.ARTIFACT_NOT_FOUND);

        assertThat(failure.details()).containsEntry("op_index", List.of("2")).containsEntry("op", List.of("record_case"));
        assertThat(State.of(s, d.id())).isEqualTo(before);
        assertThat(referencing(s, mine)).isZero();
        assertThat(receipts(s, firstRequest)).isZero();
    }

    // ------------------------------------------------------------------ receipt digest

    @ParameterizedTest
    @MethodSource("cases")
    @DisplayName("ATA-012: the same request id with the same attachment replays the stored response; with another attachment, or without one, it is REQUEST_CONFLICT")
    void ata012_replay(Case c, Scenario s) {
        Prepared work = c.prepare().apply(s);
        String attachment = attachmentOf(s, c.actor());
        String other = attachmentOf(s, c.actor());
        String requestId = Scenario.uuid();
        Object first = work.call().run(attachment, requestId);
        long rows = historyOf(s, work.defectId());

        Object again = work.call().run(attachment, requestId);

        assertThat(again).isEqualTo(first);
        assertThat(historyOf(s, work.defectId())).as("a replay writes nothing").isEqualTo(rows);
        Expect.failure(() -> work.call().run(other, requestId), ConflictCode.REQUEST_CONFLICT);
        Expect.failure(() -> work.call().run(null, requestId), ConflictCode.REQUEST_CONFLICT);
        assertThat(referencing(s, other)).isZero();
        assertThat(historyOf(s, work.defectId())).isEqualTo(rows);
    }
}
