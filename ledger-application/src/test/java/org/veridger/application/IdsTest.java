// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application;

import static org.assertj.core.api.Assertions.assertThat;
import org.veridger.testing.TestOwners;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.veridger.application.admin.GrantService;
import org.veridger.application.support.Expect;
import org.veridger.application.support.Fields;
import org.veridger.application.support.LedgerServiceTest;
import org.veridger.application.support.Parallel;
import org.veridger.application.support.Parallel.Outcome;
import org.veridger.application.support.Scenario;
import org.veridger.application.write.BatchOpInput;
import org.veridger.application.write.BatchResult;
import org.veridger.application.write.BatchService;
import org.veridger.application.write.RecordCaseCommand;
import org.veridger.application.write.RecordCaseService;
import org.veridger.application.write.SaveCommand;
import org.veridger.application.write.SaveService;
import org.veridger.application.write.WriteResult;
import org.veridger.application.write.WriteResult.SaveResult;
import org.veridger.database.repository.TaskRepository;
import org.veridger.domain.context.ActorScope;
import org.veridger.domain.error.ConflictCode;
import org.veridger.domain.error.LedgerException;
import org.veridger.domain.error.LookupCode;
import org.veridger.domain.error.TransitionCode;
import org.veridger.domain.error.TrustCode;
import org.veridger.domain.error.ValidationCode;
import org.veridger.domain.error.ValidationIssue;
import org.veridger.domain.error.ValidationReason;
import org.veridger.domain.model.Task;
import org.veridger.domain.model.TaskFields;
import org.veridger.domain.rule.IssueStatus;
import org.veridger.domain.value.AuthorId;
import org.veridger.domain.value.IssueId;
import org.veridger.domain.value.Patch;
import org.veridger.domain.value.TaskId;

/** SVC-IDS-01 .. SVC-IDS-13, D-0049: server-assigned ids (PostgreSQL of the test PC). */
@LedgerServiceTest
class IdsTest {

    private static SaveCommand issue(Scenario s, String id, String title) {
        return s.create("issue", id, s.issueFields(title));
    }

    @Test
    @DisplayName("SVC-IDS-01: the project code, not the project id, forms the prefix; ids count from 1")
    void ids01_firstIssuesAndSpecs(Scenario s) {
        SaveResult one = s.newIssue("one");
        SaveResult two = s.newIssue("two");
        SaveResult specOne = s.newSpec("spec one");
        assertThat(one.id()).isEqualTo(s.code() + "-I-0001");
        assertThat(two.id()).isEqualTo(s.code() + "-I-0002");
        assertThat(specOne.id()).isEqualTo(s.code() + "-S-0001");
        assertThat(one.version()).isEqualTo(1);
        assertThat(s.issue(one.id()).status()).isEqualTo(IssueStatus.OPEN);
    }

    @Test
    @DisplayName("SVC-IDS-02 / D-0049: an explicit issue, spec or task id of another project code is ID_PREFIX_MISMATCH")
    void ids02_prefixMismatch(Scenario s) {
        Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR, issue(s, "OTHER-I-0009", "x")),
                ValidationCode.ID_PREFIX_MISMATCH);
        for (String foreignTask : List.of("OTHER-T-0001", "OTHER-T-0000", "OTHER-T-00001", "other-t-0001")) {
            Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR,
                    s.create("task", foreignTask, s.taskFields("x"))), ValidationCode.ID_PREFIX_MISMATCH);
        }
        for (String foreignSpec : List.of("OTHER-S-0001", "OTHER-S-0000", "OTHER-S-00001", "other-s-0001")) {
            Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR,
                    s.create("spec", foreignSpec, s.specFields("x"))), ValidationCode.ID_PREFIX_MISMATCH);
        }
    }

    @Test
    @DisplayName("D-0049: moving an issued-form task or spec to another project is PROJECT_MOVE_FORBIDDEN")
    void d0049_issuedFormTaskAndSpecCannotMoveAcrossProjects(Scenario s) {
        String other = s.otherProject();
        s.grant(Scenario.AUTHOR, other, "spec", "task");
        SaveResult issuedTask = s.newTask("issued task");
        SaveResult issuedSpec = s.newSpec("issued spec");
        Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR,
                s.edit("task", issuedTask.id(), 1, Map.of("project_id", other))),
                TransitionCode.PROJECT_MOVE_FORBIDDEN);
        Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR,
                s.edit("spec", issuedSpec.id(), 1, Map.of("project_id", other))),
                TransitionCode.PROJECT_MOVE_FORBIDDEN);
    }

    private static long counter(Scenario s, String column) {
        return s.count("SELECT " + column + " FROM project WHERE id = ?", s.projectId());
    }

    @Test
    @DisplayName("SVC-IDS-03 / VRF-023: an explicit id with the next number raises the counter, the next id is never a duplicate")
    void ids03_explicitRaisesTheCounter(Scenario s) {
        s.newIssue("one");
        s.newIssue("two");
        s.newIssue("three");
        SaveResult explicit = s.bean(SaveService.class).save(Scenario.AUTHOR, issue(s, s.issueId(4), "x"));
        assertThat(explicit.id()).isEqualTo(s.issueId(4));
        assertThat(counter(s, "next_issue_no")).isEqualTo(4);
        assertThat(s.newIssue("next").id()).isEqualTo(s.issueId(5));
    }

    @Test
    @DisplayName("VRF-024: a free number below the counter is accepted and leaves the counter alone")
    void vrf024_aFreePastNumberIsAccepted(Scenario s) {
        s.newIssue("one");
        s.newIssue("two");
        s.newIssue("three");
        s.ctx().jdbc().sql("UPDATE project SET next_issue_no = 10 WHERE id = ?").param(s.projectId()).update();
        SaveResult explicit = s.bean(SaveService.class).save(Scenario.AUTHOR, issue(s, s.issueId(7), "gap"));
        assertThat(explicit.id()).isEqualTo(s.issueId(7));
        assertThat(counter(s, "next_issue_no")).isEqualTo(10);
        assertThat(s.newIssue("next").id()).isEqualTo(s.issueId(11));
    }

    @Test
    @DisplayName("VRF-025: an explicit number above the next number is ID_NUMBER_AHEAD, nothing is written and the automatic ids go on")
    void vrf025_aNumberAheadIsRefused(Scenario s) {
        s.newIssue("one");
        s.newIssue("two");
        s.newIssue("three");
        long history = s.count("SELECT COUNT(*) FROM history");
        long issues = s.count("SELECT COUNT(*) FROM issue WHERE project_id = ?", s.projectId());
        for (String id : List.of(s.issueId(5), s.issueId(100), s.code() + "-I-999999999999999999")) {
            LedgerException e = Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR,
                    issue(s, id, "ahead")), ValidationCode.ID_NUMBER_AHEAD);
            assertThat(e.args()).containsExactly(id, s.issueId(4));
            ValidationIssue issue = Expect.firstIssue(e);
            assertThat(issue.path()).isEqualTo("id");
            assertThat(issue.reason()).isEqualTo(ValidationReason.NUMBER_ABOVE_NEXT);
            assertThat(issue.reasonArgs()).containsExactly(s.issueId(4));
            assertThat(Expect.message(e)).contains(id, s.issueId(4));
            assertThat(s.count("SELECT COUNT(*) FROM issue WHERE id = ?", id)).isZero();
        }
        assertThat(counter(s, "next_issue_no")).isEqualTo(3);
        assertThat(s.count("SELECT COUNT(*) FROM history")).isEqualTo(history);
        assertThat(s.count("SELECT COUNT(*) FROM issue WHERE project_id = ?", s.projectId())).isEqualTo(issues);
        assertThat(s.newIssue("next").id()).isEqualTo(s.issueId(4));
    }

    @Test
    @DisplayName("VRF-026: an explicit task id of the issued form is taken up to the next number only, a free text id takes nothing")
    void vrf026_taskNumbers(Scenario s) {
        SaveService save = s.bean(SaveService.class);
        assertThat(s.newTask("auto").id()).isEqualTo(s.taskId(1));
        assertThat(save.save(Scenario.AUTHOR, s.create("task", s.taskId(2), s.taskFields("next"))).id())
                .isEqualTo(s.taskId(2));
        assertThat(counter(s, "next_task_no")).isEqualTo(2);
        for (String ahead : List.of(s.taskId(4), s.code() + "-T-123456789012345678")) {
            LedgerException e = Expect.failure(() -> save.save(Scenario.AUTHOR,
                    s.create("task", ahead, s.taskFields("ahead"))), ValidationCode.ID_NUMBER_AHEAD);
            assertThat(e.args()).containsExactly(ahead, s.taskId(3));
            assertThat(s.count("SELECT COUNT(*) FROM task WHERE id = ?", ahead)).isZero();
        }
        for (String free : List.of(s.code() + "-T-first", s.code() + "-T|PIPE", s.code() + "-T-" + "9".repeat(19))) {
            assertThat(save.save(Scenario.AUTHOR, s.create("task", free, s.taskFields("free"))).id()).isEqualTo(free);
        }
        assertThat(counter(s, "next_task_no")).isEqualTo(2);
        assertThat(s.newTask("next").id()).isEqualTo(s.taskId(3));
    }

    @Test
    @DisplayName("VRF-027: a batch with an explicit number that is ahead fails as a whole and names the operation")
    void vrf027_batch(Scenario s) {
        BatchOpInput auto = new BatchOpInput.Save("issue", Fields.of(s.issueFields("auto")), Scenario.REASON, null, null);
        BatchOpInput ahead = new BatchOpInput.Save("issue", Fields.of(s.issueFields("ahead")), Scenario.REASON,
                s.issueId(100), null);
        LedgerException e = Expect.failure(() -> s.bean(BatchService.class).batch(Scenario.AUTHOR, Scenario.uuid(),
                List.of(auto, ahead)), ValidationCode.ID_NUMBER_AHEAD);
        assertThat(e.details()).containsEntry("op_index", List.of("2")).containsEntry("op", List.of("save"));
        assertThat(counter(s, "next_issue_no")).isZero();
        assertThat(s.newIssue("after").id()).isEqualTo(s.issueId(1));
    }

    @Test
    @DisplayName("VRF-046 (D-0049): an explicit issue id is stored only in the canonical form: no other padding, no number 0")
    void vrf046_canonicalIssueIds(Scenario s) {
        s.newIssue("one");
        s.newIssue("two");
        SaveService save = s.bean(SaveService.class);
        long issues = s.count("SELECT COUNT(*) FROM issue WHERE project_id = ?", s.projectId());
        for (String padded : List.of(s.code() + "-I-00002", s.code() + "-I-000003")) {
            long number = Long.parseLong(padded.substring(padded.lastIndexOf('-') + 1));
            LedgerException e = Expect.failure(() -> save.save(Scenario.AUTHOR, issue(s, padded, "padded")),
                    ValidationCode.VALIDATION_FAILED);
            ValidationIssue vIssue = Expect.firstIssue(e);
            assertThat(vIssue.path()).isEqualTo("id");
            assertThat(vIssue.reason()).isEqualTo(ValidationReason.ID_NOT_CANONICAL);
            assertThat(vIssue.reasonArgs()).containsExactly(s.issueId((int) number));
        }
        LedgerException zero = Expect.failure(() -> save.save(Scenario.AUTHOR, issue(s, s.code() + "-I-0000", "zero")),
                ValidationCode.VALIDATION_FAILED);
        assertThat(Expect.firstIssue(zero).path()).isEqualTo("id");
        assertThat(Expect.firstIssue(zero).reason()).isEqualTo(ValidationReason.MUST_BE_POSITIVE);
        assertThat(s.count("SELECT COUNT(*) FROM issue WHERE project_id = ?", s.projectId())).isEqualTo(issues);
        assertThat(counter(s, "next_issue_no")).isEqualTo(2);
        assertThat(save.save(Scenario.AUTHOR, issue(s, s.issueId(3), "three")).id()).isEqualTo(s.issueId(3));
        Expect.failure(() -> save.save(Scenario.AUTHOR, issue(s, s.code() + "-I-999999999999999999", "huge")),
                ValidationCode.ID_NUMBER_AHEAD);
        Expect.failure(() -> save.save(Scenario.AUTHOR, issue(s, s.code().toLowerCase() + "-I-0004", "lower")),
                ValidationCode.VALIDATION_FAILED);
        Expect.failure(() -> save.save(Scenario.AUTHOR, issue(s, s.code() + "-i-0004", "lower")),
                ValidationCode.VALIDATION_FAILED);
        assertThat(IssueId.parse(s.code() + "-I-00002")).isPresent();
    }

    @Test
    @DisplayName("VRF-047 (D-0049): an explicit task id of the issued form is stored only in the canonical form, a free text is unchanged")
    void vrf047_canonicalTaskIds(Scenario s) {
        s.newTask("one");
        SaveService save = s.bean(SaveService.class);
        for (String padded : List.of(s.code() + "-T-00001", s.code() + "-T-1", s.code() + "-T-2",
                s.code() + "-t-0002", s.code().toLowerCase() + "-T-0002", s.code().toLowerCase() + "-t-00002")) {
            long number = Long.parseLong(padded.substring(padded.lastIndexOf('-') + 1));
            LedgerException e = Expect.failure(() -> save.save(Scenario.AUTHOR,
                    s.create("task", padded, s.taskFields("padded"))), ValidationCode.VALIDATION_FAILED);
            ValidationIssue vIssue = Expect.firstIssue(e);
            assertThat(vIssue.path()).isEqualTo("id");
            assertThat(vIssue.reason()).isEqualTo(ValidationReason.ID_NOT_CANONICAL);
            assertThat(vIssue.reasonArgs()).containsExactly(s.taskId((int) number));
        }
        LedgerException zero = Expect.failure(() -> save.save(Scenario.AUTHOR,
                s.create("task", s.code() + "-T-0000", s.taskFields("zero"))), ValidationCode.VALIDATION_FAILED);
        assertThat(Expect.firstIssue(zero).reason()).isEqualTo(ValidationReason.MUST_BE_POSITIVE);
        assertThat(counter(s, "next_task_no")).isEqualTo(1);
        for (String accepted : List.of(s.taskId(2), s.code() + "-T|PIPE", s.code() + "-T-" + "9".repeat(19))) {
            assertThat(save.save(Scenario.AUTHOR, s.create("task", accepted, s.taskFields("ok"))).id())
                    .isEqualTo(accepted);
        }
    }

    @Test
    @DisplayName("VRF-037: two creates with the same explicit id, the next number, at the same time: one is saved, the other is DUPLICATE_KEY")
    void vrf037_sameNumberTwice(Scenario s) {
        s.newIssue("one");
        s.newIssue("two");
        s.newIssue("three");
        List<Callable<SaveResult>> calls = List.of(
                () -> s.bean(SaveService.class).save(Scenario.AUTHOR, issue(s, s.issueId(4), "a")),
                () -> s.bean(SaveService.class).save(Scenario.AUTHOR, issue(s, s.issueId(4), "b")));
        List<Outcome<SaveResult>> outcomes = Parallel.all(calls);
        assertThat(outcomes.stream().filter(Outcome::succeeded)).hasSize(1);
        assertThat(outcomes.stream().filter(o -> !o.succeeded()).map(o -> ((LedgerException) o.failure()).code()))
                .containsExactly(ConflictCode.DUPLICATE_KEY);
        assertThat(counter(s, "next_issue_no")).isEqualTo(4);
        assertThat(s.count("SELECT COUNT(*) FROM issue WHERE id = ?", s.issueId(4))).isEqualTo(1);
    }

    @Test
    @DisplayName("VRF-038: a create without id and a create with the explicit id of the next number at the same time: two numbers, or DUPLICATE_KEY for the explicit one")
    void vrf038_automaticAgainstExplicit(Scenario s) {
        s.newIssue("one");
        s.newIssue("two");
        s.newIssue("three");
        List<Callable<SaveResult>> calls = List.of(
                () -> s.bean(SaveService.class).save(Scenario.AUTHOR, issue(s, null, "automatic")),
                () -> s.bean(SaveService.class).save(Scenario.AUTHOR, issue(s, s.issueId(4), "explicit")));
        List<Outcome<SaveResult>> outcomes = Parallel.all(calls);
        assertThat(outcomes.get(0).failure()).as("the automatic create always succeeds").isNull();
        if (outcomes.get(1).succeeded()) {
            assertThat(outcomes.get(0).value().id()).isEqualTo(s.issueId(5));
            assertThat(counter(s, "next_issue_no")).isEqualTo(5);
        } else {
            assertThat(((LedgerException) outcomes.get(1).failure()).code()).isEqualTo(ConflictCode.DUPLICATE_KEY);
            assertThat(outcomes.get(0).value().id()).isEqualTo(s.issueId(4));
            assertThat(counter(s, "next_issue_no")).isEqualTo(4);
        }
    }

    @Test
    @DisplayName("VRF-022: two explicit ids at the same time, the next number and the one after it: the second is saved, ID_NUMBER_AHEAD or ID_COUNTER_CHANGED, the counter is the highest that was saved")
    void vrf022_race(Scenario s) {
        s.newIssue("one");
        s.newIssue("two");
        s.newIssue("three");
        List<Callable<SaveResult>> calls = List.of(
                () -> s.bean(SaveService.class).save(Scenario.AUTHOR, issue(s, s.issueId(4), "next")),
                () -> s.bean(SaveService.class).save(Scenario.AUTHOR, issue(s, s.issueId(5), "after")));
        List<Outcome<SaveResult>> outcomes = Parallel.all(calls);
        assertThat(outcomes.get(0).failure()).as("the next number is always accepted").isNull();
        if (outcomes.get(1).succeeded()) {
            assertThat(counter(s, "next_issue_no")).isEqualTo(5);
        } else {
            assertThat(outcomes.get(1).failure()).isInstanceOf(LedgerException.class);
            assertThat(((LedgerException) outcomes.get(1).failure()).code()).isIn(ValidationCode.ID_NUMBER_AHEAD,
                    ConflictCode.ID_COUNTER_CHANGED);
            assertThat(counter(s, "next_issue_no")).isEqualTo(4);
        }
    }

    @Test
    @DisplayName("SVC-IDS-04: an explicit id that exists")
    void ids04_duplicate(Scenario s) {
        SaveResult one = s.newIssue("one");
        Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR, issue(s, one.id(), "again")),
                ConflictCode.DUPLICATE_KEY);
    }

    @Test
    @DisplayName("SVC-IDS-05: failed requests consume no number")
    void ids05_failuresConsumeNothing(Scenario s) {
        Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR, new SaveCommand(Scenario.uuid(), "issue", null, 0L,
                Fields.of(s.issueFields(" ")), Scenario.REASON)), ValidationCode.VALIDATION_FAILED);
        Expect.failure(() -> s.bean(SaveService.class).save(Scenario.NOGRANT, issue(s, null, "x")),
                TrustCode.SESSION_NOT_GRANTED);
        Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR, new SaveCommand(Scenario.uuid(), "issue", null, 0L,
                Fields.of(s.issueFields("x")), "short")), ValidationCode.REASON_INVALID);
        assertThat(s.newIssue("good").id()).isEqualTo(s.issueId(1));
    }

    @Test
    @DisplayName("SVC-IDS-06: tasks are numbered like issues, explicit free-text ids are accepted")
    void ids06_tasks(Scenario s) {
        assertThat(s.newTask("auto").id()).isEqualTo(s.taskId(1));
        assertThat(s.bean(SaveService.class).save(Scenario.AUTHOR,
                s.create("task", s.code() + "-T-first", s.taskFields("explicit"))).id()).isEqualTo(s.code() + "-T-first");
        assertThat(s.bean(SaveService.class).save(Scenario.AUTHOR,
                s.create("task", s.code() + "-T|PIPE", s.taskFields("pipe"))).id()).isEqualTo(s.code() + "-T|PIPE");
    }

    @Test
    @DisplayName("SVC-IDS-07: an issued task id is never a duplicate of a task that exists")
    void ids07_issuedTaskIdSkipsExisting(Scenario s) {
        s.newTask("first");
        TaskFields fields = TaskFields.of(Patch.set(s.projectId()), Patch.set("planted"), Patch.set("p"),
                Patch.set("s"), Patch.absent(), Patch.absent(), Patch.absent());
        Task planted = Task.plan(TaskId.of(s.taskId(2)), fields, s.ctx().clock().instant());
        s.ctx().tx(() -> ScopedValue.where(ActorScope.CURRENT, AuthorId.OWNER)
                .call(() -> s.bean(TaskRepository.class).save(planted)));
        assertThat(s.newTask("next").id()).isEqualTo(s.taskId(3));
    }

    @Test
    @DisplayName("SVC-IDS-08: two projects number independently")
    void ids08_independentProjects(Scenario s) {
        String other = s.otherProject();
        s.grant(Scenario.AUTHOR, other, "issue", "spec", "task");
        var fields = s.issueFields("other");
        fields.put("project_id", other);
        SaveResult otherIssue = s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("issue", null, fields));
        assertThat(otherIssue.id()).endsWith("-I-0001").doesNotStartWith(s.code());
        assertThat(s.newIssue("mine").id()).isEqualTo(s.issueId(1));
        assertThat(s.newSpec("mine").id()).isEqualTo(s.specId(1));
        assertThat(s.newTask("mine").id()).isEqualTo(s.taskId(1));
    }

    @Test
    @DisplayName("SVC-IDS-09: a criterion needs an explicit id")
    void ids09_criterionNeedsAnId(Scenario s) {
        var failure = Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR,
                s.create("criterion", null, s.criterionFields("r", "c"))), ValidationCode.VALIDATION_FAILED);
        assertThat(failure.args()).first().isEqualTo("id");
    }

    @Test
    @DisplayName("SVC-IDS-10: 50 parallel issues and 50 parallel tasks get distinct sequential ids")
    void ids10_parallelCreates(Scenario s) {
        String actor = s.actorWith("issue", "task");
        List<Callable<SaveResult>> calls = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            int n = i;
            calls.add(() -> s.bean(SaveService.class).save(actor, s.create("issue", null, s.issueFields("i" + n))));
            calls.add(() -> s.bean(SaveService.class).save(actor, s.create("task", null, s.taskFields("t" + n))));
        }
        List<Outcome<SaveResult>> outcomes = Parallel.all(calls, 20);
        assertThat(outcomes).allSatisfy(o -> assertThat(o.failure()).isNull());
        assertThat(outcomes.stream().map(o -> o.value().id()).filter(id -> id.contains("-I-")).toList())
                .containsExactlyInAnyOrderElementsOf(IntStream.rangeClosed(1, 50).mapToObj(s::issueId).toList());
        assertThat(outcomes.stream().map(o -> o.value().id()).filter(id -> id.contains("-T-")).toList())
                .containsExactlyInAnyOrderElementsOf(IntStream.rangeClosed(1, 50).mapToObj(s::taskId).toList());
    }

    @Test
    @DisplayName("SVC-IDS-11: the number grows past 9999")
    void ids11_pastNineThousand(Scenario s) {
        s.ctx().jdbc().sql("UPDATE project SET next_issue_no = 9999 WHERE id = ?").param(s.projectId()).update();
        assertThat(s.newIssue("big").id()).isEqualTo(s.code() + "-I-10000");
    }

    @Test
    @DisplayName("SVC-IDS-12: a batch of creates is consecutive, and a failing batch gives its numbers back")
    void ids12_batches(Scenario s) {
        BatchOpInput create = new BatchOpInput.Save("issue", Fields.of(s.issueFields("b")), Scenario.REASON, null, null);
        BatchOpInput failing = new BatchOpInput.Transition("issue", s.issueId(99), 1L, "DISPUTED", Scenario.REASON,
                Scenario.EVIDENCE, null, null, null);
        Expect.failure(() -> s.bean(BatchService.class).batch(Scenario.AUTHOR, Scenario.uuid(),
                List.of(create, create, failing)), LookupCode.NOT_FOUND);
        BatchResult ok = s.bean(BatchService.class).batch(Scenario.AUTHOR, Scenario.uuid(), List.of(create, create));
        assertThat(ok.results().stream().map(r -> ((SaveResult) r).id()))
                .containsExactly(s.issueId(1), s.issueId(2));
    }

    @Test
    @DisplayName("SVC-IDS-13: history, deliverable, case and grant ids are distinct and gapless after 64 concurrent writes")
    void ids13_surrogateKeys(Scenario s) {
        List<SaveResult> issues = IntStream.range(0, 32).mapToObj(i -> s.newIssue("i" + i)).toList();
        List<Callable<WriteResult>> calls = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            String issueId = issues.get(i).id();
            String ref = Scenario.ref((char) ('a' + i % 6));
            calls.add(() -> s.deliver(Scenario.AUTHOR, issueId, ref));
            String caseOn = issues.get(16 + i).id();
            calls.add(() -> s.bean(RecordCaseService.class).recordCase(Scenario.AUTHOR, new RecordCaseCommand(Scenario.uuid(),
                    caseOn, "seen", Scenario.REASON, null, null, null)));
            calls.add(() -> s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("task", null,
                    s.taskFields("t"))));
            String author = "claude:grant" + i;
            s.ctx().enroll(author);
            calls.add(() -> {
                s.bean(GrantService.class).grant(new GrantService.GrantCommand(author, s.projectId(),
                        List.of("issue"), null, null), TestOwners.OWNER);
                return null;
            });
        }
        assertThat(Parallel.all(calls, 16)).allSatisfy(o -> assertThat(o.failure()).isNull());
        Stream.of("history", "deliverable", "issue_case", "session_grant").forEach(table -> {
            assertThat(s.count("SELECT COUNT(DISTINCT id) FROM " + table)).as(table).isEqualTo(
                    s.count("SELECT COUNT(*) FROM " + table));
            assertThat(s.count("SELECT MAX(id) FROM " + table)).as(table + " has no gaps").isEqualTo(
                    s.count("SELECT COUNT(*) FROM " + table));
        });
    }
}
