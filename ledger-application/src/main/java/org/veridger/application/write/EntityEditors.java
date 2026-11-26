// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application.write;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.veridger.domain.error.ValidationException;
import org.veridger.domain.error.ValidationReason;
import org.veridger.domain.model.Criterion;
import org.veridger.domain.model.CriterionFields;
import org.veridger.domain.model.EntityFields;
import org.veridger.domain.model.Issue;
import org.veridger.domain.model.IssueFields;
import org.veridger.domain.model.Spec;
import org.veridger.domain.model.SpecFields;
import org.veridger.domain.model.Task;
import org.veridger.domain.model.TaskFields;
import org.veridger.domain.rule.HistoryEntity;
import org.veridger.domain.value.AuthorId;
import org.veridger.domain.value.CodeValue;
import org.veridger.domain.value.Patch;
import org.veridger.domain.value.ProjectId;
import org.veridger.domain.value.SpecId;
import org.veridger.domain.value.Tokens;
import org.springframework.stereotype.Component;

/**
 * Parses editable {@link FieldsInput} into typed domain {@link EntityFields} and projects rows/patches to canonical
 * {@code field_name -> text} maps for history diffs and receipt digests.
 */
@Component
public class EntityEditors {

    private static final List<String> ISSUE_KEYS = List.of("project_id", "title", "category", "severity",
            "symptom", "root_cause", "owner_actor_id");
    private static final List<String> SPEC_KEYS = List.of("project_id", "parent_id", "title", "summary",
            "acceptance_criteria", "priority", "owner_actor_id", "origin_ref", "origin_quote");
    private static final List<String> TASK_KEYS = List.of("project_id", "title", "purpose", "success_condition",
            "owner_actor_id", "origin_ref", "origin_quote");
    private static final List<String> CRITERION_KEYS = List.of("project_id", "milestone_name", "goal_label", "code",
            "criterion_text");

    /** Entity kinds writable via save operations. */
    static final HistoryEntity[] SAVEABLE = {
        HistoryEntity.ISSUE, HistoryEntity.SPEC, HistoryEntity.TASK, HistoryEntity.CRITERION
    };

    public List<String> keys(HistoryEntity entity) {
        return switch (entity) {
            case ISSUE -> ISSUE_KEYS;
            case SPEC -> SPEC_KEYS;
            case TASK -> TASK_KEYS;
            case CRITERION -> CRITERION_KEYS;
            case DELIVERABLE, ISSUE_TASK, ISSUE_SPEC, SPEC_TASK, ISSUE_RELATION, CRITERION_LINK, ISSUE_CASE ->
                throw ValidationException.invalid("entity", ValidationReason.CANNOT_BE_SAVED,
                        Tokens.allowed(SAVEABLE), entity.token());
        };
    }

    public EntityFields parse(HistoryEntity entity, FieldsInput input) {
        if (input == null) {
            throw ValidationException.invalid("fields", ValidationReason.REQUIRED);
        }
        List<String> allowed = keys(entity);
        for (String key : input.mentioned().keySet()) {
            if (!allowed.contains(key)) {
                throw ValidationException.invalid("fields." + key, ValidationReason.NOT_AN_EDITABLE_FIELD_OF, allowed,
                        entity.token());
            }
        }
        return switch (entity) {
            case ISSUE -> IssueFields.of(input.projectId(), input.title(), input.category(), input.severity(),
                    input.symptom(), input.rootCause(), input.ownerActorId());
            case SPEC -> SpecFields.of(input.projectId(), input.parentId(), input.title(), input.summary(),
                    input.acceptanceCriteria(), input.priority(), input.ownerActorId(), input.originRef(),
                    input.originQuote());
            case TASK -> TaskFields.of(input.projectId(), input.title(), input.purpose(), input.successCondition(),
                    input.ownerActorId(), input.originRef(), input.originQuote());
            case CRITERION -> CriterionFields.of(input.projectId(), input.milestoneName(), input.goalLabel(),
                    input.code(), input.criterionText());
            case DELIVERABLE, ISSUE_TASK, ISSUE_SPEC, SPEC_TASK, ISSUE_RELATION, CRITERION_LINK, ISSUE_CASE ->
                throw new IllegalStateException("keys() refused it");
        };
    }

    public Map<String, String> mentioned(EntityFields fields) {
        Map<String, String> out = new LinkedHashMap<>();
        switch (fields) {
            case IssueFields f -> {
                put(out, "project_id", f.projectId(), ProjectId::value);
                put(out, "title", f.title(), Function.identity());
                put(out, "category", f.category(), CodeValue::value);
                put(out, "severity", f.severity(), CodeValue::value);
                put(out, "symptom", f.symptom(), Function.identity());
                put(out, "root_cause", f.rootCause(), Function.identity());
                put(out, "owner_actor_id", f.ownerActorId(), AuthorId::value);
            }
            case SpecFields f -> {
                put(out, "project_id", f.projectId(), ProjectId::value);
                put(out, "parent_id", f.parentId(), SpecId::value);
                put(out, "title", f.title(), Function.identity());
                put(out, "summary", f.summary(), Function.identity());
                put(out, "acceptance_criteria", f.acceptanceCriteria(), Function.identity());
                put(out, "priority", f.priority(), Function.identity());
                put(out, "owner_actor_id", f.ownerActorId(), AuthorId::value);
                put(out, "origin_ref", f.originRef(), Function.identity());
                put(out, "origin_quote", f.originQuote(), Function.identity());
            }
            case TaskFields f -> {
                put(out, "project_id", f.projectId(), ProjectId::value);
                put(out, "title", f.title(), Function.identity());
                put(out, "purpose", f.purpose(), Function.identity());
                put(out, "success_condition", f.successCondition(), Function.identity());
                put(out, "owner_actor_id", f.ownerActorId(), AuthorId::value);
                put(out, "origin_ref", f.originRef(), Function.identity());
                put(out, "origin_quote", f.originQuote(), Function.identity());
            }
            case CriterionFields f -> {
                put(out, "project_id", f.projectId(), ProjectId::value);
                put(out, "milestone_name", f.milestone(), Function.identity());
                put(out, "goal_label", f.goalLabel(), Function.identity());
                put(out, "code", f.code(), Function.identity());
                put(out, "criterion_text", f.criterionText(), Function.identity());
            }
        }
        return out;
    }

    private static <T> void put(Map<String, String> out, String key, Patch<T> patch, Function<T, String> text) {
        if (patch.mentioned()) {
            T value = patch.orNull();
            out.put(key, value == null ? null : text.apply(value));
        }
    }

    public Map<String, String> columns(Issue i) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("project_id", i.projectId().value());
        out.put("title", i.title());
        out.put("category", i.category().value());
        out.put("severity", i.severity().value());
        out.put("symptom", i.symptom());
        out.put("root_cause", i.rootCause());
        out.put("owner_actor_id", i.ownerActorId() == null ? null : i.ownerActorId().value());
        return out;
    }

    public Map<String, String> columns(Spec s) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("project_id", s.projectId().value());
        out.put("parent_id", s.parentId() == null ? null : s.parentId().value());
        out.put("title", s.title());
        out.put("summary", s.summary());
        out.put("acceptance_criteria", s.acceptanceCriteria());
        out.put("priority", s.priority());
        out.put("owner_actor_id", s.ownerActorId() == null ? null : s.ownerActorId().value());
        out.put("origin_ref", s.originRef());
        out.put("origin_quote", s.originQuote());
        return out;
    }

    public Map<String, String> columns(Task t) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("project_id", t.projectId().value());
        out.put("title", t.title());
        out.put("purpose", t.purpose());
        out.put("success_condition", t.successCondition());
        out.put("owner_actor_id", t.ownerActorId() == null ? null : t.ownerActorId().value());
        out.put("origin_ref", t.originRef());
        out.put("origin_quote", t.originQuote());
        return out;
    }

    public Map<String, String> columns(Criterion c) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("project_id", c.projectId().value());
        out.put("milestone_name", c.milestone());
        out.put("goal_label", c.goalLabel());
        out.put("code", c.code());
        out.put("criterion_text", c.criterionText());
        return out;
    }

    public Set<String> keySet(HistoryEntity entity) {
        return Set.copyOf(keys(entity));
    }
}
