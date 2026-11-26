// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.mcp.support;

import java.util.List;
import java.util.function.BiFunction;
import org.veridger.mcp.support.Calls.Fields;
import org.veridger.mcp.tool.WriteOutputs;

/**
 * One write tool with a valid call against {@link Seed} rows when attested.
 */
public record WriteCase(String tool, Class<?> output, String actor, BiFunction<Seed, String, Object> arguments) {

    @Override
    public String toString() {
        return tool;
    }

    public Object call(Seed seed) {
        return arguments.apply(seed, actor);
    }

    public Object callWithout(Seed seed) {
        return arguments.apply(seed, null);
    }

    /** The thirteen write tools of the 2.0 contract. */
    public static List<WriteCase> all() {
        return List.of(
                new WriteCase("ledger_issue_save", WriteOutputs.Saved.class, Scenario.AUTHOR,
                        (seed, actor) -> Calls.IssueSave.create(seed.project(), "another issue", actor)),
                new WriteCase("ledger_spec_save", WriteOutputs.Saved.class, Scenario.AUTHOR,
                        (seed, actor) -> Calls.SpecSave.create(seed.project(), "another spec", actor)),
                new WriteCase("ledger_task_save", WriteOutputs.Saved.class, Scenario.AUTHOR,
                        (seed, actor) -> Calls.TaskSave.create(seed.project(), "another task", actor)),
                new WriteCase("ledger_criterion_save", WriteOutputs.Saved.class, Scenario.AUTHOR,
                        (seed, actor) -> Calls.CriterionSave.create(seed.project(), seed.project() + "-CRIT-CASE",
                                "M1", "CODE-CASE", actor)),
                new WriteCase("ledger_transition", WriteOutputs.Transitioned.class, Scenario.AUTHOR,
                        (seed, actor) -> Calls.Transition.of("issue", seed.issue(), 1, "DISPUTED", actor)
                                .withEvidence(Scenario.EVIDENCE)),
                new WriteCase("ledger_record_deliverable", WriteOutputs.DeliverableRecorded.class, Scenario.AUTHOR,
                        (seed, actor) -> Calls.RecordDeliverable.ofIssue(seed.issue(), "rev-case-1", actor)),
                new WriteCase("ledger_record_case", WriteOutputs.CaseRecorded.class, Scenario.AUTHOR,
                        (seed, actor) -> Calls.RecordCase.of(seed.issue(), "seen again", actor)),
                new WriteCase("ledger_link", WriteOutputs.Linked.class, Scenario.AUTHOR,
                        (seed, actor) -> Calls.Link.of("issue_task", seed.issue(), seed.task(), actor)),
                new WriteCase("ledger_correct", WriteOutputs.Corrected.class, Scenario.AUTHOR,
                        (seed, actor) -> Calls.Correct.of(seed.issueHistoryId(), actor)),
                new WriteCase("ledger_batch", BatchResults.class, Scenario.AUTHOR,
                        (seed, actor) -> Calls.Batch.of(actor,
                                Calls.Op.save("issue", Fields.issue(seed.project(), "batched issue")))),
                new WriteCase("ledger_review", WriteOutputs.Transitioned.class, Scenario.REVIEWER,
                        (seed, actor) -> Calls.Review.of("issue", seed.resolved(), 2, "VERIFIED", actor)),
                new WriteCase("ledger_review_correct", WriteOutputs.Corrected.class, Scenario.REVIEWER,
                        (seed, actor) -> Calls.Correct.of(seed.verificationRow(), actor)),
                new WriteCase("ledger_whoami", WriteOutputs.Whoami.class, Scenario.AUTHOR,
                        (seed, actor) -> new Calls.Whoami(actor)));
    }

    /** The twelve write tools that carry a required {@code operationKey} (all except {@code ledger_whoami}). */
    public static List<WriteCase> keyed() {
        return all().stream().filter(write -> !"ledger_whoami".equals(write.tool())).toList();
    }
}
