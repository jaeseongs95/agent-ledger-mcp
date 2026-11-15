package org.pgledger.application;

import static org.assertj.core.api.Assertions.assertThat;
import org.pgledger.testing.TestOwners;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.application.admin.GrantService;
import org.pgledger.application.support.Expect;
import org.pgledger.application.support.Fields;
import org.pgledger.application.support.Parallel;
import org.pgledger.application.support.Parallel.Outcome;
import org.pgledger.application.support.Scenario;
import org.pgledger.application.support.LedgerServiceTest;
import org.pgledger.application.write.BatchOpInput;
import org.pgledger.application.write.BatchResult;
import org.pgledger.application.write.BatchService;
import org.pgledger.application.write.RecordCaseCommand;
import org.pgledger.application.write.RecordCaseService;
import org.pgledger.application.write.SaveCommand;
import org.pgledger.application.write.SaveService;
import org.pgledger.application.write.WriteResult;
import org.pgledger.application.write.WriteResult.SaveResult;
import org.pgledger.database.repository.TaskRepository;
import org.pgledger.domain.context.ActorScope;
import org.pgledger.domain.error.ConflictCode;
import org.pgledger.domain.error.LedgerException;
import org.pgledger.domain.error.TransitionCode;
import org.pgledger.domain.error.ValidationCode;
import org.pgledger.domain.error.ValidationIssue;
import org.pgledger.domain.error.ValidationReason;
import org.pgledger.domain.model.Task;
import org.pgledger.domain.model.TaskFields;
import org.pgledger.domain.rule.DefectStatus;
import org.pgledger.domain.value.AuthorId;
import org.pgledger.domain.value.Patch;
import org.pgledger.domain.value.TaskId;

/** SVC-IDS-01 .. SVC-IDS-13: server-assigned ids (PostgreSQL of the test PC). */
@LedgerServiceTest
class IdsTest {

    private static SaveCommand defect(Scenario s, String id, String title) {
        return s.create("defect", id, s.defectFields(title));
    }

    @Test
    @DisplayName("SVC-IDS-01: the project code, not the project id, forms the prefix; ids count from 1")
    void ids01_firstDefects(Scenario s) {
        SaveResult one = s.newDefect("one");
        SaveResult two = s.newDefect("two");
        assertThat(one.id()).isEqualTo(s.code() + "-D-0001");
        assertThat(two.id()).isEqualTo(s.code() + "-D-0002");
        assertThat(one.version()).isEqualTo(1);
        assertThat(s.defect(one.id()).status()).isEqualTo(DefectStatus.OPEN);
    }

    @Test
    @DisplayName("SVC-IDS-02: an explicit defect id of another project code")
    void ids02_prefixMismatch(Scenario s) {
        Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR, defect(s, "OTHER-D-0009", "x")),
                ValidationCode.ID_PREFIX_MISMATCH);
    }

    private static long counter(Scenario s, String column) {
        return s.count("SELECT " + column + " FROM project WHERE id = ?", s.projectId());
    }

    @Test
    @DisplayName("SVC-IDS-03 / VRF-023: an explicit id with the next number raises the counter, the next id is never a duplicate")
    void ids03_explicitRaisesTheCounter(Scenario s) {
        s.newDefect("one");
        s.newDefect("two");
        s.newDefect("three");
        SaveResult explicit = s.bean(SaveService.class).save(Scenario.AUTHOR, defect(s, s.defectId(4), "x"));
        assertThat(explicit.id()).isEqualTo(s.defectId(4));
        assertThat(counter(s, "next_defect_no")).isEqualTo(4);
        assertThat(s.newDefect("next").id()).isEqualTo(s.defectId(5));
    }

    @Test
    @DisplayName("VRF-024: a free number below the counter is accepted and leaves the counter alone")
    void vrf024_aFreePastNumberIsAccepted(Scenario s) {
        s.newDefect("one");
        s.newDefect("two");
        s.newDefect("three");
        // what an import leaves behind: numbers 4 to 10 were used elsewhere, the counter is at 10, number 7 is free here
        s.ctx().jdbc().sql("UPDATE project SET next_defect_no = 10 WHERE id = ?").param(s.projectId()).update();
        SaveResult explicit = s.bean(SaveService.class).save(Scenario.AUTHOR, defect(s, s.defectId(7), "gap"));
        assertThat(explicit.id()).isEqualTo(s.defectId(7));
        assertThat(counter(s, "next_defect_no")).isEqualTo(10);
        assertThat(s.newDefect("next").id()).isEqualTo(s.defectId(11));
    }

    @Test
    @DisplayName("VRF-025: an explicit number above the next number is ID_NUMBER_AHEAD, nothing is written and the automatic ids go on")
    void vrf025_aNumberAheadIsRefused(Scenario s) {
        s.newDefect("one");
        s.newDefect("two");
        s.newDefect("three");
        long history = s.count("SELECT COUNT(*) FROM history");
        long defects = s.count("SELECT COUNT(*) FROM defect WHERE project_id = ?", s.projectId());
        for (String id : List.of(s.defectId(5), s.defectId(100), s.code() + "-D-999999999999999999")) {
            LedgerException e = Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR,
                    defect(s, id, "ahead")), ValidationCode.ID_NUMBER_AHEAD);
            assertThat(e.args()).containsExactly(id, s.defectId(4));
            ValidationIssue issue = Expect.firstIssue(e);
            assertThat(issue.path()).isEqualTo("id");
            assertThat(issue.reason()).isEqualTo(ValidationReason.NUMBER_ABOVE_NEXT);
            assertThat(issue.reasonArgs()).containsExactly(s.defectId(4));
            assertThat(Expect.message(e)).contains(id, s.defectId(4));
            assertThat(s.count("SELECT COUNT(*) FROM defect WHERE id = ?", id)).isZero();
        }
        assertThat(counter(s, "next_defect_no")).isEqualTo(3);
        assertThat(s.count("SELECT COUNT(*) FROM history")).isEqualTo(history);
        assertThat(s.count("SELECT COUNT(*) FROM defect WHERE project_id = ?", s.projectId())).isEqualTo(defects);
        assertThat(s.newDefect("next").id()).isEqualTo(s.defectId(4));
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
        // free texts and a number of 19 digits (a free id: it has no number) are accepted
        for (String free : List.of(s.code() + "-T-first", s.code() + "-T|PIPE", s.code() + "-T-" + "9".repeat(19))) {
            assertThat(save.save(Scenario.AUTHOR, s.create("task", free, s.taskFields("free"))).id()).isEqualTo(free);
        }
        assertThat(counter(s, "next_task_no")).isEqualTo(2);
        assertThat(s.newTask("next").id()).isEqualTo(s.taskId(3));
    }

    @Test
    @DisplayName("VRF-027: a batch with an explicit number that is ahead fails as a whole and names the operation")
    void vrf027_batch(Scenario s) {
        BatchOpInput auto = new BatchOpInput.Save("defect", Fields.of(s.defectFields("auto")), Scenario.REASON, null, null);
        BatchOpInput ahead = new BatchOpInput.Save("defect", Fields.of(s.defectFields("ahead")), Scenario.REASON,
                s.defectId(100), null);
        LedgerException e = Expect.failure(() -> s.bean(BatchService.class).batch(Scenario.AUTHOR, Scenario.uuid(),
                List.of(auto, ahead)), ValidationCode.ID_NUMBER_AHEAD);
        assertThat(e.details()).containsEntry("op_index", List.of("2")).containsEntry("op", List.of("save"));
        assertThat(counter(s, "next_defect_no")).isZero();
        assertThat(s.newDefect("after").id()).isEqualTo(s.defectId(1));
    }

    @Test
    @DisplayName("VRF-046 (D-0049): an explicit defect id is stored only in the canonical form: no other padding, no number 0")
    void vrf046_canonicalDefectIds(Scenario s) {
        s.newDefect("one");
        s.newDefect("two");
        SaveService save = s.bean(SaveService.class);
        long defects = s.count("SELECT COUNT(*) FROM defect WHERE project_id = ?", s.projectId());
        // 00002 has the number of E2E-D-0002 (which exists), 000003 is the next number padded to six digits
        for (String padded : List.of(s.code() + "-D-00002", s.code() + "-D-000003")) {
            long number = Long.parseLong(padded.substring(padded.lastIndexOf('-') + 1));
            LedgerException e = Expect.failure(() -> save.save(Scenario.AUTHOR, defect(s, padded, "padded")),
                    ValidationCode.VALIDATION_FAILED);
            ValidationIssue issue = Expect.firstIssue(e);
            assertThat(issue.path()).isEqualTo("id");
            assertThat(issue.reason()).isEqualTo(ValidationReason.ID_NOT_CANONICAL);
            assertThat(issue.reasonArgs()).containsExactly(s.defectId((int) number));
        }
        LedgerException zero = Expect.failure(() -> save.save(Scenario.AUTHOR, defect(s, s.code() + "-D-0000", "zero")),
                ValidationCode.VALIDATION_FAILED);
        assertThat(Expect.firstIssue(zero).path()).isEqualTo("id");
        assertThat(Expect.firstIssue(zero).reason()).isEqualTo(ValidationReason.MUST_BE_POSITIVE);
        assertThat(s.count("SELECT COUNT(*) FROM defect WHERE project_id = ?", s.projectId())).isEqualTo(defects);
        assertThat(counter(s, "next_defect_no")).isEqualTo(2);
        // the canonical id of the next number is saved, and the largest number (18 digits) is canonical but ahead
        assertThat(save.save(Scenario.AUTHOR, defect(s, s.defectId(3), "three")).id()).isEqualTo(s.defectId(3));
        Expect.failure(() -> save.save(Scenario.AUTHOR, defect(s, s.code() + "-D-999999999999999999", "huge")),
                ValidationCode.ID_NUMBER_AHEAD);
        // the pattern of DefectId itself still reads the padded id (reads and the import use it)
        assertThat(org.pgledger.domain.value.DefectId.parse(s.code() + "-D-00002")).isPresent();
    }

    @Test
    @DisplayName("VRF-047 (D-0049): an explicit task id of the issued form is stored only in the canonical form, a free text is unchanged")
    void vrf047_canonicalTaskIds(Scenario s) {
        s.newTask("one");
        SaveService save = s.bean(SaveService.class);
        for (String padded : List.of(s.code() + "-T-00001", s.code() + "-T-1", s.code() + "-T-2")) {
            long number = Long.parseLong(padded.substring(padded.lastIndexOf('-') + 1));
            LedgerException e = Expect.failure(() -> save.save(Scenario.AUTHOR,
                    s.create("task", padded, s.taskFields("padded"))), ValidationCode.VALIDATION_FAILED);
            ValidationIssue issue = Expect.firstIssue(e);
            assertThat(issue.path()).isEqualTo("id");
            assertThat(issue.reason()).isEqualTo(ValidationReason.ID_NOT_CANONICAL);
            assertThat(issue.reasonArgs()).containsExactly(s.taskId((int) number));
        }
        LedgerException zero = Expect.failure(() -> save.save(Scenario.AUTHOR,
                s.create("task", s.code() + "-T-0000", s.taskFields("zero"))), ValidationCode.VALIDATION_FAILED);
        assertThat(Expect.firstIssue(zero).reason()).isEqualTo(ValidationReason.MUST_BE_POSITIVE);
        assertThat(counter(s, "next_task_no")).isEqualTo(1);
        // the canonical next id, a free text and a number of 19 digits are saved
        for (String accepted : List.of(s.taskId(2), s.code() + "-T|PIPE", s.code() + "-T-" + "9".repeat(19))) {
            assertThat(save.save(Scenario.AUTHOR, s.create("task", accepted, s.taskFields("ok"))).id())
                    .isEqualTo(accepted);
        }
    }

    @Test
    @DisplayName("VRF-037: two creates with the same explicit id, the next number, at the same time: one is saved, the other is DUPLICATE_KEY")
    void vrf037_sameNumberTwice(Scenario s) {
        s.newDefect("one");
        s.newDefect("two");
        s.newDefect("three");
        List<Callable<SaveResult>> calls = List.of(
                () -> s.bean(SaveService.class).save(Scenario.AUTHOR, defect(s, s.defectId(4), "a")),
                () -> s.bean(SaveService.class).save(Scenario.AUTHOR, defect(s, s.defectId(4), "b")));
        List<Outcome<SaveResult>> outcomes = Parallel.all(calls);
        assertThat(outcomes.stream().filter(Outcome::succeeded)).hasSize(1);
        assertThat(outcomes.stream().filter(o -> !o.succeeded()).map(o -> ((LedgerException) o.failure()).code()))
                .containsExactly(ConflictCode.DUPLICATE_KEY);
        assertThat(counter(s, "next_defect_no")).isEqualTo(4);
        assertThat(s.count("SELECT COUNT(*) FROM defect WHERE id = ?", s.defectId(4))).isEqualTo(1);
    }

    @Test
    @DisplayName("VRF-038: a create without id and a create with the explicit id of the next number at the same time: two numbers, or DUPLICATE_KEY for the explicit one")
    void vrf038_automaticAgainstExplicit(Scenario s) {
        s.newDefect("one");
        s.newDefect("two");
        s.newDefect("three");
        List<Callable<SaveResult>> calls = List.of(
                () -> s.bean(SaveService.class).save(Scenario.AUTHOR, defect(s, null, "automatic")),
                () -> s.bean(SaveService.class).save(Scenario.AUTHOR, defect(s, s.defectId(4), "explicit")));
        List<Outcome<SaveResult>> outcomes = Parallel.all(calls);
        assertThat(outcomes.get(0).failure()).as("the automatic create always succeeds").isNull();
        if (outcomes.get(1).succeeded()) {
            assertThat(outcomes.get(0).value().id()).isEqualTo(s.defectId(5));
            assertThat(counter(s, "next_defect_no")).isEqualTo(5);
        } else {
            assertThat(((LedgerException) outcomes.get(1).failure()).code()).isEqualTo(ConflictCode.DUPLICATE_KEY);
            assertThat(outcomes.get(0).value().id()).isEqualTo(s.defectId(4));
            assertThat(counter(s, "next_defect_no")).isEqualTo(4);
        }
    }

    @Test
    @DisplayName("VRF-022: two explicit ids at the same time, the next number and the one after it: each is saved or ID_NUMBER_AHEAD, the counter is the highest that was saved")
    void vrf022_race(Scenario s) {
        s.newDefect("one");
        s.newDefect("two");
        s.newDefect("three");
        List<Callable<SaveResult>> calls = List.of(
                () -> s.bean(SaveService.class).save(Scenario.AUTHOR, defect(s, s.defectId(4), "next")),
                () -> s.bean(SaveService.class).save(Scenario.AUTHOR, defect(s, s.defectId(5), "after")));
        List<Outcome<SaveResult>> outcomes = Parallel.all(calls);
        assertThat(outcomes.get(0).failure()).as("the next number is always accepted").isNull();
        if (outcomes.get(1).succeeded()) {
            assertThat(counter(s, "next_defect_no")).isEqualTo(5);
        } else {
            assertThat(outcomes.get(1).failure()).isInstanceOf(LedgerException.class);
            assertThat(((LedgerException) outcomes.get(1).failure()).code()).isEqualTo(ValidationCode.ID_NUMBER_AHEAD);
            assertThat(counter(s, "next_defect_no")).isEqualTo(4);
        }
    }

    @Test
    @DisplayName("SVC-IDS-04: an explicit id that exists")
    void ids04_duplicate(Scenario s) {
        SaveResult one = s.newDefect("one");
        Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR, defect(s, one.id(), "again")),
                ConflictCode.DUPLICATE_KEY);
    }

    @Test
    @DisplayName("SVC-IDS-05: failed requests consume no number")
    void ids05_failuresConsumeNothing(Scenario s) {
        Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR, new SaveCommand(Scenario.uuid(), "defect", null, 0L,
                Fields.of(s.defectFields(" ")), Scenario.REASON)), ValidationCode.VALIDATION_FAILED);
        Expect.failure(() -> s.bean(SaveService.class).save(Scenario.NOGRANT, defect(s, null, "x")),
                org.pgledger.domain.error.TrustCode.SESSION_NOT_GRANTED);
        Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR, new SaveCommand(Scenario.uuid(), "defect", null, 0L,
                Fields.of(s.defectFields("x")), "short")), ValidationCode.REASON_INVALID);
        assertThat(s.newDefect("good").id()).isEqualTo(s.defectId(1));
    }

    @Test
    @DisplayName("SVC-IDS-06: tasks are numbered like defects, explicit free-text ids are accepted")
    void ids06_tasks(Scenario s) {
        assertThat(s.newTask("auto").id()).isEqualTo(s.taskId(1));
        // changed by D-0049: the issued form with a number is held to the canonical form (VRF-047), a free text is not
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
        s.grant(Scenario.AUTHOR, other, "defect", "task");
        var fields = s.defectFields("other");
        fields.put("project_id", other);
        SaveResult otherDefect = s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("defect", null, fields));
        assertThat(otherDefect.id()).endsWith("-D-0001").doesNotStartWith(s.code());
        assertThat(s.newDefect("mine").id()).isEqualTo(s.defectId(1));
        assertThat(s.newTask("mine").id()).isEqualTo(s.taskId(1));
    }

    @Test
    @DisplayName("SVC-IDS-09: a condition needs an explicit id")
    void ids09_conditionNeedsAnId(Scenario s) {
        var failure = Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR,
                s.create("condition", null, s.conditionFields("r", "c"))), ValidationCode.VALIDATION_FAILED);
        assertThat(failure.args()).first().isEqualTo("id");
    }

    @Test
    @DisplayName("SVC-IDS-10: 50 parallel defects and 50 parallel tasks get distinct sequential ids")
    void ids10_parallelCreates(Scenario s) {
        String actor = s.actorWith("defect", "task");
        List<Callable<SaveResult>> calls = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            int n = i;
            calls.add(() -> s.bean(SaveService.class).save(actor, s.create("defect", null, s.defectFields("d" + n))));
            calls.add(() -> s.bean(SaveService.class).save(actor, s.create("task", null, s.taskFields("t" + n))));
        }
        List<Outcome<SaveResult>> outcomes = Parallel.all(calls, 20);
        assertThat(outcomes).allSatisfy(o -> assertThat(o.failure()).isNull());
        assertThat(outcomes.stream().map(o -> o.value().id()).filter(id -> id.contains("-D-")).toList())
                .containsExactlyInAnyOrderElementsOf(IntStream.rangeClosed(1, 50).mapToObj(s::defectId).toList());
        assertThat(outcomes.stream().map(o -> o.value().id()).filter(id -> id.contains("-T-")).toList())
                .containsExactlyInAnyOrderElementsOf(IntStream.rangeClosed(1, 50).mapToObj(s::taskId).toList());
    }

    @Test
    @DisplayName("SVC-IDS-11: the number grows past 9999")
    void ids11_pastNineThousand(Scenario s) {
        s.ctx().jdbc().sql("UPDATE project SET next_defect_no = 9999 WHERE id = ?").param(s.projectId()).update();
        assertThat(s.newDefect("big").id()).isEqualTo(s.code() + "-D-10000");
    }

    @Test
    @DisplayName("SVC-IDS-12: a batch of creates is consecutive, and a failing batch gives its numbers back")
    void ids12_batches(Scenario s) {
        BatchOpInput create = new BatchOpInput.Save("defect", Fields.of(s.defectFields("b")), Scenario.REASON, null, null);
        BatchOpInput failing = new BatchOpInput.Transition("defect", s.defectId(99), 1L, "DISPUTED", Scenario.REASON,
                Scenario.EVIDENCE, null, null, null);
        Expect.failure(() -> s.bean(BatchService.class).batch(Scenario.AUTHOR, Scenario.uuid(), List.of(create, create, failing)),
                org.pgledger.domain.error.LookupCode.NOT_FOUND);
        BatchResult ok = s.bean(BatchService.class).batch(Scenario.AUTHOR, Scenario.uuid(), List.of(create, create));
        assertThat(ok.results().stream().map(r -> ((SaveResult) r).id()))
                .containsExactly(s.defectId(1), s.defectId(2));
    }

    @Test
    @DisplayName("SVC-IDS-13: history, fix, case and grant ids are distinct and gapless after 64 concurrent writes")
    void ids13_surrogateKeys(Scenario s) {
        List<SaveResult> defects = IntStream.range(0, 32).mapToObj(i -> s.newDefect("d" + i)).toList();
        List<Callable<WriteResult>> calls = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            String defect = defects.get(i).id();
            String commit = Scenario.commit((char) ('a' + i % 6));
            calls.add(() -> s.fix(Scenario.AUTHOR, defect, commit));
            String caseOn = defects.get(16 + i).id();
            calls.add(() -> s.bean(RecordCaseService.class).recordCase(Scenario.AUTHOR, new RecordCaseCommand(Scenario.uuid(),
                    caseOn, "seen", Scenario.REASON, null, null, null)));
            calls.add(() -> s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("task", null,
                    s.taskFields("t"))));
            String author = "claude:grant" + i;
            s.ctx().enroll(author);
            calls.add(() -> {
                s.bean(GrantService.class).grant(new GrantService.GrantCommand(author, s.projectId(),
                        List.of("defect"), null, null), TestOwners.OWNER);
                return null;
            });
        }
        assertThat(Parallel.all(calls, 16)).allSatisfy(o -> assertThat(o.failure()).isNull());
        Stream.of("history", "defect_fix", "defect_case", "session_grant").forEach(table -> {
            assertThat(s.count("SELECT COUNT(DISTINCT id) FROM " + table)).as(table).isEqualTo(
                    s.count("SELECT COUNT(*) FROM " + table));
            assertThat(s.count("SELECT MAX(id) FROM " + table)).as(table + " has no gaps").isEqualTo(
                    s.count("SELECT COUNT(*) FROM " + table));
        });
    }
}
