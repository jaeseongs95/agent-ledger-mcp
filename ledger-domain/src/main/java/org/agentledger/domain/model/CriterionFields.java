// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.domain.model;

import org.agentledger.domain.rule.HistoryEntity;
import org.agentledger.domain.value.Patch;
import org.agentledger.domain.value.ProjectId;
import org.agentledger.domain.value.TextRule;

/** Editable fields of a milestone criterion. */
public record CriterionFields(Patch<ProjectId> projectId, Patch<String> milestone, Patch<String> goalLabel,
        Patch<String> code, Patch<String> criterionText) implements EntityFields {

    public static CriterionFields of(Patch<String> projectId, Patch<String> milestone, Patch<String> goalLabel,
            Patch<String> code, Patch<String> criterionText) {
        return new CriterionFields(projectId.map(ProjectId::of), milestone.map(TextRule.MILESTONE_NAME::required),
                goalLabel.map(TextRule.GOAL_LABEL::optional), code.map(TextRule.CRITERION_CODE::required),
                criterionText.map(TextRule.CRITERION_TEXT::givenOrEmpty));
    }

    @Override
    public HistoryEntity entity() {
        return HistoryEntity.CRITERION;
    }

    @Override
    public CriterionFields requireCreatable() {
        EntityFields.requireValue("project_id", projectId);
        EntityFields.requireValue("milestone_name", milestone);
        EntityFields.requireValue("code", code);
        EntityFields.requireValue("criterion_text", criterionText);
        return this;
    }

    @Override
    public CriterionFields requireEditable() {
        EntityFields.requireAnyMentioned(projectId, milestone, goalLabel, code, criterionText);
        return this;
    }
}
