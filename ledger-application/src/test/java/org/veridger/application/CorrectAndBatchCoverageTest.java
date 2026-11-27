// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.veridger.application.support.Expect;
import org.veridger.application.support.Fields;
import org.veridger.application.support.LedgerServiceTest;
import org.veridger.application.support.Scenario;
import org.veridger.application.write.BatchOpInput;
import org.veridger.application.write.BatchResult;
import org.veridger.application.write.BatchService;
import org.veridger.application.write.CorrectCommand;
import org.veridger.application.write.CorrectService;
import org.veridger.application.write.LinkCommand;
import org.veridger.application.write.LinkService;
import org.veridger.application.write.RecordDeliverableCommand;
import org.veridger.application.write.RecordDeliverableService;
import org.veridger.application.write.SaveCommand;
import org.veridger.application.write.SaveService;
import org.veridger.application.write.TransitionCommand;
import org.veridger.application.write.TransitionService;
import org.veridger.application.write.WriteResult.LinkResult;
import org.veridger.application.write.WriteResult.RecordDeliverableResult;
import org.veridger.application.write.WriteResult.SaveResult;
import org.veridger.domain.error.LookupCode;
import org.veridger.domain.error.ValidationCode;

/**
 * Real-database coverage tests for {@code BatchLockPlanner} and {@code CorrectOperation} exercising all entity types,
 * link kinds, hierarchy/project moves, merged-issue corrections, beforeData/changes/fallback resolution, and batch
 * endpoint discovery.
 */
@LedgerServiceTest
class CorrectAndBatchCoverageTest {

    @Test
    @DisplayName("SVC-BAT-20 BatchLockPlanner covers Save/Transition/Deliverable/Case/Link/Correct across all entity types and link kinds")
    void batchLockPlannerAllOperationsAndLinkKinds(Scenario s) {
        SaveResult issueA = s.newIssue("batch-issue-a");
        SaveResult issueB = s.newIssue("batch-issue-b");
        SaveResult specA = s.newSpec("batch-spec-a");
        SaveResult specB = s.newSpec("batch-spec-b");
        SaveResult taskA = s.newTask("batch-task-a");
        SaveResult critA = s.newCriterion("B-1", "v1", "criterion-a");

        // Batch 1: Save creates with explicit IDs + updates (including spec hierarchy parent_id) + all 7 link kinds
        // + RecordCase + RecordDeliverable (issue/spec/task) + Correct (issue/spec/task/criterion)
        BatchResult r1 = s.bean(BatchService.class).batch(Scenario.AUTHOR, Scenario.uuid(), List.of(
                new BatchOpInput.Save("spec", Fields.of(s.specFields("created-spec-explicit")), Scenario.REASON,
                        s.specId(3), 0L),
                new BatchOpInput.Save("task", Fields.of(s.taskFields("created-task-explicit")), Scenario.REASON,
                        s.taskId(2), 0L),
                new BatchOpInput.Save("criterion", Fields.of(s.criterionFields("v1", "B-99")),
                        Scenario.REASON, "CRIT-BATCH-99", 0L),
                new BatchOpInput.Save("issue", Fields.of(Map.of("title", "updated-issue-a")), Scenario.REASON,
                        issueA.id(), 1L),
                new BatchOpInput.Save("spec", Fields.of(Map.of("title", "updated-spec-b", "parent_id", specA.id())),
                        Scenario.REASON, specB.id(), 1L),
                new BatchOpInput.Save("task", Fields.of(Map.of("title", "updated-task-a")), Scenario.REASON,
                        taskA.id(), 1L),
                new BatchOpInput.Save("criterion", Fields.of(Map.of("criterion_text", "updated-crit")), Scenario.REASON,
                        critA.id(), 1L),
                new BatchOpInput.RecordCase(issueA.id(), "obs in batch", Scenario.REASON, null, null, null, null),
                new BatchOpInput.RecordDeliverable("issue", issueA.id(), "code_change", Scenario.ref('1'),
                        Scenario.REASON, "org/repo", "issue deliv", null, null, null, null, null),
                new BatchOpInput.RecordDeliverable("spec", specA.id(), "document", Scenario.ref('2'),
                        Scenario.REASON, "org/repo", "spec deliv", null, null, null, null, null),
                new BatchOpInput.RecordDeliverable("task", taskA.id(), "code_change", Scenario.ref('3'),
                        Scenario.REASON, "org/repo", "task deliv", null, null, null, null, null),
                new BatchOpInput.Link("issue_task", issueA.id(), taskA.id(), Scenario.REASON, false),
                new BatchOpInput.Link("issue_spec", issueA.id(), specA.id(), Scenario.REASON, false),
                new BatchOpInput.Link("spec_task", specA.id(), taskA.id(), Scenario.REASON, false),
                new BatchOpInput.Link("criterion_task", critA.id(), taskA.id(), Scenario.REASON, false),
                new BatchOpInput.Link("criterion_issue", critA.id(), issueA.id(), Scenario.REASON, false),
                new BatchOpInput.Link("criterion_spec", critA.id(), specA.id(), Scenario.REASON, false),
                new BatchOpInput.Link("blocks", issueA.id(), issueB.id(), Scenario.REASON, false),
                new BatchOpInput.Correct(issueA.historyId(), Scenario.REASON, null),
                new BatchOpInput.Correct(specA.historyId(), Scenario.REASON, null),
                new BatchOpInput.Correct(taskA.historyId(), Scenario.REASON, null),
                new BatchOpInput.Correct(critA.historyId(), Scenario.REASON, null)));
        assertThat(r1.count()).isEqualTo(22);

        // Batch 2: Transitions across issue (MERGED with mergedInto), spec, task, criterion
        SaveResult issueMergeSrc = s.newIssue("merge-src");
        SaveResult issueMergeDst = s.newIssue("merge-dst");
        BatchResult r2 = s.bean(BatchService.class).batch(Scenario.AUTHOR, Scenario.uuid(), List.of(
                new BatchOpInput.Transition("issue", issueMergeSrc.id(), 1L, "MERGED", Scenario.REASON,
                        null, null, issueMergeDst.id(), null),
                new BatchOpInput.Transition("spec", specA.id(), s.spec(specA.id()).version(), "ACTIVE", Scenario.REASON,
                        null, null, null, null),
                new BatchOpInput.Transition("task", taskA.id(), s.task(taskA.id()).version(), "ACTIVE", Scenario.REASON,
                        null, null, null, null),
                new BatchOpInput.Transition("criterion", critA.id(), s.criterion(critA.id()).version(), "ACTIVE",
                        Scenario.REASON, null, null, null, null)));
        assertThat(r2.count()).isEqualTo(4);

        // Batch 3: Invalid entity/targetEntity/link kind hit BatchLockPlanner's defensive discovery/target guards
        Expect.failure(() -> s.bean(BatchService.class).batch(Scenario.AUTHOR, Scenario.uuid(), List.of(
                new BatchOpInput.Save("unknown_entity", Fields.of(Map.of("title", "x")), Scenario.REASON, null, 0L))),
                ValidationCode.VALIDATION_FAILED);
        Expect.failure(() -> s.bean(BatchService.class).batch(Scenario.AUTHOR, Scenario.uuid(), List.of(
                new BatchOpInput.Transition("unknown_entity", "X-1", 1L, "OPEN", Scenario.REASON,
                        null, null, null, null))),
                ValidationCode.VALIDATION_FAILED);
        Expect.failure(() -> s.bean(BatchService.class).batch(Scenario.AUTHOR, Scenario.uuid(), List.of(
                new BatchOpInput.RecordDeliverable("unknown_target", "X-1", "code_change", Scenario.ref('4'),
                        Scenario.REASON, "org/repo", "bad", null, null, null, null, null))),
                ValidationCode.VALIDATION_FAILED);
        Expect.failure(() -> s.bean(BatchService.class).batch(Scenario.AUTHOR, Scenario.uuid(), List.of(
                new BatchOpInput.Link("", issueA.id(), taskA.id(), Scenario.REASON, false))),
                ValidationCode.VALIDATION_FAILED);
        Expect.failure(() -> s.bean(BatchService.class).batch(Scenario.AUTHOR, Scenario.uuid(), List.of(
                new BatchOpInput.Correct(null, Scenario.REASON, null))),
                ValidationCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("SVC-COR-20 CorrectOperation covers merged issues, deliverables, spec_task & criterion_link beforeData/active/unlinked/fallback paths")
    void correctOperationCoversAllEntityAndCompositeLinkPaths(Scenario s) {
        SaveResult issueA = s.newIssue("cor-issue-a");
        SaveResult issueB = s.newIssue("cor-issue-b");
        SaveResult specA = s.newSpec("cor-spec-a");
        SaveResult taskA = s.newTask("cor-task-a");
        SaveResult critA = s.newCriterion("C-1", "v1", "cor-crit-a");

        CorrectService correctService = s.bean(CorrectService.class);
        LinkService linkService = s.bean(LinkService.class);
        RecordDeliverableService delivService = s.bean(RecordDeliverableService.class);

        // 1. Null history_id validation
        Expect.failure(() -> correctService.correct(Scenario.AUTHOR,
                new CorrectCommand(Scenario.uuid(), null, Scenario.REASON)), ValidationCode.VALIDATION_FAILED);

        // 2. MERGED issue transition correction (resolves both issueA and mergedInto=issueB)
        long mergeHistoryId = s.bean(TransitionService.class).transition(Scenario.AUTHOR, new TransitionCommand(
                Scenario.uuid(), "issue", issueA.id(), 1L, "MERGED", Scenario.REASON, null, null, issueB.id(), null))
                .historyId();
        assertThat(correctService.correct(Scenario.AUTHOR,
                new CorrectCommand(Scenario.uuid(), mergeHistoryId, Scenario.REASON)).corrects())
                .isEqualTo(mergeHistoryId);

        // 3. Deliverables for spec and task (changes path)
        RecordDeliverableResult specDeliv = delivService.recordDeliverable(Scenario.AUTHOR,
                new RecordDeliverableCommand(Scenario.uuid(), "spec", specA.id(), "document", Scenario.ref('a'),
                        Scenario.REASON, "org/repo", "spec doc", null, null, null, null, null));
        RecordDeliverableResult taskDeliv = delivService.recordDeliverable(Scenario.AUTHOR,
                new RecordDeliverableCommand(Scenario.uuid(), "task", taskA.id(), "code_change", Scenario.ref('b'),
                        Scenario.REASON, "org/repo", "task fix", null, null, null, null, null));
        assertThat(correctService.correct(Scenario.AUTHOR,
                new CorrectCommand(Scenario.uuid(), specDeliv.historyId(), Scenario.REASON)).corrects())
                .isEqualTo(specDeliv.historyId());
        assertThat(correctService.correct(Scenario.AUTHOR,
                new CorrectCommand(Scenario.uuid(), taskDeliv.historyId(), Scenario.REASON)).corrects())
                .isEqualTo(taskDeliv.historyId());

        // 4. spec_task link -> unlink -> correct BOTH unlink (beforeData path) and original link (second-pass unlinked path)
        LinkResult stLink = linkService.link(Scenario.AUTHOR,
                new LinkCommand(Scenario.uuid(), "spec_task", specA.id(), taskA.id(), Scenario.REASON, false));
        LinkResult stUnlink = linkService.link(Scenario.AUTHOR,
                new LinkCommand(Scenario.uuid(), "spec_task", specA.id(), taskA.id(), Scenario.REASON, true));
        assertThat(correctService.correct(Scenario.AUTHOR,
                new CorrectCommand(Scenario.uuid(), stUnlink.historyId(), Scenario.REASON)).corrects())
                .isEqualTo(stUnlink.historyId());
        assertThat(correctService.correct(Scenario.AUTHOR,
                new CorrectCommand(Scenario.uuid(), stLink.historyId(), Scenario.REASON)).corrects())
                .isEqualTo(stLink.historyId());

        // 5. criterion_link for all 3 target kinds: task, issue, spec (active link, unlink beforeData, and unlinked second-pass)
        for (String[] pair : List.of(
                new String[] { "criterion_task", taskA.id() },
                new String[] { "criterion_issue", issueB.id() },
                new String[] { "criterion_spec", specA.id() })) {
            LinkResult linked = linkService.link(Scenario.AUTHOR,
                    new LinkCommand(Scenario.uuid(), pair[0], critA.id(), pair[1], Scenario.REASON, false));
            // Active link path (first pass)
            assertThat(correctService.correct(Scenario.AUTHOR,
                    new CorrectCommand(Scenario.uuid(), linked.historyId(), Scenario.REASON)).corrects())
                    .isEqualTo(linked.historyId());
            // Unlink (beforeData path)
            LinkResult unlinked = linkService.link(Scenario.AUTHOR,
                    new LinkCommand(Scenario.uuid(), pair[0], critA.id(), pair[1], Scenario.REASON, true));
            assertThat(correctService.correct(Scenario.AUTHOR,
                    new CorrectCommand(Scenario.uuid(), unlinked.historyId(), Scenario.REASON)).corrects())
                    .isEqualTo(unlinked.historyId());
            // Re-correct original link now that active link row is gone (second pass existing-entity match)
            assertThat(correctService.correct(Scenario.AUTHOR,
                    new CorrectCommand(Scenario.uuid(), linked.historyId(), Scenario.REASON)).corrects())
                    .isEqualTo(linked.historyId());
        }

        // 6. Legacy/imported history rows (no before_data, no changes) hitting entityId fallback & malformed paths
        long delivEntitySpec = insertLegacyHistory(s, "DELIVERABLE", "spec:" + specA.id() + "@ref1");
        assertThat(correctService.correct(Scenario.AUTHOR,
                new CorrectCommand(Scenario.uuid(), delivEntitySpec, Scenario.REASON)).corrects())
                .isEqualTo(delivEntitySpec);

        long delivEntityPlainIssue = insertLegacyHistory(s, "DELIVERABLE", issueB.id() + "@ref2");
        assertThat(correctService.correct(Scenario.AUTHOR,
                new CorrectCommand(Scenario.uuid(), delivEntityPlainIssue, Scenario.REASON)).corrects())
                .isEqualTo(delivEntityPlainIssue);

        long critAnyIssue = insertLegacyHistory(s, "CRITERION_LINK", critA.id() + "|" + issueB.id());
        assertThat(correctService.correct(Scenario.AUTHOR,
                new CorrectCommand(Scenario.uuid(), critAnyIssue, Scenario.REASON)).corrects())
                .isEqualTo(critAnyIssue);

        long critAnySpec = insertLegacyHistory(s, "CRITERION_LINK", critA.id() + "|" + specA.id());
        assertThat(correctService.correct(Scenario.AUTHOR,
                new CorrectCommand(Scenario.uuid(), critAnySpec, Scenario.REASON)).corrects())
                .isEqualTo(critAnySpec);

        long critAnyTask = insertLegacyHistory(s, "CRITERION_LINK", critA.id() + "|" + taskA.id());
        assertThat(correctService.correct(Scenario.AUTHOR,
                new CorrectCommand(Scenario.uuid(), critAnyTask, Scenario.REASON)).corrects())
                .isEqualTo(critAnyTask);

        // Malformed composite entity_ids return VALIDATION_FAILED / NOT_FOUND cleanly without crashing
        long badIssueTask = insertLegacyHistory(s, "ISSUE_TASK", "no-pipe");
        long badIssueSpec = insertLegacyHistory(s, "ISSUE_SPEC", "no-pipe");
        long badSpecTask = insertLegacyHistory(s, "SPEC_TASK", "no-pipe");
        long badRelation = insertLegacyHistory(s, "ISSUE_RELATION", "no-pipe");
        long badCritLink = insertLegacyHistory(s, "CRITERION_LINK", "no-pipe");
        long missingSpecTask = insertLegacyHistory(s, "SPEC_TASK", "ZZZ-S-9999|ZZZ-T-9999");
        long missingCritLink = insertLegacyHistory(s, "CRITERION_LINK", critA.id() + "|MISSING-T-9999");

        Expect.failure(() -> correctService.correct(Scenario.AUTHOR,
                new CorrectCommand(Scenario.uuid(), badIssueTask, Scenario.REASON)), ValidationCode.VALIDATION_FAILED);
        Expect.failure(() -> correctService.correct(Scenario.AUTHOR,
                new CorrectCommand(Scenario.uuid(), badIssueSpec, Scenario.REASON)), ValidationCode.VALIDATION_FAILED);
        Expect.failure(() -> correctService.correct(Scenario.AUTHOR,
                new CorrectCommand(Scenario.uuid(), badSpecTask, Scenario.REASON)), ValidationCode.VALIDATION_FAILED);
        Expect.failure(() -> correctService.correct(Scenario.AUTHOR,
                new CorrectCommand(Scenario.uuid(), badRelation, Scenario.REASON)), ValidationCode.VALIDATION_FAILED);
        Expect.failure(() -> correctService.correct(Scenario.AUTHOR,
                new CorrectCommand(Scenario.uuid(), badCritLink, Scenario.REASON)), ValidationCode.VALIDATION_FAILED);
        Expect.failure(() -> correctService.correct(Scenario.AUTHOR,
                new CorrectCommand(Scenario.uuid(), missingSpecTask, Scenario.REASON)), LookupCode.NOT_FOUND);
        Expect.failure(() -> correctService.correct(Scenario.AUTHOR,
                new CorrectCommand(Scenario.uuid(), missingCritLink, Scenario.REASON)), LookupCode.NOT_FOUND);
    }

    private static long insertLegacyHistory(Scenario s, String entity, String entityId) {
        long nextId = s.bean(org.veridger.database.repository.IdAllocator.class)
                .next(org.veridger.database.repository.Counter.HISTORY);
        s.ctx().jdbc().sql("""
                INSERT INTO history (id, occurred_at, author_id, login, actor_assurance, entity, entity_id, op, changes, reason, operation_key)
                VALUES (?, CURRENT_TIMESTAMP, ?, 'ledger_app', 'DECLARED', ?, ?, 'LINK', '{}', 'legacy test row', ?)
                """)
                .param(nextId)
                .param(Scenario.AUTHOR)
                .param(entity)
                .param(entityId)
                .param(Scenario.uuid())
                .update();
        return nextId;
    }
}
