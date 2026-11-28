// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.mcp.tool;

import java.util.List;
import org.veridger.application.write.BatchOpInput;
import org.veridger.application.write.BatchService;
import org.veridger.application.write.CorrectCommand;
import org.veridger.application.write.CorrectService;
import org.veridger.application.write.FieldsInput;
import org.veridger.application.write.LinkCommand;
import org.veridger.application.write.LinkService;
import org.veridger.application.write.RecordCaseCommand;
import org.veridger.application.write.RecordCaseService;
import org.veridger.application.write.RecordDeliverableCommand;
import org.veridger.application.write.RecordDeliverableService;
import org.veridger.application.write.ReviewService;
import org.veridger.application.write.SaveCommand;
import org.veridger.application.write.SaveService;
import org.veridger.application.write.TransitionCommand;
import org.veridger.application.write.TransitionService;
import org.veridger.application.write.WhoamiService;
import org.veridger.domain.rule.Channel;
import org.veridger.domain.rule.CheckResult;
import org.veridger.domain.rule.CodeGroup;
import org.veridger.domain.rule.CriterionState;
import org.veridger.domain.rule.DeliverableTarget;
import org.veridger.domain.rule.HistoryEntity;
import org.veridger.domain.rule.IssueStatus;
import org.veridger.domain.rule.SpecStatus;
import org.veridger.domain.rule.TaskState;
import org.veridger.domain.value.Patch;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * The thirteen write tools of the 2.0 universal ledger. Top-level creation and editing use dedicated flat tools per
 * entity ({@code ledger_issue_save}, {@code ledger_spec_save}, {@code ledger_task_save}, {@code ledger_criterion_save})
 * with explicit validated parameters rather than a polymorphic map or raw query.
 *
 * <p>Every write tool except {@code ledger_whoami} requires an {@code operationKey} chosen by the caller for
 * idempotent replay. Parameter descriptions are message keys resolved by {@code ToolCatalog}.
 */
@Component
public class WriteTools implements ToolAdapter {

    private final SaveService save;
    private final TransitionService transition;
    private final RecordDeliverableService recordDeliverable;
    private final RecordCaseService recordCase;
    private final LinkService link;
    private final CorrectService correct;
    private final BatchService batch;
    private final ReviewService review;
    private final WhoamiService whoami;

    public WriteTools(SaveService save, TransitionService transition, RecordDeliverableService recordDeliverable,
            RecordCaseService recordCase, LinkService link, CorrectService correct, BatchService batch,
            ReviewService review, WhoamiService whoami) {
        this.save = save;
        this.transition = transition;
        this.recordDeliverable = recordDeliverable;
        this.recordCase = recordCase;
        this.link = link;
        this.correct = correct;
        this.batch = batch;
        this.review = review;
        this.whoami = whoami;
    }

    private static Patch<String> patch(String value) {
        return value == null ? Patch.absent() : Patch.set(value);
    }

    @McpTool(name = "ledger_issue_save", description = "tool.ledger_issue_save", generateOutputSchema = true)
    public WriteOutputs.Saved saveIssue(
            @McpToolParam(description = "field.project_id", required = false) String project_id,
            @McpToolParam(description = "field.title", required = false) String title,
            @McpToolParam(description = "field.category", required = false)
            @CommonCode(group = CodeGroup.ISSUE_CATEGORY) String category,
            @McpToolParam(description = "field.severity", required = false)
            @CommonCode(group = CodeGroup.ISSUE_SEVERITY) String severity,
            @McpToolParam(description = "field.symptom", required = false) String symptom,
            @McpToolParam(description = "field.root_cause", required = false) String root_cause,
            @McpToolParam(description = "field.owner_actor_id", required = false) String owner_actor_id,
            @McpToolParam(description = "param.issue_save_id", required = false) String id,
            @McpToolParam(description = "param.save_expected_version", required = false) Long expected_version,
            @McpToolParam(description = "param.reason", required = true) String reason,
            @McpToolParam(description = "param.operation_key", required = true) String operationKey,
            @McpToolParam(description = "param.host_attestation", required = false) String _hostAttestation) {
        FieldsInput fields = new FieldsInput(patch(project_id), Patch.absent(), patch(title), patch(category),
                patch(severity), patch(symptom), patch(root_cause), Patch.absent(), Patch.absent(), Patch.absent(),
                patch(owner_actor_id), Patch.absent(), Patch.absent(), Patch.absent(), Patch.absent(), Patch.absent(),
                Patch.absent(), Patch.absent(), Patch.absent());
        return WriteOutputs.saved(save.save(_hostAttestation,
                new SaveCommand(operationKey, HistoryEntity.ISSUE.token(), id, expected_version, fields, reason)));
    }

    @McpTool(name = "ledger_spec_save", description = "tool.ledger_spec_save", generateOutputSchema = true)
    public WriteOutputs.Saved saveSpec(
            @McpToolParam(description = "field.project_id", required = false) String project_id,
            @McpToolParam(description = "field.parent_id", required = false) String parent_id,
            @McpToolParam(description = "field.title", required = false) String title,
            @McpToolParam(description = "field.summary", required = false) String summary,
            @McpToolParam(description = "field.acceptance_criteria", required = false) String acceptance_criteria,
            @McpToolParam(description = "field.priority", required = false) String priority,
            @McpToolParam(description = "field.owner_actor_id", required = false) String owner_actor_id,
            @McpToolParam(description = "field.origin_ref", required = false) String origin_ref,
            @McpToolParam(description = "field.origin_quote", required = false) String origin_quote,
            @McpToolParam(description = "param.spec_save_id", required = false) String id,
            @McpToolParam(description = "param.save_expected_version", required = false) Long expected_version,
            @McpToolParam(description = "param.reason", required = true) String reason,
            @McpToolParam(description = "param.operation_key", required = true) String operationKey,
            @McpToolParam(description = "param.host_attestation", required = false) String _hostAttestation) {
        FieldsInput fields = new FieldsInput(patch(project_id), patch(parent_id), patch(title), Patch.absent(),
                Patch.absent(), Patch.absent(), Patch.absent(), patch(summary), patch(acceptance_criteria),
                patch(priority), patch(owner_actor_id), Patch.absent(), Patch.absent(), patch(origin_ref),
                patch(origin_quote), Patch.absent(), Patch.absent(), Patch.absent(), Patch.absent());
        return WriteOutputs.saved(save.save(_hostAttestation,
                new SaveCommand(operationKey, HistoryEntity.SPEC.token(), id, expected_version, fields, reason)));
    }

    @McpTool(name = "ledger_task_save", description = "tool.ledger_task_save", generateOutputSchema = true)
    public WriteOutputs.Saved saveTask(
            @McpToolParam(description = "field.project_id", required = false) String project_id,
            @McpToolParam(description = "field.title", required = false) String title,
            @McpToolParam(description = "field.purpose", required = false) String purpose,
            @McpToolParam(description = "field.success_condition", required = false) String success_condition,
            @McpToolParam(description = "field.owner_actor_id", required = false) String owner_actor_id,
            @McpToolParam(description = "field.origin_ref", required = false) String origin_ref,
            @McpToolParam(description = "field.origin_quote", required = false) String origin_quote,
            @McpToolParam(description = "param.task_save_id", required = false) String id,
            @McpToolParam(description = "param.save_expected_version", required = false) Long expected_version,
            @McpToolParam(description = "param.reason", required = true) String reason,
            @McpToolParam(description = "param.operation_key", required = true) String operationKey,
            @McpToolParam(description = "param.host_attestation", required = false) String _hostAttestation) {
        FieldsInput fields = new FieldsInput(patch(project_id), Patch.absent(), patch(title), Patch.absent(),
                Patch.absent(), Patch.absent(), Patch.absent(), Patch.absent(), Patch.absent(), Patch.absent(),
                patch(owner_actor_id), patch(purpose), patch(success_condition), patch(origin_ref),
                patch(origin_quote), Patch.absent(), Patch.absent(), Patch.absent(), Patch.absent());
        return WriteOutputs.saved(save.save(_hostAttestation,
                new SaveCommand(operationKey, HistoryEntity.TASK.token(), id, expected_version, fields, reason)));
    }

    @McpTool(name = "ledger_criterion_save", description = "tool.ledger_criterion_save", generateOutputSchema = true)
    public WriteOutputs.Saved saveCriterion(
            @McpToolParam(description = "field.project_id", required = false) String project_id,
            @McpToolParam(description = "param.criterion_save_id", required = true) String id,
            @McpToolParam(description = "field.milestone_name", required = false) String milestone_name,
            @McpToolParam(description = "field.goal_label", required = false) String goal_label,
            @McpToolParam(description = "field.code", required = false) String code,
            @McpToolParam(description = "field.criterion_text", required = false) String criterion_text,
            @McpToolParam(description = "param.save_expected_version", required = false) Long expected_version,
            @McpToolParam(description = "param.reason", required = true) String reason,
            @McpToolParam(description = "param.operation_key", required = true) String operationKey,
            @McpToolParam(description = "param.host_attestation", required = false) String _hostAttestation) {
        FieldsInput fields = new FieldsInput(patch(project_id), Patch.absent(), Patch.absent(), Patch.absent(),
                Patch.absent(), Patch.absent(), Patch.absent(), Patch.absent(), Patch.absent(), Patch.absent(),
                Patch.absent(), Patch.absent(), Patch.absent(), Patch.absent(), Patch.absent(),
                patch(milestone_name), patch(goal_label), patch(code), patch(criterion_text));
        return WriteOutputs.saved(save.save(_hostAttestation,
                new SaveCommand(operationKey, HistoryEntity.CRITERION.token(), id, expected_version, fields, reason)));
    }

    @McpTool(name = "ledger_transition", description = "tool.ledger_transition", generateOutputSchema = true)
    public WriteOutputs.Transitioned transition(
            @McpToolParam(description = "param.entity", required = true)
            @Choice(from = HistoryEntity.class, only = {"ISSUE", "SPEC", "TASK", "CRITERION"}) String entity,
            @McpToolParam(description = "param.id", required = true) String id,
            @McpToolParam(description = "param.expected_version", required = true) Long expected_version,
            @McpToolParam(description = "param.transition_to", required = true)
            @Choice(from = {IssueStatus.class, SpecStatus.class, TaskState.class, CriterionState.class},
                    verification = Choice.Verification.EXCLUDED) String to,
            @McpToolParam(description = "param.reason", required = true) String reason,
            @McpToolParam(description = "param.evidence_uri", required = false) String evidence_uri,
            @McpToolParam(description = "param.evidence_sha256", required = false) String evidence_sha256,
            @McpToolParam(description = "param.merged_into", required = false) String merged_into,
            @McpToolParam(description = "param.attachment_id", required = false) String attachment_id,
            @McpToolParam(description = "param.operation_key", required = true) String operationKey,
            @McpToolParam(description = "param.host_attestation", required = false) String _hostAttestation) {
        return WriteOutputs.transitioned(transition.transition(_hostAttestation,
                new TransitionCommand(operationKey, entity, id, expected_version, to, reason, evidence_uri,
                        evidence_sha256, merged_into, attachment_id)));
    }

    @McpTool(name = "ledger_record_deliverable", description = "tool.ledger_record_deliverable",
            generateOutputSchema = true)
    public WriteOutputs.DeliverableRecorded recordDeliverable(
            @McpToolParam(description = "param.target_entity", required = true)
            @Choice(from = DeliverableTarget.class) String target_entity,
            @McpToolParam(description = "param.target_id", required = true) String target_id,
            @McpToolParam(description = "param.deliverable_kind", required = true)
            @CommonCode(group = CodeGroup.DELIVERABLE_KIND) String kind,
            @McpToolParam(description = "param.deliverable_ref", required = true) String deliverable_ref,
            @McpToolParam(description = "param.deliverable_summary", required = true) String summary,
            @McpToolParam(description = "param.reason", required = true) String reason,
            @McpToolParam(description = "param.deliverable_location", required = false) String location,
            @McpToolParam(description = "param.check_ref", required = false) String check_ref,
            @McpToolParam(description = "param.check_result", required = false)
            @Choice(from = CheckResult.class) String check_result,
            @McpToolParam(description = "param.checked_at", required = false) String checked_at,
            @McpToolParam(description = "param.mark_done", required = false) Boolean mark_done,
            @McpToolParam(description = "param.attachment_id", required = false) String attachment_id,
            @McpToolParam(description = "param.operation_key", required = true) String operationKey,
            @McpToolParam(description = "param.host_attestation", required = false) String _hostAttestation) {
        return WriteOutputs.deliverableRecorded(recordDeliverable.recordDeliverable(_hostAttestation,
                new RecordDeliverableCommand(operationKey, target_entity, target_id, kind, deliverable_ref, reason,
                        location, summary, check_ref, check_result, checked_at, mark_done, attachment_id)));
    }

    @McpTool(name = "ledger_record_case", description = "tool.ledger_record_case", generateOutputSchema = true)
    public WriteOutputs.CaseRecorded recordCase(
            @McpToolParam(description = "param.issue_id", required = true) String issue_id,
            @McpToolParam(description = "param.observation", required = true) String observation,
            @McpToolParam(description = "param.case_reason", required = true) String reason,
            @McpToolParam(description = "param.case_evidence", required = false) String evidence,
            @McpToolParam(description = "param.source_quote", required = false) String source_quote,
            @McpToolParam(description = "param.observed_at", required = false) String observed_at,
            @McpToolParam(description = "param.attachment_id", required = false) String attachment_id,
            @McpToolParam(description = "param.operation_key", required = true) String operationKey,
            @McpToolParam(description = "param.host_attestation", required = false) String _hostAttestation) {
        return WriteOutputs.caseRecorded(recordCase.recordCase(_hostAttestation,
                new RecordCaseCommand(operationKey, issue_id, observation, reason, evidence, source_quote,
                        observed_at, attachment_id)));
    }

    @McpTool(name = "ledger_link", description = "tool.ledger_link", generateOutputSchema = true)
    public WriteOutputs.Linked link(
            @McpToolParam(description = "param.kind", required = true)
            @CommonCode(group = CodeGroup.RELATION_KIND,
                    fixed = {"issue_task", "issue_spec", "spec_task", "criterion_task", "criterion_issue",
                        "criterion_spec"}) String kind,
            @McpToolParam(description = "param.link_a", required = true) String a,
            @McpToolParam(description = "param.link_b", required = true) String b,
            @McpToolParam(description = "param.reason", required = true) String reason,
            @McpToolParam(description = "param.remove", required = false) Boolean remove,
            @McpToolParam(description = "param.operation_key", required = true) String operationKey,
            @McpToolParam(description = "param.host_attestation", required = false) String _hostAttestation) {
        return WriteOutputs.linked(link.link(_hostAttestation,
                new LinkCommand(operationKey, kind, a, b, reason, remove)));
    }

    @McpTool(name = "ledger_correct", description = "tool.ledger_correct", generateOutputSchema = true)
    public WriteOutputs.Corrected correct(
            @McpToolParam(description = "param.history_id", required = true) Long history_id,
            @McpToolParam(description = "param.correct_reason", required = true) String reason,
            @McpToolParam(description = "param.attachment_id", required = false) String attachment_id,
            @McpToolParam(description = "param.operation_key", required = true) String operationKey,
            @McpToolParam(description = "param.host_attestation", required = false) String _hostAttestation) {
        return WriteOutputs.corrected(correct.correct(_hostAttestation,
                new CorrectCommand(operationKey, history_id, reason, attachment_id)));
    }

    @McpTool(name = "ledger_batch", description = "tool.ledger_batch", generateOutputSchema = true)
    public WriteOutputs.Batched batch(
            @McpToolParam(description = "param.ops", required = true) List<BatchOp> ops,
            @McpToolParam(description = "param.batch_default_reason", required = false) String default_reason,
            @McpToolParam(description = "param.batch_operation_key", required = true) String operationKey,
            @McpToolParam(description = "param.host_attestation", required = false) String _hostAttestation) {
        List<BatchOpInput> inputs = ops.stream().map(op -> op == null ? null : op.toInput(default_reason)).toList();
        return WriteOutputs.of(batch.batch(_hostAttestation, operationKey, inputs));
    }

    @McpTool(name = "ledger_review", description = "tool.ledger_review", generateOutputSchema = true)
    public WriteOutputs.Transitioned review(
            @McpToolParam(description = "param.review_entity", required = true)
            @Choice(from = HistoryEntity.class, only = {"ISSUE", "SPEC", "TASK", "CRITERION"}) String entity,
            @McpToolParam(description = "param.id", required = true) String id,
            @McpToolParam(description = "param.expected_version", required = true) Long expected_version,
            @McpToolParam(description = "param.review_to", required = true)
            @Choice(from = {IssueStatus.class, SpecStatus.class, TaskState.class, CriterionState.class}) String to,
            @McpToolParam(description = "param.reason", required = true) String reason,
            @McpToolParam(description = "param.review_evidence_uri", required = true) String evidence_uri,
            @McpToolParam(description = "param.evidence_sha256", required = false) String evidence_sha256,
            @McpToolParam(description = "param.attachment_id", required = false) String attachment_id,
            @McpToolParam(description = "param.operation_key", required = true) String operationKey,
            @McpToolParam(description = "param.host_attestation", required = false) String _hostAttestation) {
        return WriteOutputs.transitioned(review.review(_hostAttestation,
                new TransitionCommand(operationKey, entity, id, expected_version, to, reason, evidence_uri,
                        evidence_sha256, null, attachment_id)));
    }

    @McpTool(name = "ledger_review_correct", description = "tool.ledger_review_correct",
            generateOutputSchema = true)
    public WriteOutputs.Corrected reviewCorrect(
            @McpToolParam(description = "param.history_id", required = true) Long history_id,
            @McpToolParam(description = "param.correct_reason", required = true) String reason,
            @McpToolParam(description = "param.attachment_id", required = false) String attachment_id,
            @McpToolParam(description = "param.operation_key", required = true) String operationKey,
            @McpToolParam(description = "param.host_attestation", required = false) String _hostAttestation) {
        return WriteOutputs.corrected(review.reviewCorrect(_hostAttestation,
                new CorrectCommand(operationKey, history_id, reason, attachment_id)));
    }

    @McpTool(name = "ledger_whoami", description = "tool.ledger_whoami", generateOutputSchema = true)
    public WriteOutputs.Whoami whoami(
            @McpToolParam(description = "param.whoami_project", required = false) String project,
            @McpToolParam(description = "param.host_attestation", required = false) String _hostAttestation) {
        return WriteOutputs.of(whoami.whoami(_hostAttestation, Channel.AUTHOR, project));
    }
}
