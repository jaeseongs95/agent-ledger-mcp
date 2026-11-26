// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.mcp.tool;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.swagger.v3.oas.annotations.media.Schema;
import org.veridger.application.json.PatchText;
import org.veridger.application.write.FieldsInput;
import org.veridger.domain.rule.CodeGroup;
import org.veridger.domain.value.Patch;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * Editable {@code fields} record of a {@code save} operation inside {@code ledger_batch}: covers issues,
 * specifications, tasks, and milestone criteria (the entity kind determines which keys apply). Top-level saves use the
 * flat entity-specific tools ({@code ledger_issue_save}, {@code ledger_spec_save}, {@code ledger_task_save},
 * {@code ledger_criterion_save}).
 *
 * <p>Every component is a {@link Patch} so omitted or JSON {@code null} leaves the field untouched while an empty
 * string clears an optional field.
 */
public record SaveFields(
        @JsonProperty("project_id") @JsonPropertyDescription("field.project_id") @Schema(implementation = String.class)
        @JsonDeserialize(using = PatchText.class) Patch<String> projectId,
        @JsonProperty("parent_id") @JsonPropertyDescription("field.parent_id") @Schema(implementation = String.class)
        @JsonDeserialize(using = PatchText.class) Patch<String> parentId,
        @JsonProperty("title") @JsonPropertyDescription("field.title") @Schema(implementation = String.class)
        @JsonDeserialize(using = PatchText.class) Patch<String> title,
        @JsonProperty("category") @JsonPropertyDescription("field.category") @Schema(implementation = String.class)
        @JsonDeserialize(using = PatchText.class) @CommonCode(group = CodeGroup.ISSUE_CATEGORY) Patch<String> category,
        @JsonProperty("severity") @JsonPropertyDescription("field.severity") @Schema(implementation = String.class)
        @JsonDeserialize(using = PatchText.class) @CommonCode(group = CodeGroup.ISSUE_SEVERITY) Patch<String> severity,
        @JsonProperty("symptom") @JsonPropertyDescription("field.symptom") @Schema(implementation = String.class)
        @JsonDeserialize(using = PatchText.class) Patch<String> symptom,
        @JsonProperty("root_cause") @JsonPropertyDescription("field.root_cause") @Schema(implementation = String.class)
        @JsonDeserialize(using = PatchText.class) Patch<String> rootCause,
        @JsonProperty("summary") @JsonPropertyDescription("field.summary") @Schema(implementation = String.class)
        @JsonDeserialize(using = PatchText.class) Patch<String> summary,
        @JsonProperty("acceptance_criteria") @JsonPropertyDescription("field.acceptance_criteria")
        @Schema(implementation = String.class) @JsonDeserialize(using = PatchText.class) Patch<String> acceptanceCriteria,
        @JsonProperty("priority") @JsonPropertyDescription("field.priority") @Schema(implementation = String.class)
        @JsonDeserialize(using = PatchText.class) Patch<String> priority,
        @JsonProperty("owner_actor_id") @JsonPropertyDescription("field.owner_actor_id")
        @Schema(implementation = String.class) @JsonDeserialize(using = PatchText.class) Patch<String> ownerActorId,
        @JsonProperty("purpose") @JsonPropertyDescription("field.purpose") @Schema(implementation = String.class)
        @JsonDeserialize(using = PatchText.class) Patch<String> purpose,
        @JsonProperty("success_condition") @JsonPropertyDescription("field.success_condition")
        @Schema(implementation = String.class) @JsonDeserialize(using = PatchText.class) Patch<String> successCondition,
        @JsonProperty("origin_ref") @JsonPropertyDescription("field.origin_ref") @Schema(implementation = String.class)
        @JsonDeserialize(using = PatchText.class) Patch<String> originRef,
        @JsonProperty("origin_quote") @JsonPropertyDescription("field.origin_quote")
        @Schema(implementation = String.class) @JsonDeserialize(using = PatchText.class) Patch<String> originQuote,
        @JsonProperty("milestone_name") @JsonPropertyDescription("field.milestone_name")
        @Schema(implementation = String.class) @JsonDeserialize(using = PatchText.class) Patch<String> milestoneName,
        @JsonProperty("goal_label") @JsonPropertyDescription("field.goal_label") @Schema(implementation = String.class)
        @JsonDeserialize(using = PatchText.class) Patch<String> goalLabel,
        @JsonProperty("code") @JsonPropertyDescription("field.code") @Schema(implementation = String.class)
        @JsonDeserialize(using = PatchText.class) Patch<String> code,
        @JsonProperty("criterion_text") @JsonPropertyDescription("field.criterion_text")
        @Schema(implementation = String.class) @JsonDeserialize(using = PatchText.class) Patch<String> criterionText) {

    public FieldsInput toInput() {
        return new FieldsInput(projectId, parentId, title, category, severity, symptom, rootCause, summary,
                acceptanceCriteria, priority, ownerActorId, purpose, successCondition, originRef, originQuote,
                milestoneName, goalLabel, code, criterionText);
    }
}
