package org.pgledger.application;

import static org.assertj.core.api.Assertions.assertThat;
import org.pgledger.testing.TestOwners;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.application.admin.GrantService;
import org.pgledger.application.history.Change;
import org.pgledger.application.history.ChangeSet;
import org.pgledger.application.json.LedgerJson;
import org.pgledger.application.support.Expect;
import org.pgledger.application.support.Fields;
import org.pgledger.application.support.LedgerServiceTest;
import org.pgledger.application.support.Scenario;
import org.pgledger.application.write.RecordCaseCommand;
import org.pgledger.application.write.RecordCaseService;
import org.pgledger.application.write.RecordDeliverableCommand;
import org.pgledger.application.write.RecordDeliverableService;
import org.pgledger.application.write.ReviewService;
import org.pgledger.application.write.SaveCommand;
import org.pgledger.application.write.SaveService;
import org.pgledger.application.write.TransitionCommand;
import org.pgledger.application.write.TransitionService;
import org.pgledger.application.write.WriteResult.SaveResult;
import org.pgledger.domain.error.ErrorCode;
import org.pgledger.domain.error.LedgerException;
import org.pgledger.domain.error.TransitionCode;
import org.pgledger.domain.error.ValidationCode;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.rule.HistoryOp;

/** SVC-NORM-01 .. SVC-NORM-05: an empty string is NULL or refused, never stored (SVC-NORM-04 is in the receipt test). */
@LedgerServiceTest
class NormTest {

    /** A write that gives optional fields as blank; the SQL counts the rows that hold NULL in all of them. */
    record Blank(String name, Function<Scenario, String> write, String sql) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Blank> blankOptionalFields() {
        return Stream.of(
                new Blank("issue owner_actor_id", s -> {
                    var fields = s.issueFields("i");
                    fields.put("owner_actor_id", "  ");
                    s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("issue", null, fields));
                    return s.projectId();
                }, "SELECT COUNT(*) FROM issue WHERE project_id = ? AND owner_actor_id IS NULL"),
                new Blank("spec parent_id, priority, owner, origin_ref, origin_quote", s -> {
                    var fields = s.specFields("sp");
                    fields.put("parent_id", " ");
                    fields.put("priority", "");
                    fields.put("owner_actor_id", " ");
                    fields.put("origin_ref", "");
                    fields.put("origin_quote", "  ");
                    s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("spec", null, fields));
                    return s.projectId();
                }, "SELECT COUNT(*) FROM spec WHERE project_id = ? AND parent_id IS NULL AND priority IS NULL "
                        + "AND owner_actor_id IS NULL AND origin_ref IS NULL AND origin_quote IS NULL"),
                new Blank("task owner, origin_ref, origin_quote", s -> {
                    var fields = s.taskFields("t");
                    fields.put("owner_actor_id", "");
                    fields.put("origin_ref", "   ");
                    fields.put("origin_quote", "");
                    s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("task", null, fields));
                    return s.projectId();
                }, "SELECT COUNT(*) FROM task WHERE project_id = ? AND owner_actor_id IS NULL "
                        + "AND origin_ref IS NULL AND origin_quote IS NULL"),
                new Blank("criterion goal_label", s -> {
                    var fields = s.criterionFields("r", "c");
                    fields.put("goal_label", " ");
                    s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("criterion", s.criterionId("1"), fields));
                    return s.projectId();
                }, "SELECT COUNT(*) FROM milestone_criterion WHERE project_id = ? AND goal_label IS NULL"),
                new Blank("case evidence and source_quote", s -> {
                    SaveResult i = s.newIssue("i");
                    s.bean(RecordCaseService.class).recordCase(Scenario.AUTHOR, new RecordCaseCommand(Scenario.uuid(),
                            i.id(), "seen", Scenario.REASON, " ", "", null));
                    return i.id();
                }, "SELECT COUNT(*) FROM issue_case WHERE issue_id = ? AND evidence IS NULL AND source_quote IS NULL"),
                new Blank("deliverable location and check_ref", s -> {
                    SaveResult i = s.newIssue("i");
                    s.bean(RecordDeliverableService.class).recordDeliverable(Scenario.AUTHOR,
                            new RecordDeliverableCommand(Scenario.uuid(), "issue", i.id(), "code_change",
                                    Scenario.ref('a'), Scenario.REASON, "  ", "Deliverable summary", "", "", "", null));
                    return i.id();
                }, "SELECT COUNT(*) FROM deliverable WHERE target_entity = 'ISSUE' AND target_id = ? "
                        + "AND location IS NULL AND check_ref IS NULL AND check_result IS NULL"),
                new Blank("transition evidence_sha256 and merged_into", s -> {
                    SaveResult i = s.newIssue("i");
                    s.bean(TransitionService.class).transition(Scenario.AUTHOR, new TransitionCommand(Scenario.uuid(),
                            "issue", i.id(), 1L, "DISPUTED", Scenario.REASON, Scenario.EVIDENCE, " ", " "));
                    return i.id();
                }, "SELECT COUNT(*) FROM history WHERE entity = 'ISSUE' AND entity_id = ? AND op = 'TRANSITION' "
                        + "AND evidence_sha256 IS NULL"),
                new Blank("grant note", s -> {
                    s.ctx().enroll("claude:note");
                    s.bean(GrantService.class).grant(new GrantService.GrantCommand("claude:note", s.projectId(),
                            List.of("issue"), null, "   "), TestOwners.OWNER);
                    return "claude:note";
                }, "SELECT COUNT(*) FROM session_grant WHERE author_id = ? AND note IS NULL"));
    }

    @ParameterizedTest
    @MethodSource("blankOptionalFields")
    @DisplayName("SVC-NORM-01: a blank optional field is stored as NULL")
    void norm01_blankOptionalsBecomeNull(Blank blank, Scenario s) {
        String parameter = blank.write().apply(s);
        assertThat(s.count(blank.sql(), parameter)).isEqualTo(1);
    }

    @Test
    @DisplayName("SVC-NORM-01: a blank check_ref with a check_result is a validation failure")
    void norm01_resultNeedsAReference(Scenario s) {
        SaveResult i = s.newIssue("i");
        ValidationException e = (ValidationException) Expect.failure(() -> s.bean(RecordDeliverableService.class)
                .recordDeliverable(Scenario.AUTHOR, new RecordDeliverableCommand(Scenario.uuid(), "issue", i.id(),
                        "code_change", Scenario.ref('a'), Scenario.REASON, "repo/e2e", "Deliverable summary", "   ",
                        "passed", null, null)), ValidationCode.VALIDATION_FAILED);
        assertThat(e.issues().get(0).path()).isEqualTo("check_ref");
    }

    /** A required field given blank: the write fails with this code and names this path. */
    record Required(String name, Consumer<Scenario> write, ErrorCode code, String path) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static Map<String, String> with(Map<String, String> fields, String key, String value) {
        Map<String, String> copy = new LinkedHashMap<>(fields);
        copy.put(key, value);
        return copy;
    }

    static Stream<Required> blankRequiredFields() {
        return Stream.of(
                new Required("issue title", s -> s.bean(SaveService.class).save(Scenario.AUTHOR,
                        s.create("issue", null, with(s.issueFields("i"), "title", "  "))),
                        ValidationCode.VALIDATION_FAILED, "title"),
                new Required("spec title", s -> s.bean(SaveService.class).save(Scenario.AUTHOR,
                        s.create("spec", null, with(s.specFields("sp"), "title", "  "))),
                        ValidationCode.VALIDATION_FAILED, "title"),
                new Required("criterion milestone_name", s -> s.bean(SaveService.class).save(Scenario.AUTHOR,
                        s.create("criterion", s.criterionId("1"),
                                with(s.criterionFields("r", "c"), "milestone_name", ""))),
                        ValidationCode.VALIDATION_FAILED, "milestone_name"),
                new Required("criterion code", s -> s.bean(SaveService.class).save(Scenario.AUTHOR,
                        s.create("criterion", s.criterionId("1"), with(s.criterionFields("r", "c"), "code", " "))),
                        ValidationCode.VALIDATION_FAILED, "criterion_code"),
                new Required("case observation", s -> s.bean(RecordCaseService.class).recordCase(Scenario.AUTHOR,
                        new RecordCaseCommand(Scenario.uuid(), s.newIssue("i").id(), "   ", Scenario.REASON, null, null,
                                null)), ValidationCode.VALIDATION_FAILED, "observation"),
                new Required("deliverable ref", s -> s.bean(RecordDeliverableService.class).recordDeliverable(
                        Scenario.AUTHOR, new RecordDeliverableCommand(Scenario.uuid(), "issue", s.newIssue("i").id(),
                                "code_change", "   ", Scenario.REASON, null, "Deliverable summary", null, null, null,
                                null)),
                        ValidationCode.VALIDATION_FAILED, "deliverable_ref"),
                new Required("deliverable summary", s -> s.bean(RecordDeliverableService.class).recordDeliverable(
                        Scenario.AUTHOR, new RecordDeliverableCommand(Scenario.uuid(), "issue", s.newIssue("i").id(),
                                "code_change", Scenario.ref('a'), Scenario.REASON, null, "   ", null, null, null,
                                null)),
                        ValidationCode.VALIDATION_FAILED, "deliverable_summary"),
                new Required("reason", s -> s.bean(SaveService.class).save(Scenario.AUTHOR,
                        new SaveCommand(Scenario.uuid(), "issue", null, 0L, Fields.of(s.issueFields("i")),
                                "          ")), ValidationCode.REASON_INVALID, "reason"));
    }

    @ParameterizedTest
    @MethodSource("blankRequiredFields")
    @DisplayName("SVC-NORM-02: a blank required field is refused and named; long descriptive texts that may be empty follow REH3-TXT-01")
    void norm02_blankRequiredFields(Required required, Scenario s) {
        LedgerException e = Expect.failure(() -> required.write().accept(s), required.code());
        assertThat(((ValidationException) e).issues().get(0).path()).isEqualTo(required.path());
        assertThat(s.count("SELECT COUNT(*) FROM issue_case WHERE issue_id IN (SELECT id FROM issue "
                + "WHERE project_id = ?)", s.projectId())).isZero();
    }

    @Test
    @DisplayName("SVC-NORM-03: a blank evidence_uri equals none")
    void norm03_blankEvidence(Scenario s) {
        SaveResult i = s.resolvedIssue("i");
        Expect.failure(() -> s.bean(ReviewService.class).review(Scenario.REVIEWER, new TransitionCommand(
                Scenario.uuid(), "issue", i.id(), 2L, "VERIFIED", Scenario.REASON, "   ", null, null)),
                TransitionCode.EVIDENCE_REQUIRED);
    }

    @Test
    @DisplayName("SVC-NORM-05: setting an optional field to blank clears it and the history records [old, null]")
    void norm05_clearing(Scenario s) {
        var fields = s.taskFields("t");
        fields.put("origin_ref", "https://origin.example/1");
        SaveResult t = s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("task", null, fields));
        SaveResult edited = s.bean(SaveService.class).save(Scenario.AUTHOR, s.edit("task", t.id(), 1,
                Map.of("origin_ref", "")));
        assertThat(s.task(t.id()).originRef()).isNull();
        ChangeSet changes = s.bean(LedgerJson.class).read(s.historyRow(edited.historyId()).changes(),
                ChangeSet.class);
        assertThat(changes.entries()).containsExactly(Map.entry("origin_ref",
                new Change("https://origin.example/1", null)));
        assertThat(s.history(HistoryEntity.TASK, t.id()).get(1).op()).isEqualTo(HistoryOp.EDIT);
    }
}
