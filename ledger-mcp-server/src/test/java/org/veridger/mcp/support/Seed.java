// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.mcp.support;

import org.veridger.mcp.tool.WriteOutputs;

/**
 * Seeded baseline entities for parameterized read and write contract tests: an OPEN issue, a PLANNED spec, a PLANNED
 * task, an UNKNOWN criterion, a RESOLVED issue (version 2), and a VERIFIED issue (with its verification history row).
 */
public record Seed(String project, String issue, long issueHistoryId, String spec, String task, String criterion,
        String resolved, String verified, long verificationRow) {

    public static final String DELIVERABLE_REF = "rev-seed-0001";

    public static Seed create(McpSession session, Scenario scenario) {
        String project = scenario.projectId();
        WriteOutputs.Saved issue = session.ok("ledger_issue_save",
                Calls.IssueSave.create(project, "open issue", Scenario.AUTHOR),
                WriteOutputs.Saved.class);
        WriteOutputs.Saved spec = session.ok("ledger_spec_save",
                Calls.SpecSave.create(project, "seed spec", Scenario.AUTHOR),
                WriteOutputs.Saved.class);
        WriteOutputs.Saved task = session.ok("ledger_task_save",
                Calls.TaskSave.create(project, "seed task", Scenario.AUTHOR),
                WriteOutputs.Saved.class);
        String criterionId = scenario.criterionId("SEED-01");
        WriteOutputs.Saved criterion = session.ok("ledger_criterion_save",
                Calls.CriterionSave.create(project, criterionId, "M1", "C01", Scenario.AUTHOR),
                WriteOutputs.Saved.class);
        String resolved = resolvedIssue(session, project, "resolved issue", DELIVERABLE_REF);
        String verified = resolvedIssue(session, project, "verified issue", "rev-seed-0002");
        WriteOutputs.Transitioned verification = session.ok("ledger_review",
                Calls.Review.of("issue", verified, 2, "VERIFIED", Scenario.REVIEWER),
                WriteOutputs.Transitioned.class);
        return new Seed(project, issue.id(), issue.historyId(), spec.id(), task.id(), criterion.id(), resolved,
                verified, verification.historyId());
    }

    private static String resolvedIssue(McpSession session, String project, String title, String deliverableRef) {
        WriteOutputs.Saved created = session.ok("ledger_issue_save",
                Calls.IssueSave.create(project, title, Scenario.AUTHOR),
                WriteOutputs.Saved.class);
        session.ok("ledger_record_deliverable",
                Calls.RecordDeliverable.ofIssue(created.id(), deliverableRef, Scenario.AUTHOR),
                WriteOutputs.DeliverableRecorded.class);
        return created.id();
    }
}
