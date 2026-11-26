// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.veridger.application.json.LedgerJson;
import org.veridger.application.support.Expect;
import org.veridger.application.support.LedgerServiceTest;
import org.veridger.application.support.MergeFixture;
import org.veridger.application.support.Scenario;
import org.veridger.application.write.BatchOpInput;
import org.veridger.application.write.BatchService;
import org.veridger.application.write.CorrectCommand;
import org.veridger.application.write.CorrectService;
import org.veridger.application.write.RecordCaseCommand;
import org.veridger.application.write.RecordCaseService;
import org.veridger.application.write.WriteResult.RecordCaseResult;
import org.veridger.application.write.WriteResult.SaveResult;
import org.veridger.database.repository.IssueCaseRepository;
import org.veridger.domain.error.ConflictCode;
import org.veridger.domain.error.ErrorCode;
import org.veridger.domain.error.LedgerException;
import org.veridger.domain.error.LookupCode;
import org.veridger.domain.error.TransitionCode;
import org.veridger.domain.error.TrustCode;
import org.veridger.domain.error.ValidationCode;
import org.veridger.domain.model.HistoryEntry;
import org.veridger.domain.model.IssueCase;
import org.veridger.domain.rule.HistoryEntity;
import org.veridger.domain.rule.HistoryOp;
import org.veridger.domain.value.IssueId;
import tools.jackson.databind.json.JsonMapper;

/** SVC-CAS-01 .. SVC-CAS-16: observed cases under an issue. */
@LedgerServiceTest
class CaseTest {

    private static RecordCaseCommand observe(String issue, String observation) {
        return new RecordCaseCommand(Scenario.uuid(), issue, observation, Scenario.REASON, null, null, null);
    }

    private RecordCaseResult record(Scenario s, String actor, RecordCaseCommand command) {
        return s.bean(RecordCaseService.class).recordCase(actor, command);
    }

    private List<IssueCase> cases(Scenario s, String issue) {
        return s.bean(IssueCaseRepository.class).findByIssueIdOrderById(IssueId.of(issue));
    }

    @Test
    @DisplayName("SVC-CAS-01: a case keeps every field and the result carries contract keys")
    void cas01_fullCase(Scenario s) {
        SaveResult i = s.newIssue("i");
        RecordCaseResult result = record(s, Scenario.AUTHOR, new RecordCaseCommand(Scenario.uuid(), i.id(),
                "observed failure", Scenario.REASON, "log line", "reported quote", "2026-11-05T09:30:00+01:00"));
        assertThat(result.entity()).isEqualTo("issue_case");
        assertThat(result.id()).isEqualTo(i.id() + "|" + result.caseId());
        assertThat(result.issue()).isEqualTo(i.id());
        IssueCase stored = cases(s, i.id()).getFirst();
        assertThat(stored.observation()).isEqualTo("observed failure");
        assertThat(stored.evidence()).isEqualTo("log line");
        assertThat(stored.sourceQuote()).isEqualTo("reported quote");
        assertThat(stored.observedAt()).isEqualTo(Instant.parse("2026-11-05T08:30:00Z"));
        assertThat(stored.recordedBy().value()).isEqualTo(Scenario.AUTHOR);
        Map<?, ?> keys = new JsonMapper().readValue(s.bean(LedgerJson.class).write(result).text(), Map.class);
        assertThat(keys.keySet()).isEqualTo(Set.of("status", "entity", "id", "case_id", "issue", "history_id",
                "author", "login"));
    }

    @Test
    @DisplayName("SVC-CAS-02: one CREATE history row of the case without before_data")
    void cas02_history(Scenario s) {
        SaveResult i = s.newIssue("i");
        RecordCaseResult result = record(s, Scenario.AUTHOR, observe(i.id(), "seen"));
        List<HistoryEntry> rows = s.history(HistoryEntity.ISSUE_CASE, result.id());
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().op()).isEqualTo(HistoryOp.CREATE);
        assertThat(rows.getFirst().login()).isEqualTo("ledger_author");
        assertThat(rows.getFirst().authorId().value()).isEqualTo(Scenario.AUTHOR);
        assertThat(rows.getFirst().beforeData()).isNull();
    }

    @Test
    @DisplayName("SVC-CAS-04/05: missing grant and unknown issue")
    void cas04_05_authAndNotFound(Scenario s) {
        SaveResult i = s.newIssue("i");
        Expect.failure(() -> record(s, Scenario.NOGRANT, observe(i.id(), "seen")), TrustCode.SESSION_NOT_GRANTED);
        Expect.failure(() -> record(s, Scenario.AUTHOR, observe(s.issueId(9), "seen")), LookupCode.NOT_FOUND);
    }

    @Test
    @DisplayName("SVC-CAS-06/07: replay vs operation conflict")
    void cas06_07_replayAndConflict(Scenario s) {
        SaveResult i = s.newIssue("i");
        String key = Scenario.uuid();
        RecordCaseCommand command = new RecordCaseCommand(key, i.id(), "seen", Scenario.REASON, null, null, null);
        RecordCaseResult first = record(s, Scenario.AUTHOR, command);
        assertThat(record(s, Scenario.AUTHOR, command)).isEqualTo(first);
        assertThat(cases(s, i.id())).hasSize(1);

        Expect.failure(() -> record(s, Scenario.AUTHOR, new RecordCaseCommand(key, i.id(), "seen differently",
                Scenario.REASON, null, null, null)), ConflictCode.OPERATION_CONFLICT);
    }

    @Test
    @DisplayName("SVC-CAS-08/10/11/15: merged issue refusal, batch cases, batch rollback on merged, and case correction")
    void cas08_10_11_15_mergedBatchAndCorrect(Scenario s) {
        SaveResult source = s.newIssue("source");
        SaveResult target = s.newIssue("target");
        MergeFixture.merge(s, source.id(), 1, target.id());
        LedgerException e = Expect.failure(() -> record(s, Scenario.AUTHOR, observe(source.id(), "seen")),
                TransitionCode.MERGED_ISSUE);
        assertThat(Expect.message(e)).startsWith(source.id() + " was merged into " + target.id());
        assertThat(cases(s, source.id())).isEmpty();

        var batchOk = s.bean(BatchService.class).batch(Scenario.AUTHOR, Scenario.uuid(), List.of(
                new BatchOpInput.RecordCase(target.id(), "one", Scenario.REASON, null, null, null, null),
                new BatchOpInput.RecordCase(target.id(), "two", Scenario.REASON, null, null, null, null)));
        assertThat(batchOk.count()).isEqualTo(2);
        assertThat(cases(s, target.id())).extracting(IssueCase::observation).containsExactly("one", "two");

        SaveResult live = s.newIssue("live");
        LedgerException batchErr = Expect.failure(() -> s.bean(BatchService.class).batch(Scenario.AUTHOR,
                Scenario.uuid(), List.of(
                        new BatchOpInput.RecordCase(live.id(), "one", Scenario.REASON, null, null, null, null),
                        new BatchOpInput.RecordCase(source.id(), "two", Scenario.REASON, null, null, null, null))),
                TransitionCode.MERGED_ISSUE);
        assertThat(batchErr.details()).containsEntry("op_index", List.of("2"))
                .containsEntry("op", List.of("record_case"));
        assertThat(cases(s, live.id())).isEmpty();

        RecordCaseResult added = record(s, Scenario.AUTHOR, observe(live.id(), "seen"));
        var corrected = s.bean(CorrectService.class).correct(Scenario.AUTHOR, new CorrectCommand(Scenario.uuid(),
                added.historyId(), Scenario.REASON));
        assertThat(corrected.corrects()).isEqualTo(added.historyId());
        assertThat(s.historyRow(corrected.historyId()).entityId()).isEqualTo(added.id());
    }

    record Limit(String name, String observation, String evidence, String quote, String observedAt,
            ErrorCode expected) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Limit> limits() {
        ErrorCode refused = ValidationCode.VALIDATION_FAILED;
        return Stream.of(
                new Limit("observation 4000", "o".repeat(4000), null, null, null, null),
                new Limit("observation 4001", "o".repeat(4001), null, null, null, refused),
                new Limit("evidence 2000", "o", "e".repeat(2000), null, null, null),
                new Limit("evidence 2001", "o", "e".repeat(2001), null, null, refused),
                new Limit("source_quote 2000", "o", null, "q".repeat(2000), null, null),
                new Limit("source_quote 2001", "o", null, "q".repeat(2001), null, refused),
                new Limit("observed_at absent", "o", null, null, null, null),
                new Limit("observed_at with zone", "o", null, null, "2026-11-05T10:00:00Z", null),
                new Limit("observed_at without zone", "o", null, null, "2026-11-05T10:00:00", refused));
    }

    @ParameterizedTest
    @MethodSource("limits")
    @DisplayName("SVC-CAS-16: field limits of a case")
    void cas16_limits(Limit limit, Scenario s) {
        SaveResult i = s.newIssue("i");
        assertThat(Expect.outcome(() -> record(s, Scenario.AUTHOR, new RecordCaseCommand(Scenario.uuid(), i.id(),
                limit.observation(), Scenario.REASON, limit.evidence(), limit.quote(), limit.observedAt()))))
                .isEqualTo(Optional.ofNullable(limit.expected()));
    }
}
