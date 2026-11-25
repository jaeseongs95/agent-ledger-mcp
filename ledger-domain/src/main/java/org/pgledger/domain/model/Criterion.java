// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.domain.model;

import java.util.List;
import org.pgledger.domain.error.TransitionCode;
import org.pgledger.domain.error.TransitionException;
import org.pgledger.domain.rule.CriterionState;
import org.pgledger.domain.value.ActorId;
import org.pgledger.domain.value.CriterionId;
import org.pgledger.domain.value.ProjectId;
import org.pgledger.domain.value.TextRule;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.InsertOnlyProperty;
import org.springframework.data.relational.core.mapping.Table;

/**
 * A milestone criterion (goal/gate verified as MET or NOT_MET). Table {@code milestone_criterion}.
 */
@Table("milestone_criterion")
public record Criterion(@Id CriterionId id, ProjectId projectId, @Column("milestone_name") String milestone,
        String goalLabel, String code, String criterionText, CriterionState state, @Version long version,
        @CreatedBy @InsertOnlyProperty ActorId createdBy, @InsertOnlyProperty String idKey) {

    public Criterion {
        criterionText = TextRule.emptyToNull(criterionText);
        goalLabel = TextRule.emptyToNull(goalLabel);
        idKey = id == null ? null : id.key();
    }

    /** Verified/closed criteria (MET or NOT_MET) cannot be edited by save until reopened by a reviewer. */
    public void requireOpenForEdit() {
        if (state.verification()) {
            throw new TransitionException(TransitionCode.VERIFIED_ENTITY, List.of(id.value()));
        }
    }

    /** A new UNKNOWN criterion from complete fields. */
    public static Criterion open(CriterionId id, CriterionFields fields) {
        CriterionFields f = fields.requireCreatable();
        return new Criterion(id, f.projectId().orNull(), f.milestone().orNull(), f.goalLabel().orNull(),
                f.code().orNull(), f.criterionText().orNull(), CriterionState.UNKNOWN, 0, null, null);
    }

    public Criterion edited(CriterionFields f) {
        return new Criterion(id, f.projectId().orElse(projectId), f.milestone().orElse(milestone),
                f.goalLabel().orElse(goalLabel), f.code().orElse(code), f.criterionText().orElse(criterionText),
                state, version, createdBy, idKey);
    }

    public Criterion transitioned(CriterionState to) {
        return new Criterion(id, projectId, milestone, goalLabel, code, criterionText, to, version, createdBy, idKey);
    }
}
