// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.veridger.domain.error.ValidationException;
import org.veridger.domain.model.Criterion;
import org.veridger.domain.model.CriterionFields;
import org.veridger.domain.model.Issue;
import org.veridger.domain.model.IssueFields;
import org.veridger.domain.model.Spec;
import org.veridger.domain.model.SpecFields;
import org.veridger.domain.model.Task;
import org.veridger.domain.model.TaskFields;
import org.veridger.domain.value.CriterionId;
import org.veridger.domain.value.IssueId;
import org.veridger.domain.value.Patch;
import org.veridger.domain.value.SpecId;
import org.veridger.domain.value.TaskId;
import org.veridger.domain.value.TextRule;

/**
 * REH3-TXT-01 .. 04 at the domain level: {@code purpose}, {@code success_condition}, {@code symptom},
 * {@code root_cause}, {@code summary}, {@code acceptance_criteria}, and {@code criterion_text} may be empty (stored
 * as SQL NULL); check references and codes leave room for imported values.
 */
class EmptyTextTest {

    private static final Instant NOW = Instant.parse("2026-11-12T00:00:00Z");

    @Test
    void reh3Txt01_aTaskMayHaveEmptyPurposeAndSuccessCondition() {
        TaskFields fields = TaskFields.of(Patch.set("p1"), Patch.set("title"), Patch.set(""), Patch.set("   "),
                Patch.absent(), Patch.absent(), Patch.absent());

        Task task = Task.plan(TaskId.of("AGS:A07-a"), fields, NOW);

        assertThat(task.purpose()).isNull();
        assertThat(task.successCondition()).isNull();
        Task edited = task.edited(TaskFields.of(Patch.absent(), Patch.absent(), Patch.set("a purpose"), Patch.absent(),
                Patch.absent(), Patch.absent(), Patch.absent()), NOW);
        assertThat(edited.purpose()).isEqualTo("a purpose");
        assertThat(edited.edited(TaskFields.of(Patch.absent(), Patch.absent(), Patch.set(""), Patch.absent(),
                Patch.absent(), Patch.absent(), Patch.absent()), NOW).purpose()).isNull();
    }

    @Test
    void reh3Txt02_theFieldHasToBeGiven() {
        // null is not empty: the column has no default
        Expect.invalid(() -> TaskFields.of(Patch.set("p1"), Patch.set("title"), Patch.set(null), Patch.set("s"),
                Patch.absent(), Patch.absent(), Patch.absent()));
        TaskFields withoutPurpose = TaskFields.of(Patch.set("p1"), Patch.set("title"), Patch.absent(), Patch.set("s"),
                Patch.absent(), Patch.absent(), Patch.absent());
        Expect.invalid(withoutPurpose::requireCreatable);
        Expect.invalid(() -> IssueFields.of(Patch.set("p1"), Patch.set("t"), Patch.set("PROCESS"), Patch.set("P2"),
                Patch.absent(), Patch.set("c"), Patch.absent()).requireCreatable());
        Expect.invalid(() -> CriterionFields.of(Patch.set("p1"), Patch.set("1.0"), Patch.absent(), Patch.set("C1"),
                Patch.absent()).requireCreatable());
        // a title and a milestone name are still required to have text
        Expect.invalid(() -> TaskFields.of(Patch.set("p1"), Patch.set(""), Patch.set("p"), Patch.set("s"),
                Patch.absent(), Patch.absent(), Patch.absent()));
        Expect.invalid(() -> CriterionFields.of(Patch.set("p1"), Patch.set(""), Patch.absent(), Patch.set("C1"),
                Patch.set("t")));
        Expect.invalid(() -> SpecFields.of(Patch.set("p1"), Patch.absent(), Patch.set(""), Patch.set("s"),
                Patch.set("ac"), Patch.absent(), Patch.absent(), Patch.absent(), Patch.absent()));
    }

    @Test
    void reh3Txt04_anIssueSpecAndCriterionMayHaveEmptyText() {
        IssueFields issue = IssueFields.of(Patch.set("p1"), Patch.set("t"), Patch.set("PROCESS"), Patch.set("P2"),
                Patch.set(""), Patch.set(" "), Patch.absent());
        Issue row = Issue.open(IssueId.of("AGS-I-0001"), issue, NOW);
        assertThat(row.symptom()).isNull();
        assertThat(row.rootCause()).isNull();

        SpecFields specFields = SpecFields.of(Patch.set("p1"), Patch.absent(), Patch.set("Spec Title"),
                Patch.set(""), Patch.set("  "), Patch.absent(), Patch.absent(), Patch.absent(), Patch.absent());
        Spec spec = Spec.create(SpecId.of("AGS-S-0001"), specFields, NOW);
        assertThat(spec.summary()).isNull();
        assertThat(spec.acceptanceCriteria()).isNull();

        CriterionFields criterion = CriterionFields.of(Patch.set("p1"), Patch.set("1.0"), Patch.absent(),
                Patch.set("C1"), Patch.set(""));
        Criterion open = Criterion.open(CriterionId.of("ags:c1"), criterion);
        assertThat(open.criterionText()).isNull();
    }

    @Test
    void reh3Len02_theOriginReferenceHoldsALongImportedValue() {
        assertThat(TextRule.ORIGIN_REF.max()).isEqualTo(20000);
        assertThat(TextRule.ORIGIN_REF.optional("o".repeat(9408))).hasSize(9408);
        assertThat(TextRule.ORIGIN_REF.optional("o".repeat(20000))).hasSize(20000);
        Expect.invalid(() -> TextRule.ORIGIN_REF.optional("o".repeat(20001)));
    }

    @Test
    void reh3Len01_theLimitsLeaveRoomForImportedValues() {
        assertThat(TextRule.CRITERION_CODE.max()).isEqualTo(200);
        assertThat(TextRule.CHECK_REF.max()).isEqualTo(2000);
        assertThat(TextRule.CRITERION_CODE.required("c".repeat(200))).hasSize(200);
        assertThat(TextRule.CHECK_REF.optional("r".repeat(2000))).hasSize(2000);
        Expect.invalid(() -> TextRule.CRITERION_CODE.required("c".repeat(201)));
        Expect.invalid(() -> TextRule.CHECK_REF.optional("r".repeat(2001)));
        ValidationException refused = (ValidationException) Expect.invalid(
                () -> TextRule.CHECK_REF.optional("r".repeat(2001)));
        assertThat(refused.issues().getFirst().reasonArgs()).containsExactly("2000");
    }
}
