// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application;

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
import org.veridger.application.read.HistoryService;
import org.veridger.application.read.HistoryService.HistoryQuery;
import org.veridger.application.read.HistoryService.HistoryView;
import org.veridger.application.support.Expect;
import org.veridger.application.support.LedgerServiceTest;
import org.veridger.application.support.Scenario;
import org.veridger.application.write.BatchOpInput;
import org.veridger.application.write.BatchResult;
import org.veridger.application.write.BatchService;
import org.veridger.application.write.CorrectCommand;
import org.veridger.application.write.CorrectService;
import org.veridger.application.write.RecordCaseCommand;
import org.veridger.application.write.RecordCaseService;
import org.veridger.application.write.RecordDeliverableCommand;
import org.veridger.application.write.RecordDeliverableService;
import org.veridger.application.write.ReviewService;
import org.veridger.application.write.TransitionCommand;
import org.veridger.application.write.TransitionService;
import org.veridger.application.write.WriteResult;
import org.veridger.application.write.WriteResult.RecordDeliverableResult;
import org.veridger.application.write.WriteResult.SaveResult;
import org.veridger.application.write.WriteResult.TransitionResult;
import org.veridger.database.repository.AttachmentRepository;
import org.veridger.domain.error.ConflictCode;
import org.veridger.domain.error.LedgerException;
import org.veridger.domain.error.LookupCode;
import org.veridger.domain.error.ValidationCode;
import org.veridger.domain.error.ValidationException;
import org.veridger.domain.model.Attachment;
import org.veridger.domain.value.AuthorId;
import org.veridger.domain.value.OperationKey;

/**
 * ATA-012 (the service side): optional {@code attachment_id} across author, reviewer, and batch write operations.
 */
@LedgerServiceTest
class AttachmentRecordTest {

    private static final Instant WHEN = Instant.parse("2026-11-05T09:00:00Z");

    private interface Call {
        Object run(String attachmentId, String operationKey);
    }

    private record Prepared(String issueId, Call call) {
    }

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

    private static TransitionCommand dispute(String issueId, String attachmentId, String operationKey) {
        return new TransitionCommand(operationKey, "issue", issueId, 1L, "DISPUTED", Scenario.REASON,
                Scenario.EVIDENCE, null, null, attachmentId);
    }

    private static RecordDeliverableCommand deliverable(String issueId, String attachmentId, String operationKey) {
        return new RecordDeliverableCommand(operationKey, "issue", issueId, "code_change", Scenario.ref('a'),
                Scenario.REASON, "repo/e2e", "Deliverable summary", null, null, null, null, attachmentId);
    }

    private static RecordCaseCommand observe(String issueId, String attachmentId, String operationKey) {
        return new RecordCaseCommand(operationKey, issueId, "seen", Scenario.REASON, null, null, null, attachmentId);
    }

    static Stream<Case> cases() {
        return Stream.of(
                author("ledger_transition", s -> {
                    SaveResult i = s.newIssue("i");
                    return new Prepared(i.id(), (att, rid) -> s.bean(TransitionService.class).transition(
                            Scenario.AUTHOR, dispute(i.id(), att, rid)));
                }),
                reviewer("ledger_review", s -> {
                    SaveResult i = s.resolvedIssue("i");
                    return new Prepared(i.id(), (att, rid) -> s.bean(ReviewService.class).review(Scenario.REVIEWER,
                            new TransitionCommand(rid, "issue", i.id(), 2L, "VERIFIED", Scenario.REASON,
                                    Scenario.EVIDENCE, null, null, att)));
                }),
                reviewer("ledger_review_correct", s -> {
                    SaveResult i = s.resolvedIssue("i");
                    TransitionResult verified = s.bean(ReviewService.class).review(Scenario.REVIEWER,
                            s.move("issue", i.id(), 2, "VERIFIED", Scenario.EVIDENCE));
                    return new Prepared(i.id(), (att, rid) -> s.bean(ReviewService.class).reviewCorrect(
                            Scenario.REVIEWER, new CorrectCommand(rid, verified.historyId(), Scenario.REASON, att)));
                }),
                author("ledger_correct", s -> {
                    SaveResult i = s.newIssue("i");
                    return new Prepared(i.id(), (att, rid) -> s.bean(CorrectService.class).correct(Scenario.AUTHOR,
                            new CorrectCommand(rid, i.historyId(), Scenario.REASON, att)));
                }),
                author("ledger_record_deliverable", s -> {
                    SaveResult i = s.newIssue("i");
                    return new Prepared(i.id(), (att, rid) -> s.bean(RecordDeliverableService.class)
                            .recordDeliverable(Scenario.AUTHOR, deliverable(i.id(), att, rid)));
                }),
                author("ledger_record_case", s -> {
                    SaveResult i = s.newIssue("i");
                    return new Prepared(i.id(), (att, rid) -> s.bean(RecordCaseService.class).recordCase(
                            Scenario.AUTHOR, observe(i.id(), att, rid)));
                }),
                author("ledger_batch: transition", s -> {
                    SaveResult i = s.newIssue("i");
                    return new Prepared(i.id(), (att, rid) -> s.bean(BatchService.class).batch(Scenario.AUTHOR, rid,
                            List.of(new BatchOpInput.Transition("issue", i.id(), 1L, "DISPUTED", Scenario.REASON,
                                    Scenario.EVIDENCE, null, null, att))));
                }),
                author("ledger_batch: record_deliverable", s -> {
                    SaveResult i = s.newIssue("i");
                    return new Prepared(i.id(), (att, rid) -> s.bean(BatchService.class).batch(Scenario.AUTHOR, rid,
                            List.of(new BatchOpInput.RecordDeliverable("issue", i.id(), "code_change",
                                    Scenario.ref('a'), Scenario.REASON, "repo/e2e", "Deliverable summary", null, null,
                                    null, null, att))));
                }),
                author("ledger_batch: record_case", s -> {
                    SaveResult i = s.newIssue("i");
                    return new Prepared(i.id(), (att, rid) -> s.bean(BatchService.class).batch(Scenario.AUTHOR, rid,
                            List.of(new BatchOpInput.RecordCase(i.id(), "seen", Scenario.REASON, null, null, null,
                                    att))));
                }),
                author("ledger_batch: correct", s -> {
                    SaveResult i = s.newIssue("i");
                    return new Prepared(i.id(), (att, rid) -> s.bean(BatchService.class).batch(Scenario.AUTHOR, rid,
                            List.of(new BatchOpInput.Correct(i.historyId(), Scenario.REASON, att))));
                }));
    }

    private static String attachmentOf(Scenario s, String author) {
        String id = UUID.randomUUID().toString();
        Attachment row = new Attachment(id, AuthorId.of(author), WHEN);
        s.ctx().tx(() -> {
            s.bean(AttachmentRepository.class).insert(row);
            return null;
        });
        return id;
    }

    private static long historyId(Object result) {
        return switch (result) {
            case TransitionResult r -> r.historyId();
            case RecordDeliverableResult r -> r.historyId();
            case WriteResult.RecordCaseResult r -> r.historyId();
            case WriteResult.CorrectResult r -> r.historyId();
            case WriteResult.ProjectVersionResult r -> throw new AssertionError("version changes do not attach evidence");
            case BatchResult r -> historyId(r.results().getFirst());
            default -> throw new IllegalStateException("not a write result: " + result);
        };
    }

    private static String derived(String operationKey) {
        return OperationKey.parse(operationKey).derive(0).toString();
    }

    private static long receipts(Scenario s, String operationKey) {
        return s.count("SELECT COUNT(*) FROM request_receipt WHERE operation_key IN (?, ?)", operationKey,
                derived(operationKey));
    }

    private static long historyRows(Scenario s, String operationKey) {
        return s.count("SELECT COUNT(*) FROM history WHERE operation_key IN (?, ?)", operationKey,
                derived(operationKey));
    }

    private static long historyOf(Scenario s, String issueId) {
        return s.count("SELECT COUNT(*) FROM history WHERE entity_id = ? OR entity_id LIKE ? OR entity_id LIKE ?",
                issueId, "issue:" + issueId + "@%", issueId + "|%");
    }

    private static long referencing(Scenario s, String attachmentId) {
        return s.count("SELECT COUNT(*) FROM history WHERE attachment_id = ?", attachmentId);
    }

    private record State(long version, long deliverables, long cases, long history) {

        static State of(Scenario s, String issueId) {
            return new State(s.issue(issueId).version(),
                    s.count("SELECT COUNT(*) FROM deliverable WHERE target_entity = 'ISSUE' AND target_id = ?",
                            issueId),
                    s.count("SELECT COUNT(*) FROM issue_case WHERE issue_id = ?", issueId),
                    historyOf(s, issueId));
        }
    }

    private static HistoryView read(Scenario s, long historyId) {
        List<HistoryView> rows = s.bean(HistoryService.class).history(new HistoryQuery(List.of(historyId), null, null,
                null, null, null, null, null, null, null, null, null, null)).items();
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    @ParameterizedTest
    @MethodSource("cases")
    @DisplayName("ATA-012: an attachment of the writing author is stored in history.attachment_id")
    void ata012_roundTrip(Case c, Scenario s) {
        Prepared work = c.prepare().apply(s);
        String attachment = attachmentOf(s, c.actor());

        Object result = work.call().run(attachment, Scenario.uuid());

        long row = historyId(result);
        assertThat(s.historyRow(row).attachmentId()).isEqualTo(attachment);
        assertThat(read(s, row).attachmentId()).isEqualTo(attachment);
        assertThat(referencing(s, attachment)).isEqualTo(1);
    }

    @Test
    @DisplayName("ATA-012: record_deliverable puts the id on the DELIVERABLE row, not on the target's TRANSITION row")
    void ata012_recordDeliverableCarriesItOnTheDeliverableRow(Scenario s) {
        SaveResult i = s.newIssue("i");
        String attachment = attachmentOf(s, Scenario.AUTHOR);

        RecordDeliverableResult result = s.bean(RecordDeliverableService.class).recordDeliverable(Scenario.AUTHOR,
                deliverable(i.id(), attachment, Scenario.uuid()));

        assertThat(result.transitionHistoryId()).isNotNull();
        assertThat(s.historyRow(result.historyId()).attachmentId()).isEqualTo(attachment);
        assertThat(s.historyRow(result.transitionHistoryId()).attachmentId()).isNull();
    }

    @Test
    @DisplayName("ATA-012: a record without attachment_id has none")
    void ata012_withoutAttachmentIsNull(Scenario s) {
        SaveResult i = s.newIssue("i");

        TransitionResult moved = s.bean(TransitionService.class).transition(Scenario.AUTHOR,
                s.move("issue", i.id(), 1, "DISPUTED", Scenario.EVIDENCE));

        assertThat(s.historyRow(moved.historyId()).attachmentId()).isNull();
        assertThat(read(s, moved.historyId()).attachmentId()).isNull();
        assertThat(read(s, i.historyId()).attachmentId()).isNull();
    }

    @Test
    @DisplayName("ATA-012: the operations of one batch each carry their own id on their own row")
    void ata012_batchOperationsCarryIt(Scenario s) {
        SaveResult i = s.newIssue("i");
        String first = attachmentOf(s, Scenario.AUTHOR);
        String second = attachmentOf(s, Scenario.AUTHOR);

        BatchResult result = s.bean(BatchService.class).batch(Scenario.AUTHOR, Scenario.uuid(), List.of(
                new BatchOpInput.RecordCase(i.id(), "seen", Scenario.REASON, null, null, null, first),
                new BatchOpInput.RecordCase(i.id(), "again", Scenario.REASON, null, null, null, second),
                new BatchOpInput.RecordCase(i.id(), "no file", Scenario.REASON, null, null, null, null)));

        assertThat(result.count()).isEqualTo(3);
        assertThat(s.historyRow(historyId(result.results().get(0))).attachmentId()).isEqualTo(first);
        assertThat(s.historyRow(historyId(result.results().get(1))).attachmentId()).isEqualTo(second);
        assertThat(s.historyRow(historyId(result.results().get(2))).attachmentId()).isNull();
    }

    @ParameterizedTest
    @MethodSource("cases")
    @DisplayName("ATA-012: an id that does not exist fails with ARTIFACT_NOT_FOUND and writes nothing")
    void ata012_missingAttachment(Case c, Scenario s) {
        Prepared work = c.prepare().apply(s);
        State before = State.of(s, work.issueId());
        String operationKey = Scenario.uuid();
        String missing = Scenario.uuid();

        LedgerException failure = Expect.failure(() -> work.call().run(missing, operationKey),
                LookupCode.ARTIFACT_NOT_FOUND);

        assertThat(failure.args()).containsExactly(missing);
        assertThat(State.of(s, work.issueId())).isEqualTo(before);
        assertThat(receipts(s, operationKey)).isZero();
        assertThat(historyRows(s, operationKey)).isZero();
    }

    @ParameterizedTest
    @MethodSource("cases")
    @DisplayName("ATA-012: the attachment of another author fails with ARTIFACT_NOT_FOUND and writes nothing")
    void ata012_attachmentOfAnotherAuthor(Case c, Scenario s) {
        Prepared work = c.prepare().apply(s);
        String foreign = attachmentOf(s, c.stranger());
        State before = State.of(s, work.issueId());
        String operationKey = Scenario.uuid();

        Expect.failure(() -> work.call().run(foreign, operationKey), LookupCode.ARTIFACT_NOT_FOUND);

        assertThat(State.of(s, work.issueId())).isEqualTo(before);
        assertThat(receipts(s, operationKey)).isZero();
        assertThat(historyRows(s, operationKey)).isZero();
        assertThat(referencing(s, foreign)).isZero();
    }

    @ParameterizedTest
    @MethodSource("cases")
    @DisplayName("ATA-012: an id that is not a UUID is a validation failure and writes nothing")
    void ata012_notAUuid(Case c, Scenario s) {
        Prepared work = c.prepare().apply(s);
        State before = State.of(s, work.issueId());
        String operationKey = Scenario.uuid();

        LedgerException failure = Expect.failure(() -> work.call().run("not-a-uuid", operationKey),
                ValidationCode.VALIDATION_FAILED);

        assertThat(failure).isInstanceOf(ValidationException.class);
        assertThat(Expect.firstIssue(failure).path()).isEqualTo("attachment_id");
        assertThat(State.of(s, work.issueId())).isEqualTo(before);
        assertThat(receipts(s, operationKey)).isZero();
        assertThat(historyRows(s, operationKey)).isZero();
    }

    @Test
    @DisplayName("ATA-012: in a batch a bad attachment id rolls back earlier operations")
    void ata012_batchIsOneTransaction(Scenario s) {
        SaveResult i = s.newIssue("i");
        String mine = attachmentOf(s, Scenario.AUTHOR);
        State before = State.of(s, i.id());
        String batchKey = Scenario.uuid();

        LedgerException failure = Expect.failure(() -> s.bean(BatchService.class).batch(Scenario.AUTHOR, batchKey,
                List.of(
                        new BatchOpInput.RecordCase(i.id(), "seen", Scenario.REASON, null, null, null, mine),
                        new BatchOpInput.RecordCase(i.id(), "bad", Scenario.REASON, null, null, null, Scenario.uuid()))),
                LookupCode.ARTIFACT_NOT_FOUND);

        assertThat(failure.details()).containsEntry("op_index", List.of("2"))
                .containsEntry("op", List.of("record_case"));
        assertThat(State.of(s, i.id())).isEqualTo(before);
        assertThat(referencing(s, mine)).isZero();
        assertThat(receipts(s, batchKey)).isZero();
    }

    @ParameterizedTest
    @MethodSource("cases")
    @DisplayName("ATA-012: replay returns stored response; differing attachment is OPERATION_CONFLICT")
    void ata012_replay(Case c, Scenario s) {
        Prepared work = c.prepare().apply(s);
        String attachment = attachmentOf(s, c.actor());
        String other = attachmentOf(s, c.actor());
        String operationKey = Scenario.uuid();
        Object first = work.call().run(attachment, operationKey);
        long rows = historyOf(s, work.issueId());

        Object again = work.call().run(attachment, operationKey);

        assertThat(again).isEqualTo(first);
        assertThat(historyOf(s, work.issueId())).isEqualTo(rows);
        Expect.failure(() -> work.call().run(other, operationKey), ConflictCode.OPERATION_CONFLICT);
        Expect.failure(() -> work.call().run(null, operationKey), ConflictCode.OPERATION_CONFLICT);
        assertThat(referencing(s, other)).isZero();
        assertThat(historyOf(s, work.issueId())).isEqualTo(rows);
    }
}
