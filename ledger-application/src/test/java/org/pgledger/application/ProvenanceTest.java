// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.application;

import static org.assertj.core.api.Assertions.assertThat;
import org.pgledger.testing.TestOwners;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.application.admin.CommonCodeService;
import org.pgledger.application.admin.CommonCodeService.CodeCommand;
import org.pgledger.application.support.Fields;
import org.pgledger.application.support.LedgerServiceTest;
import org.pgledger.application.support.Scenario;
import org.pgledger.application.write.BatchOpInput;
import org.pgledger.application.write.BatchService;
import org.pgledger.application.write.SaveService;
import org.pgledger.application.write.WriteResult.SaveResult;

/** SVC-PRV-01 .. SVC-PRV-03: who created what. */
@LedgerServiceTest
class ProvenanceTest {

    /** A table and the SQL that counts the rows of the scenario that carry the expected creator. */
    record Created(String name, String sql) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Created> createdRows() {
        return Stream.of(
                new Created("task", "SELECT COUNT(*) FROM task WHERE project_id = ? AND created_by = ?"),
                new Created("spec", "SELECT COUNT(*) FROM spec WHERE project_id = ? AND created_by = ?"),
                new Created("issue", "SELECT COUNT(*) FROM issue WHERE project_id = ? AND created_by = ?"),
                new Created("criterion", "SELECT COUNT(*) FROM milestone_criterion WHERE project_id = ? "
                        + "AND created_by = ?"),
                new Created("issue_task", "SELECT COUNT(*) FROM issue_task WHERE task_id IN "
                        + "(SELECT id FROM task WHERE project_id = ?) AND created_by = ?"),
                new Created("issue_spec", "SELECT COUNT(*) FROM issue_spec WHERE spec_id IN "
                        + "(SELECT id FROM spec WHERE project_id = ?) AND created_by = ?"),
                new Created("spec_task", "SELECT COUNT(*) FROM spec_task WHERE spec_id IN "
                        + "(SELECT id FROM spec WHERE project_id = ?) AND created_by = ?"),
                new Created("issue_relation", "SELECT COUNT(*) FROM issue_relation WHERE a IN "
                        + "(SELECT id FROM issue WHERE project_id = ?) AND created_by = ?"),
                new Created("criterion_link", "SELECT COUNT(*) FROM criterion_link WHERE criterion_id IN "
                        + "(SELECT id FROM milestone_criterion WHERE project_id = ?) AND created_by = ?"));
    }

    @ParameterizedTest
    @MethodSource("createdRows")
    @DisplayName("SVC-PRV-01: a batch of the first host author stamps every row it creates with that author")
    void prv01_batchProvenance(Created created, Scenario s) {
        SaveResult i1 = s.newIssue("seed one");
        s.bean(BatchService.class).batch(Scenario.AUTHOR, Scenario.uuid(), List.of(
                new BatchOpInput.Save("task", Fields.of(s.taskFields("t")), Scenario.REASON, s.taskId(1), null),
                new BatchOpInput.Save("spec", Fields.of(s.specFields("sp")), Scenario.REASON, s.specId(1), null),
                new BatchOpInput.Save("criterion", Fields.of(s.criterionFields("r", "c")), Scenario.REASON,
                        s.criterionId("1"), null),
                new BatchOpInput.Save("issue", Fields.of(s.issueFields("second")), Scenario.REASON, null, null),
                new BatchOpInput.Link("issue_task", i1.id(), s.taskId(1), Scenario.REASON, null),
                new BatchOpInput.Link("issue_spec", i1.id(), s.specId(1), Scenario.REASON, null),
                new BatchOpInput.Link("spec_task", s.specId(1), s.taskId(1), Scenario.REASON, null),
                new BatchOpInput.Link("blocks", i1.id(), s.issueId(2), Scenario.REASON, null),
                new BatchOpInput.Link("criterion_task", s.criterionId("1"), s.taskId(1), Scenario.REASON, null)));
        assertThat(s.count(created.sql(), s.projectId(), Scenario.AUTHOR)).isPositive();
        assertThat(s.count(created.sql().replace("created_by = ?", "created_by <> ?"), s.projectId(),
                Scenario.AUTHOR)).isZero();
    }

    @Test
    @DisplayName("SVC-PRV-02: a project and a code added by the owner operations are stamped user:owner")
    void prv02_ownerOperations(Scenario s) {
        s.bean(CommonCodeService.class).set(new CodeCommand("ISSUE_CATEGORY", "OWNED" + s.code(), "owned", null, null,
                null, 70, null), TestOwners.OWNER);
        assertThat(s.count("SELECT COUNT(*) FROM project WHERE id = ? AND created_by = 'user:owner'",
                s.projectId())).isEqualTo(1);
        assertThat(s.count("SELECT COUNT(*) FROM common_code WHERE code = ? AND created_by = 'user:owner'",
                "OWNED" + s.code())).isEqualTo(1);
    }

    @Test
    @DisplayName("SVC-PRV-03: an edit by another author leaves created_by alone")
    void prv03_editKeepsTheCreator(Scenario s) {
        SaveResult i = s.newIssue("i");
        s.bean(SaveService.class).save(Scenario.ISSUE_ONLY, s.edit("issue", i.id(), 1, Map.of("title", "x")));
        assertThat(s.issue(i.id()).createdBy().value()).isEqualTo(Scenario.AUTHOR);
    }
}
