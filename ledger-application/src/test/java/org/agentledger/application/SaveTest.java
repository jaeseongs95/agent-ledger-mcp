// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.agentledger.application.admin.CommonCodeService;
import org.agentledger.application.admin.CommonCodeService.CodeCommand;
import org.agentledger.application.history.Change;
import org.agentledger.application.support.Expect;
import org.agentledger.application.support.Fields;
import org.agentledger.application.support.LedgerServiceTest;
import org.agentledger.application.support.Scenario;
import org.agentledger.application.write.BatchOpInput;
import org.agentledger.application.write.BatchService;
import org.agentledger.application.write.SaveCommand;
import org.agentledger.application.write.SaveService;
import org.agentledger.application.write.WriteResult.SaveResult;
import org.agentledger.database.repository.CriterionRepository;
import org.agentledger.domain.error.ConflictCode;
import org.agentledger.domain.error.LedgerException;
import org.agentledger.domain.error.LookupCode;
import org.agentledger.domain.error.TransitionCode;
import org.agentledger.domain.error.TrustCode;
import org.agentledger.domain.error.ValidationCode;
import org.agentledger.domain.error.ValidationException;
import org.agentledger.domain.model.HistoryEntry;
import org.agentledger.domain.rule.ActorAssurance;
import org.agentledger.domain.rule.CriterionState;
import org.agentledger.domain.rule.HistoryEntity;
import org.agentledger.domain.rule.HistoryOp;
import org.agentledger.domain.rule.TaskState;
import org.agentledger.domain.value.CriterionId;
import org.agentledger.testing.TestOwners;

/** SVC-SAV-01 .. SVC-SAV-19: save (create and edit of an issue, spec, task or criterion). */
@LedgerServiceTest
class SaveTest {

    private static Map<String, String> with(Map<String, String> fields, String key, String value) {
        Map<String, String> copy = new LinkedHashMap<>(fields);
        copy.put(key, value);
        return copy;
    }

    private static Map<String, String> without(Map<String, String> fields, String key) {
        Map<String, String> copy = new LinkedHashMap<>(fields);
        copy.remove(key);
        return copy;
    }

    @Test
    @DisplayName("SVC-SAV-01: creating an issue writes the row and a CREATE history row with every given field")
    void sav01_createIssue(Scenario s) {
        var fields = s.issueFields("audio sync gap");
        SaveResult result = s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("issue", null, fields));
        assertThat(result.status().name()).isEqualTo("COMMITTED");
        assertThat(result.entity()).isEqualTo("issue");
        assertThat(result.version()).isEqualTo(1);
        assertThat(result.author()).isEqualTo(Scenario.AUTHOR);
        assertThat(result.login()).isEqualTo("ledger_author");
        HistoryEntry row = s.historyRow(result.historyId());
        assertThat(row.op()).isEqualTo(HistoryOp.CREATE);
        assertThat(row.entity()).isEqualTo(HistoryEntity.ISSUE);
        assertThat(row.entityId()).isEqualTo(result.id());
        assertThat(row.beforeData()).isNull();
        assertThat(row.actorAssurance()).isEqualTo(ActorAssurance.DECLARED);
        Map<String, Change> expected = new LinkedHashMap<>();
        fields.forEach((k, v) -> expected.put(k, new Change(null, v)));
        assertThat(s.changes(result.historyId()).entries()).containsExactlyInAnyOrderEntriesOf(expected);
    }

    @Test
    @DisplayName("SVC-SAV-02: an issue without severity needs one of the valid codes")
    void sav02_severityRequired(Scenario s) {
        LedgerException e = Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR,
                s.create("issue", null, without(s.issueFields("i"), "severity"))), ValidationCode.CODE_REQUIRED);
        assertThat(e.details()).containsEntry("valid", List.of("P1", "P2", "P3"));
        assertThat(e.args()).containsExactly("issue.severity", "P1, P2, P3", "ISSUE_SEVERITY");
    }

    @Test
    @DisplayName("SVC-SAV-02: an issue without category needs one of the active categories")
    void sav02_categoryRequired(Scenario s) {
        LedgerException e = Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR,
                s.create("issue", null, without(s.issueFields("i"), "category"))), ValidationCode.CODE_REQUIRED);
        assertThat(e.details().get("valid")).contains("ARC");
    }

    @Test
    @DisplayName("SVC-SAV-03: UNRATED is not allowed on a new issue")
    void sav03_unrated(Scenario s) {
        LedgerException e = Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR,
                s.create("issue", null, with(s.issueFields("i"), "severity", "UNRATED"))),
                ValidationCode.CODE_INVALID);
        assertThat(e.details()).containsEntry("valid", List.of("P1", "P2", "P3"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"BGU", "RETIRED"})
    @DisplayName("SVC-SAV-04: an unknown or an inactive category on a new issue")
    void sav04_categoryIsChecked(String category, Scenario s) {
        Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR,
                s.create("issue", null, with(s.issueFields("i"), "category", category))),
                ValidationCode.CODE_INVALID);
    }

    @Test
    @DisplayName("SVC-SAV-04: an edit may not change a category to an inactive one")
    void sav04_editToInactive(Scenario s) {
        SaveResult i = s.newIssue("i");
        Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR, s.edit("issue", i.id(), 1,
                Map.of("category", "RETIRED"))), ValidationCode.CODE_INVALID);
    }

    @Test
    @DisplayName("SVC-SAV-04: an unrelated edit of a row that holds a retired category is allowed")
    void sav04_retiredCategoryStaysOnUnrelatedEdits(Scenario s) {
        CommonCodeService codes = s.bean(CommonCodeService.class);
        String category = "RET_" + s.code();
        codes.set(new CodeCommand("ISSUE_CATEGORY", category, "나중에 폐기", "Retired later", null, null, 8, "ACTIVE"),
                TestOwners.OWNER);
        SaveResult i = s.bean(SaveService.class).save(Scenario.AUTHOR,
                s.create("issue", null, with(s.issueFields("i"), "category", category)));
        codes.set(new CodeCommand("ISSUE_CATEGORY", category, "나중에 폐기", "Retired later", null, null, 8, "INACTIVE"),
                TestOwners.OWNER);
        assertThat(s.bean(SaveService.class).save(Scenario.AUTHOR, s.edit("issue", i.id(), 1,
                Map.of("title", "new title"))).version()).isEqualTo(2);
        assertThat(s.issue(i.id()).category().value()).isEqualTo(category);
    }

    @Test
    @DisplayName("SVC-SAV-05: an edit bumps the version and the time and records only the changed fields")
    void sav05_edit(Scenario s) {
        SaveResult i = s.newIssue("i");
        s.ctx().clock().set(Instant.parse("2026-11-05T12:00:00Z"));
        var fields = new LinkedHashMap<String, String>();
        fields.put("title", "new title");
        fields.put("symptom", s.issue(i.id()).symptom());
        SaveResult edited = s.bean(SaveService.class).save(Scenario.AUTHOR, s.edit("issue", i.id(), 1, fields));
        assertThat(edited.version()).isEqualTo(2);
        assertThat(s.issue(i.id()).updatedAt()).isEqualTo(Instant.parse("2026-11-05T12:00:00Z"));
        assertThat(s.historyRow(edited.historyId()).op()).isEqualTo(HistoryOp.EDIT);
        assertThat(s.changes(edited.historyId()).entries()).containsExactly(Map.entry("title",
                new Change("i", "new title")));
    }

    @Test
    @DisplayName("SVC-SAV-06: a stale expected version names both versions")
    void sav06_versionConflict(Scenario s) {
        SaveResult i = s.newIssue("i");
        s.bean(SaveService.class).save(Scenario.AUTHOR, s.edit("issue", i.id(), 1, Map.of("title", "two")));
        LedgerException e = Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR, s.edit("issue",
                i.id(), 7, Map.of("title", "three"))), ConflictCode.VERSION_CONFLICT);
        assertThat(e.args()).containsExactly("issue", i.id(), "7", "2");
    }

    @Test
    @DisplayName("SVC-SAV-07: an edit needs an id")
    void sav07_idRequired(Scenario s) {
        Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR, s.edit("issue", null, 1,
                Map.of("title", "x"))), ValidationCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("SVC-SAV-07: an unknown id")
    void sav07_unknownId(Scenario s) {
        Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR, s.edit("issue", s.issueId(5), 1,
                Map.of("title", "x"))), LookupCode.NOT_FOUND);
    }

    @Test
    @DisplayName("SVC-SAV-07: the id of another kind of entity")
    void sav07_idOfAnotherEntity(Scenario s) {
        SaveResult i = s.newIssue("i");
        Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR, s.edit("task", i.id(), 1,
                Map.of("title", "x"))), LookupCode.NOT_FOUND);
    }

    @Test
    @DisplayName("SVC-SAV-08: an edit with identical values changes nothing and writes no history")
    void sav08_nothingChanges(Scenario s) {
        SaveResult i = s.newIssue("i");
        long history = s.count("SELECT COUNT(*) FROM history");
        Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR, s.edit("issue", i.id(), 1,
                Map.of("title", "i"))), TransitionCode.NOTHING_CHANGES);
        assertThat(s.count("SELECT COUNT(*) FROM history")).isEqualTo(history);
        assertThat(s.issue(i.id()).version()).isEqualTo(1);
    }

    @Test
    @DisplayName("SVC-SAV-09: an issue cannot move to another project")
    void sav09_projectMove(Scenario s) {
        SaveResult i = s.newIssue("i");
        Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR, s.edit("issue", i.id(), 1,
                Map.of("project_id", s.projectId()))), TransitionCode.PROJECT_MOVE_FORBIDDEN);
    }

    static Stream<String> keysOfAnotherEntity() {
        return Stream.of("purpose", "success_condition", "origin_ref", "origin_quote", "milestone_name", "goal_label",
                "code", "criterion_text", "summary", "acceptance_criteria", "priority", "parent_id");
    }

    @ParameterizedTest
    @MethodSource("keysOfAnotherEntity")
    @DisplayName("SVC-SAV-10: a key that belongs to another entity is refused and named")
    void sav10_keyOfAnotherEntity(String key, Scenario s) {
        SaveCommand command = new SaveCommand(Scenario.uuid(), "issue", null, 0L,
                Fields.of(with(s.issueFields("i"), key, "x")), Scenario.REASON);
        ValidationException e = (ValidationException) Expect.failure(
                () -> s.bean(SaveService.class).save(Scenario.AUTHOR, command), ValidationCode.VALIDATION_FAILED);
        assertThat(e.issues().get(0).path()).isEqualTo("fields." + key);
        assertThat(s.count("SELECT COUNT(*) FROM issue WHERE project_id = ?", s.projectId())).isZero();
    }

    @Test
    @DisplayName("SVC-SAV-10: a key of another entity in a batch is refused with fields.<key> and op_index")
    void sav10_unknownKeyInABatchText(Scenario s) {
        List<BatchOpInput> ops = List.of(new BatchOpInput.Save("issue",
                Fields.of(with(s.issueFields("t"), "purpose", "x")), Scenario.REASON, null, null));
        ValidationException e = (ValidationException) Expect.failure(
                () -> s.bean(BatchService.class).batch(Scenario.AUTHOR, Scenario.uuid(), ops),
                ValidationCode.VALIDATION_FAILED);
        assertThat(e.issues().get(0).path()).isEqualTo("fields.purpose");
        assertThat(e.details()).containsEntry("op_index", List.of("1")).containsEntry("op", List.of("save"));
        assertThat(s.count("SELECT COUNT(*) FROM issue WHERE project_id = ?", s.projectId())).isZero();
    }

    @Test
    @DisplayName("SVC-SAV-11: owner_actor_id must be an author id")
    void sav11_owner(Scenario s) {
        Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("issue", null,
                with(s.issueFields("i"), "owner_actor_id", "someone"))), TrustCode.AUTHOR_ID_FORMAT);
        SaveResult ok = s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("issue", null,
                with(s.issueFields("i"), "owner_actor_id", "codex:owner1")));
        assertThat(s.issue(ok.id()).ownerActorId().value()).isEqualTo("codex:owner1");
    }

    @Test
    @DisplayName("SVC-SAV-12: a task starts PLANNED at version 1, an edit moves updated_at")
    void sav12_task(Scenario s) {
        var fields = with(with(s.taskFields("t"), "owner_actor_id", "codex:owner1"), "origin_ref", "ref-1");
        SaveResult t = s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("task", null, fields));
        assertThat(s.task(t.id()).state()).isEqualTo(TaskState.PLANNED);
        assertThat(s.task(t.id()).version()).isEqualTo(1);
        assertThat(s.task(t.id()).originRef()).isEqualTo("ref-1");
        assertThat(s.task(t.id()).ownerActorId().value()).isEqualTo("codex:owner1");
        s.ctx().clock().set(Instant.parse("2026-11-05T13:00:00Z"));
        s.bean(SaveService.class).save(Scenario.AUTHOR, s.edit("task", t.id(), 1, Map.of("title", "t2")));
        assertThat(s.task(t.id()).updatedAt()).isEqualTo(Instant.parse("2026-11-05T13:00:00Z"));
    }

    @Test
    @DisplayName("SVC-SAV-13: a criterion starts UNKNOWN at version 1, an edit bumps the version only")
    void sav13_criterion(Scenario s) {
        SaveResult c = s.newCriterion("1", "r1", "c1");
        var criteria = s.bean(CriterionRepository.class);
        var row = criteria.findById(CriterionId.of(c.id())).orElseThrow();
        assertThat(row.state()).isEqualTo(CriterionState.UNKNOWN);
        assertThat(row.version()).isEqualTo(1);
        SaveResult edited = s.bean(SaveService.class).save(Scenario.AUTHOR, s.edit("criterion", c.id(), 1,
                Map.of("criterion_text", "changed")));
        assertThat(edited.version()).isEqualTo(2);
    }

    @Test
    @DisplayName("SVC-SAV-14: a criterion key (project, milestone_name, code) is unique")
    void sav14_duplicateCriterion(Scenario s) {
        s.newCriterion("1", "r1", "c1");
        Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("criterion",
                s.criterionId("2"), s.criterionFields("r1", "c1"))), ConflictCode.DUPLICATE_KEY);
    }

    @Test
    @DisplayName("SVC-SAV-15: an unknown project on create")
    void sav15_unknownProject(Scenario s) {
        Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("issue", null,
                with(s.issueFields("i"), "project_id", "nowhere"))), LookupCode.NOT_FOUND);
    }

    @ParameterizedTest
    @ValueSource(ints = {9, 1001})
    @DisplayName("SVC-SAV-16: a reason outside 10..1000 characters leaves no receipt")
    void sav16_reasonLength(int length, Scenario s) {
        String id = Scenario.uuid();
        Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR, new SaveCommand(id, "issue", null, 0L,
                Fields.of(s.issueFields("i")), "r".repeat(length))), ValidationCode.REASON_INVALID);
        assertThat(s.count("SELECT COUNT(*) FROM request_receipt WHERE operation_key = ?", id)).isZero();
    }

    record TooLong(String name, Consumer<Scenario> create) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<TooLong> tooLongFields() {
        return Stream.of(
                new TooLong("title", s -> create(s, "issue", with(s.issueFields("i"), "title", "t".repeat(301)))),
                new TooLong("symptom", s -> create(s, "issue",
                        with(s.issueFields("i"), "symptom", "b".repeat(4001)))),
                new TooLong("root_cause", s -> create(s, "issue",
                        with(s.issueFields("i"), "root_cause", "c".repeat(4001)))),
                new TooLong("summary", s -> create(s, "spec",
                        with(s.specFields("sp"), "summary", "s".repeat(4001)))),
                new TooLong("acceptance_criteria", s -> create(s, "spec",
                        with(s.specFields("sp"), "acceptance_criteria", "a".repeat(4001)))),
                new TooLong("priority", s -> create(s, "spec",
                        with(s.specFields("sp"), "priority", "p".repeat(33)))),
                new TooLong("purpose", s -> create(s, "task", with(s.taskFields("t"), "purpose", "p".repeat(4001)))),
                new TooLong("success_condition", s -> create(s, "task",
                        with(s.taskFields("t"), "success_condition", "s".repeat(4001)))),
                new TooLong("origin_quote", s -> create(s, "task",
                        with(s.taskFields("t"), "origin_quote", "q".repeat(2001)))),
                new TooLong("criterion_text", s -> create(s, "criterion",
                        with(s.criterionFields("r", "c"), "criterion_text", "x".repeat(4001)))),
                new TooLong("milestone_name", s -> create(s, "criterion",
                        with(s.criterionFields("r", "c"), "milestone_name", "r".repeat(101)))),
                new TooLong("goal_label", s -> create(s, "criterion",
                        with(s.criterionFields("r", "c"), "goal_label", "g".repeat(201)))));
    }

    private static void create(Scenario s, String entity, Map<String, String> fields) {
        s.bean(SaveService.class).save(Scenario.AUTHOR, s.create(entity,
                "criterion".equals(entity) ? s.criterionId("1") : null, fields));
    }

    @ParameterizedTest
    @MethodSource("tooLongFields")
    @DisplayName("SVC-SAV-17: a field over its limit is refused before any SQL write")
    void sav17_limits(TooLong tooLong, Scenario s) {
        s.ctx().spy().clear();
        Expect.failure(() -> tooLong.create().accept(s), ValidationCode.VALIDATION_FAILED);
        assertThat(s.ctx().spy().statements()).noneMatch(sql -> sql.stripLeading().toUpperCase().startsWith("INSERT"));
    }

    @Test
    @DisplayName("SVC-SAV-18: ids of different kinds of entity do not collide")
    void sav18_separateKinds(Scenario s) {
        SaveResult i = s.newIssue("i");
        SaveResult t = s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("task", i.id(), s.taskFields("t")));
        assertThat(t.id()).isEqualTo(i.id());
        assertThat(s.task(i.id()).title()).isEqualTo("t");
        assertThat(s.issue(i.id()).title()).isEqualTo("i");
    }

    @Test
    @DisplayName("SVC-SAV-19: an edit by another author keeps created_by and records the editor")
    void sav19_createdByIsKept(Scenario s) {
        SaveResult i = s.newIssue("i");
        SaveResult edited = s.bean(SaveService.class).save(Scenario.ISSUE_ONLY, s.edit("issue", i.id(), 1,
                Map.of("title", "by someone else")));
        assertThat(s.issue(i.id()).createdBy().value()).isEqualTo(Scenario.AUTHOR);
        assertThat(s.historyRow(edited.historyId()).authorId().value()).isEqualTo(Scenario.ISSUE_ONLY);
    }
}
