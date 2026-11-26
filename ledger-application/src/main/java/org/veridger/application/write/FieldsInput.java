// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application.write;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.LinkedHashMap;
import java.util.Map;
import org.veridger.application.json.PatchText;
import org.veridger.domain.value.Patch;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * Editable {@code fields} of an issue, specification, task, or milestone criterion as wire patches:
 * omitted or JSON {@code null} means {@link Patch#absent}, empty text clears an optional field.
 */
public record FieldsInput(
        @JsonProperty("project_id") @JsonDeserialize(using = PatchText.class) Patch<String> projectId,
        @JsonProperty("parent_id") @JsonDeserialize(using = PatchText.class) Patch<String> parentId,
        @JsonProperty("title") @JsonDeserialize(using = PatchText.class) Patch<String> title,
        @JsonProperty("category") @JsonDeserialize(using = PatchText.class) Patch<String> category,
        @JsonProperty("severity") @JsonDeserialize(using = PatchText.class) Patch<String> severity,
        @JsonProperty("symptom") @JsonDeserialize(using = PatchText.class) Patch<String> symptom,
        @JsonProperty("root_cause") @JsonDeserialize(using = PatchText.class) Patch<String> rootCause,
        @JsonProperty("summary") @JsonDeserialize(using = PatchText.class) Patch<String> summary,
        @JsonProperty("acceptance_criteria") @JsonDeserialize(using = PatchText.class) Patch<String> acceptanceCriteria,
        @JsonProperty("priority") @JsonDeserialize(using = PatchText.class) Patch<String> priority,
        @JsonProperty("owner_actor_id") @JsonDeserialize(using = PatchText.class) Patch<String> ownerActorId,
        @JsonProperty("purpose") @JsonDeserialize(using = PatchText.class) Patch<String> purpose,
        @JsonProperty("success_condition") @JsonDeserialize(using = PatchText.class) Patch<String> successCondition,
        @JsonProperty("origin_ref") @JsonDeserialize(using = PatchText.class) Patch<String> originRef,
        @JsonProperty("origin_quote") @JsonDeserialize(using = PatchText.class) Patch<String> originQuote,
        @JsonProperty("milestone_name") @JsonDeserialize(using = PatchText.class) Patch<String> milestoneName,
        @JsonProperty("goal_label") @JsonDeserialize(using = PatchText.class) Patch<String> goalLabel,
        @JsonProperty("code") @JsonDeserialize(using = PatchText.class) Patch<String> code,
        @JsonProperty("criterion_text") @JsonDeserialize(using = PatchText.class) Patch<String> criterionText) {

    public FieldsInput {
        projectId = orAbsent(projectId);
        parentId = orAbsent(parentId);
        title = orAbsent(title);
        category = orAbsent(category);
        severity = orAbsent(severity);
        symptom = orAbsent(symptom);
        rootCause = orAbsent(rootCause);
        summary = orAbsent(summary);
        acceptanceCriteria = orAbsent(acceptanceCriteria);
        priority = orAbsent(priority);
        ownerActorId = orAbsent(ownerActorId);
        purpose = orAbsent(purpose);
        successCondition = orAbsent(successCondition);
        originRef = orAbsent(originRef);
        originQuote = orAbsent(originQuote);
        milestoneName = orAbsent(milestoneName);
        goalLabel = orAbsent(goalLabel);
        code = orAbsent(code);
        criterionText = orAbsent(criterionText);
    }

    private static Patch<String> orAbsent(Patch<String> patch) {
        return patch == null ? Patch.absent() : patch;
    }

    /** Mentioned keys in contract order. */
    Map<String, Patch<String>> mentioned() {
        Map<String, Patch<String>> keys = new LinkedHashMap<>();
        put(keys, "project_id", projectId);
        put(keys, "parent_id", parentId);
        put(keys, "title", title);
        put(keys, "category", category);
        put(keys, "severity", severity);
        put(keys, "symptom", symptom);
        put(keys, "root_cause", rootCause);
        put(keys, "summary", summary);
        put(keys, "acceptance_criteria", acceptanceCriteria);
        put(keys, "priority", priority);
        put(keys, "owner_actor_id", ownerActorId);
        put(keys, "purpose", purpose);
        put(keys, "success_condition", successCondition);
        put(keys, "origin_ref", originRef);
        put(keys, "origin_quote", originQuote);
        put(keys, "milestone_name", milestoneName);
        put(keys, "goal_label", goalLabel);
        put(keys, "code", code);
        put(keys, "criterion_text", criterionText);
        return keys;
    }

    private static void put(Map<String, Patch<String>> keys, String key, Patch<String> patch) {
        if (patch.mentioned()) {
            keys.put(key, patch);
        }
    }
}
