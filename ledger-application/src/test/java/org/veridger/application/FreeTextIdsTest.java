// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.veridger.application.history.Change;
import org.veridger.application.history.Snapshot;
import org.veridger.application.read.DetailService;
import org.veridger.application.read.HistoryService;
import org.veridger.application.read.HistoryService.HistoryQuery;
import org.veridger.application.support.Expect;
import org.veridger.application.support.LedgerServiceTest;
import org.veridger.application.support.Parallel;
import org.veridger.application.support.Parallel.Outcome;
import org.veridger.application.support.Scenario;
import org.veridger.application.write.RecordDeliverableCommand;
import org.veridger.application.write.RecordDeliverableService;
import org.veridger.application.write.SaveService;
import org.veridger.application.write.WriteResult.SaveResult;
import org.veridger.domain.error.ConflictCode;
import org.veridger.domain.error.LedgerException;
import org.veridger.domain.error.TransitionCode;
import org.veridger.domain.error.ValidationCode;
import org.veridger.domain.error.ValidationException;

/**
 * REH3-ID-04, REH3-ID-05, REH3-ID-07, REH3-TXT-01 .. 04 and REH3-LEN-01 (PostgreSQL of the test PC): the ids of a task,
 * spec or criterion: free text in any case, the refusal of ids that differ only in case, the texts that may be empty, and
 * the longer columns.
 */
@LedgerServiceTest
class FreeTextIdsTest {

    private static String create(Scenario s, String entity, String id, Map<String, String> fields) {
        return s.bean(SaveService.class).save(Scenario.AUTHOR, s.create(entity, id, fields)).id();
    }

    private static Map<String, String> inProject(Map<String, String> fields, String project) {
        Map<String, String> copy = new LinkedHashMap<>(fields);
        copy.put("project_id", project);
        return copy;
    }

    @Test
    @DisplayName("REH3-ID-04 an id of a task, spec or criterion that equals an existing one ignoring case is DUPLICATE_KEY naming the existing id; nothing is written; an unrelated id is created")
    void id04_caseCollisionsAreRefused(Scenario s) {
        String task = s.code() + ":A01";
        String spec = s.code() + ":S01";
        String criterion = s.code() + ":C1";
        create(s, "task", task, s.taskFields("first"));
        create(s, "spec", spec, s.specFields("first spec"));
        create(s, "criterion", criterion, s.criterionFields("r", "c1"));
        long history = s.count("SELECT COUNT(*) FROM history WHERE entity_id IN (?, ?, ?)",
                task.toLowerCase(Locale.ROOT), spec.toLowerCase(Locale.ROOT), criterion.toLowerCase(Locale.ROOT));

        LedgerException lowerTask = Expect.failure(() -> create(s, "task", task.toLowerCase(Locale.ROOT),
                s.taskFields("sibling")), ConflictCode.DUPLICATE_KEY);
        LedgerException sameTask = Expect.failure(() -> create(s, "task", task, s.taskFields("again")),
                ConflictCode.DUPLICATE_KEY);
        LedgerException lowerSpec = Expect.failure(() -> create(s, "spec", spec.toLowerCase(Locale.ROOT),
                s.specFields("sibling spec")), ConflictCode.DUPLICATE_KEY);
        LedgerException lowerCriterion = Expect.failure(() -> create(s, "criterion",
                criterion.toLowerCase(Locale.ROOT), s.criterionFields("r2", "c2")), ConflictCode.DUPLICATE_KEY);
        LedgerException sameCriterion = Expect.failure(() -> create(s, "criterion", criterion,
                s.criterionFields("r3", "c3")), ConflictCode.DUPLICATE_KEY);

        assertThat(lowerTask.args()).containsExactly("task " + task);
        assertThat(sameTask.args()).containsExactly("task " + task);
        assertThat(lowerSpec.args()).containsExactly("spec " + spec);
        assertThat(lowerCriterion.args()).containsExactly("criterion " + criterion);
        assertThat(sameCriterion.args()).containsExactly("criterion " + criterion);
        assertThat(s.count("SELECT COUNT(*) FROM task WHERE project_id = ?", s.projectId())).isEqualTo(1);
        assertThat(s.count("SELECT COUNT(*) FROM spec WHERE project_id = ?", s.projectId())).isEqualTo(1);
        assertThat(s.count("SELECT COUNT(*) FROM milestone_criterion WHERE project_id = ?", s.projectId())).isEqualTo(1);
        assertThat(s.count("SELECT COUNT(*) FROM history WHERE entity_id IN (?, ?, ?)",
                task.toLowerCase(Locale.ROOT), spec.toLowerCase(Locale.ROOT), criterion.toLowerCase(Locale.ROOT)))
                .isEqualTo(history);
        assertThat(create(s, "task", s.code() + ":A02", s.taskFields("second"))).isEqualTo(s.code() + ":A02");
        assertThat(s.count("SELECT COUNT(*) FROM task WHERE id_key = ?", task.toUpperCase(Locale.ROOT))).isEqualTo(1);
    }

    @Test
    @DisplayName("REH3-ID-05 two creates at the same moment that differ only in case (in two projects, so the write gate does not serialise them): exactly one succeeds, the other is DUPLICATE_KEY, one row stays, no raw exception reaches the caller")
    void id05_race(Scenario s) {
        String other = s.otherProject();
        String second = "claude:reh3-" + Scenario.uuid().substring(0, 8);
        s.grant(second, other, "task");
        SaveService save = s.bean(SaveService.class);
        for (int round = 0; round < 20; round++) {
            int i = round;
            String task = s.code() + ":R" + i;
            String criterion = s.code() + ":RC" + i;
            String exactTwice = s.code() + ":X" + i;
            List<Callable<SaveResult>> calls = new ArrayList<>();
            calls.add(() -> save.save(Scenario.AUTHOR, s.create("task", task, s.taskFields("one"))));
            calls.add(() -> save.save(second, s.create("task", task.toLowerCase(Locale.ROOT),
                    inProject(s.taskFields("two"), other))));
            calls.add(() -> save.save(Scenario.AUTHOR, s.create("criterion", criterion,
                    s.criterionFields("r", "a" + i))));
            calls.add(() -> save.save(second, s.create("criterion", criterionLower(criterion),
                    inProject(s.criterionFields("r", "b" + i), other))));
            calls.add(() -> save.save(Scenario.AUTHOR, s.create("task", exactTwice, s.taskFields("x1"))));
            calls.add(() -> save.save(second, s.create("task", exactTwice, inProject(s.taskFields("x2"), other))));

            List<Outcome<SaveResult>> outcomes = Parallel.all(calls);

            for (int pair = 0; pair < 6; pair += 2) {
                Outcome<SaveResult> a = outcomes.get(pair);
                Outcome<SaveResult> b = outcomes.get(pair + 1);
                assertThat(a.succeeded() ^ b.succeeded()).as("exactly one of pair %s of round %s", pair / 2, i).isTrue();
                Throwable failure = a.succeeded() ? b.failure() : a.failure();
                assertThat(failure).isInstanceOf(LedgerException.class);
                assertThat(((LedgerException) failure).code()).isEqualTo(ConflictCode.DUPLICATE_KEY);
            }
            assertThat(s.count("SELECT COUNT(*) FROM task WHERE id_key = ?", task.toUpperCase(Locale.ROOT))).isEqualTo(1);
            assertThat(s.count("SELECT COUNT(*) FROM milestone_criterion WHERE id_key = ?",
                    criterion.toUpperCase(Locale.ROOT))).isEqualTo(1);
            assertThat(s.count("SELECT COUNT(*) FROM task WHERE id_key = ?", exactTwice.toUpperCase(Locale.ROOT)))
                    .isEqualTo(1);
        }
    }

    private static String criterionLower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    @Test
    @DisplayName("REH3-ID-07 the history of a task is listed by the id as it was written and not by the same id in another case")
    void id07_historyByExactId(Scenario s) {
        String task = s.code() + ":Hi-a";
        create(s, "task", task, s.taskFields("t"));
        HistoryService history = s.bean(HistoryService.class);

        var exact = history.history(new HistoryQuery(null, null, null, "task", task, null, null, null, null, null,
                false, 50, null));
        var other = history.history(new HistoryQuery(null, null, null, "task", task.toLowerCase(Locale.ROOT), null, null,
                null, null, null, false, 50, null));

        assertThat(exact.items()).hasSize(1);
        assertThat(exact.items().getFirst().entityId()).isEqualTo(task);
        assertThat(other.items()).isEmpty();
    }

    @Test
    @DisplayName("REH3-TXT-01 a task created with purpose \"\" and success_condition \"   \" is accepted: both columns are NULL, the detail returns \"\", the history of the create holds no ''")
    void txt01_emptyTextsOfATask(Scenario s) {
        Map<String, String> fields = s.taskFields("t");
        fields.put("purpose", "");
        fields.put("success_condition", "   ");

        SaveResult created = s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("task", null, fields));

        assertThat(s.count("SELECT COUNT(*) FROM task WHERE id = ? AND purpose IS NULL AND success_condition IS NULL",
                created.id())).isEqualTo(1);
        DetailService.TaskDetail detail = s.bean(DetailService.class).task(created.id());
        assertThat(detail.purpose()).isEmpty();
        assertThat(detail.successCondition()).isEmpty();
        assertThat(s.changes(created.historyId()).entries().values()).noneMatch(change -> "".equals(change.after()));
    }

    @Test
    @DisplayName("REH3-TXT-02 purpose, symptom and criterion_text have to be given on a create: absent or null is REQUIRED with the path of the field")
    void txt02_theFieldHasToBeGiven(Scenario s) {
        Map<String, String> noPurpose = s.taskFields("t");
        noPurpose.remove("purpose");
        Map<String, String> nullPurpose = s.taskFields("t");
        nullPurpose.put("purpose", null);
        Map<String, String> noSymptom = s.issueFields("i");
        noSymptom.remove("symptom");
        Map<String, String> noText = s.criterionFields("r", "c");
        noText.remove("criterion_text");

        for (Object[] attempt : new Object[][] {{"task", null, noPurpose, "purpose"},
                {"task", null, nullPurpose, "purpose"}, {"issue", null, noSymptom, "symptom"},
                {"criterion", s.criterionId("1"), noText, "criterion_text"}}) {
            LedgerException refused = Expect.failure(() -> create(s, (String) attempt[0], (String) attempt[1],
                    cast(attempt[2])), ValidationCode.VALIDATION_FAILED);
            assertThat(((ValidationException) refused).issues().getFirst().path()).isEqualTo(attempt[3]);
            assertThat(((ValidationException) refused).issues().getFirst().reason().name()).isEqualTo("REQUIRED");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> cast(Object fields) {
        return (Map<String, String>) fields;
    }

    @Test
    @DisplayName("REH3-TXT-03 an edit that sets purpose to \"\" stores NULL, the history records [old, null] and before_data holds the old text; the same edit again changes nothing; the read returns \"\"")
    void txt03_editToEmpty(Scenario s) {
        SaveResult task = s.newTask("t");

        SaveResult edited = s.bean(SaveService.class).save(Scenario.AUTHOR, s.edit("task", task.id(), 1,
                Map.of("purpose", "")));

        assertThat(s.count("SELECT COUNT(*) FROM task WHERE id = ? AND purpose IS NULL", task.id())).isEqualTo(1);
        assertThat(s.changes(edited.historyId()).entries()).containsExactly(Map.entry("purpose",
                new Change("purpose of t", null)));
        assertThat(s.before(edited.historyId(), Snapshot.TaskRow.class).purpose()).isEqualTo("purpose of t");
        Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR, s.edit("task", task.id(), 2,
                Map.of("purpose", ""))), TransitionCode.NOTHING_CHANGES);
        assertThat(s.bean(DetailService.class).task(task.id()).purpose()).isEmpty();
    }

    @Test
    @DisplayName("REH3-TXT-04 symptom and root_cause of an issue and criterion_text of a criterion may be set to \"\": NULL is stored, the history records the change, the read returns \"\"")
    void txt04_issueAndCriterionEmptyTexts(Scenario s) {
        SaveResult issue = s.newIssue("i");
        SaveResult criterion = s.newCriterion("1", "r", "c");

        SaveResult editedIssue = s.bean(SaveService.class).save(Scenario.AUTHOR, s.edit("issue", issue.id(), 1,
                Map.of("symptom", "", "root_cause", " ")));
        SaveResult editedCriterion = s.bean(SaveService.class).save(Scenario.AUTHOR, s.edit("criterion", criterion.id(), 1,
                Map.of("criterion_text", "")));

        assertThat(s.count("SELECT COUNT(*) FROM issue WHERE id = ? AND symptom IS NULL AND root_cause IS NULL",
                issue.id())).isEqualTo(1);
        assertThat(s.count("SELECT COUNT(*) FROM milestone_criterion WHERE id = ? AND criterion_text IS NULL",
                criterion.id())).isEqualTo(1);
        assertThat(s.changes(editedIssue.historyId()).entries()).containsOnlyKeys("symptom", "root_cause");
        assertThat(s.changes(editedCriterion.historyId()).entries()).containsExactly(Map.entry("criterion_text",
                new Change("criterion c", null)));
        DetailService.IssueDetail detail = s.bean(DetailService.class).issue(issue.id());
        assertThat(detail.symptom()).isEmpty();
        assertThat(detail.rootCause()).isEmpty();
    }

    @Test
    @DisplayName("REH3-LEN-02 a task is saved with an origin_ref of 9408 characters (a long imported value) and edited by it again; 20001 is MUST_BE_AT_MOST_CHARS (20000)")
    void len02_originReference(Scenario s) {
        Map<String, String> fields = s.taskFields("t");
        fields.put("origin_ref", "o".repeat(9408));
        String id = create(s, "task", null, fields);

        assertThat(s.count("SELECT COUNT(*) FROM task WHERE id = ? AND origin_ref = ?", id, "o".repeat(9408))).isEqualTo(1);
        assertThat(s.bean(SaveService.class).save(Scenario.AUTHOR, s.edit("task", id, 1, Map.of("origin_ref",
                "p".repeat(9408)))).version()).isEqualTo(2);
        fields.put("origin_ref", "o".repeat(20001));
        LedgerException tooLong = Expect.failure(() -> create(s, "task", null, fields), ValidationCode.VALIDATION_FAILED);
        assertThat(((ValidationException) tooLong).issues().getFirst().reasonArgs()).containsExactly("20000");
    }

    @Test
    @DisplayName("REH3-LEN-01 a criterion code of 200 and a check reference of 2000 characters are stored whole; 201 and 2001 are MUST_BE_AT_MOST_CHARS (200, 2000)")
    void len01_longerColumns(Scenario s) {
        String code200 = "c".repeat(200);
        Map<String, String> fields = s.criterionFields("r", code200);
        assertThat(create(s, "criterion", s.criterionId("L"), fields)).isEqualTo(s.criterionId("L"));
        assertThat(s.count("SELECT COUNT(*) FROM milestone_criterion WHERE id = ? AND code = ?", s.criterionId("L"),
                code200)).isEqualTo(1);
        LedgerException tooLong = Expect.failure(() -> create(s, "criterion", s.criterionId("M"),
                s.criterionFields("r", "c".repeat(201))), ValidationCode.VALIDATION_FAILED);
        assertThat(((ValidationException) tooLong).issues().getFirst().reasonArgs()).containsExactly("200");

        String issue = s.newIssue("i").id();
        String check2000 = "r".repeat(2000);
        s.bean(RecordDeliverableService.class).recordDeliverable(Scenario.AUTHOR,
                new RecordDeliverableCommand(Scenario.uuid(), "issue", issue, "code_change", Scenario.ref('a'),
                        Scenario.REASON, "repo/e2e", "Deliverable summary", check2000, "passed", null, null));
        assertThat(s.count("SELECT COUNT(*) FROM deliverable WHERE target_entity = 'ISSUE' AND target_id = ? AND check_ref = ?",
                issue, check2000)).isEqualTo(1);
        LedgerException checkTooLong = Expect.failure(() -> s.bean(RecordDeliverableService.class)
                .recordDeliverable(Scenario.AUTHOR, new RecordDeliverableCommand(Scenario.uuid(), "issue", issue,
                        "code_change", Scenario.ref('b'), Scenario.REASON, "repo/e2e", "Deliverable summary",
                        "r".repeat(2001), "passed", null, null)), ValidationCode.VALIDATION_FAILED);
        assertThat(((ValidationException) checkTooLong).issues().getFirst().reasonArgs()).containsExactly("2000");
    }
}
