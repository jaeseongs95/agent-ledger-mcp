// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.application.read.DetailService;
import org.pgledger.application.read.GetService;
import org.pgledger.application.read.HistoryService;
import org.pgledger.application.read.HistoryService.HistoryQuery;
import org.pgledger.application.support.Expect;
import org.pgledger.application.support.LedgerAppContext;
import org.pgledger.application.support.Scenario;
import org.pgledger.application.write.LinkCommand;
import org.pgledger.application.write.LinkService;
import org.pgledger.application.write.Rows;
import org.pgledger.application.write.SaveService;
import org.pgledger.application.write.TransitionCommand;
import org.pgledger.application.write.TransitionService;
import org.pgledger.apptest.LedgerTestRunners;
import org.pgledger.database.repository.CriterionRepository;
import org.pgledger.database.repository.SpecRepository;
import org.pgledger.database.repository.TaskRepository;
import org.pgledger.domain.error.ConflictCode;
import org.pgledger.domain.error.LookupCode;
import org.pgledger.domain.value.CriterionId;
import org.pgledger.domain.value.SpecId;
import org.pgledger.domain.value.TaskId;

/**
 * REH3-ID-06: the DBMS that ignores case. The test makes the PostgreSQL of the test PC one for the columns of the ids
 * (a nondeterministic ICU collation of the tests only, {@code CREATE COLLATION}, which is no part of the product): there
 * {@code WHERE id = 'ags:a07-a'} finds {@code AGS:A07-A}. The lookups of the ledger compare the id exactly in Java after the
 * fetch, so the other case is not found, on a DBMS of either kind.
 */
class CaseInsensitiveDbmsTest {

    private static void ignoreCaseInTheIdColumns(LedgerAppContext ctx) {
        var jdbc = ctx.jdbc();
        jdbc.sql("CREATE COLLATION reh3_ci (provider = icu, locale = 'und-u-ks-level2', deterministic = false)").update();
        List<String> fks = jdbc.sql("""
                SELECT tc.table_name || ' DROP CONSTRAINT ' || tc.constraint_name
                FROM information_schema.referential_constraints rc
                JOIN information_schema.table_constraints tc
                  ON tc.constraint_schema = rc.constraint_schema AND tc.constraint_name = rc.constraint_name
                JOIN information_schema.table_constraints pk
                  ON pk.constraint_schema = rc.unique_constraint_schema AND pk.constraint_name = rc.unique_constraint_name
                WHERE tc.constraint_schema = current_schema()
                  AND pk.table_name IN ('task', 'spec', 'milestone_criterion')
                """).query(String.class).list();
        for (String drop : fks) {
            jdbc.sql("ALTER TABLE " + drop).update();
        }
        jdbc.sql("ALTER TABLE task ALTER COLUMN id TYPE VARCHAR(120) COLLATE reh3_ci").update();
        jdbc.sql("ALTER TABLE spec ALTER COLUMN id TYPE VARCHAR(120) COLLATE reh3_ci").update();
        jdbc.sql("ALTER TABLE milestone_criterion ALTER COLUMN id TYPE VARCHAR(120) COLLATE reh3_ci").update();
        jdbc.sql("ALTER TABLE history ALTER COLUMN entity_id TYPE VARCHAR(300) COLLATE reh3_ci").update();
    }

    @Test
    @DisplayName("REH3-ID-06 on a DBMS that ignores case in = the lookups of a task, spec and criterion still compare the id exactly: repositories, Rows, the detail, ledger_get, the history, a transition and a link answer not found for the other case, and a create of it is DUPLICATE_KEY")
    void id06_exactComparisonInJava() {
        LedgerTestRunners.withoutCategories(ctx -> {
            ignoreCaseInTheIdColumns(ctx);
            Scenario s = new Scenario(ctx);
            String task = s.code() + ":A07-A";
            String spec = s.code() + ":SP-A";
            String criterion = s.code() + ":C1";
            s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("task", task, s.taskFields("t")));
            s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("spec", spec, s.specFields("sp")));
            s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("criterion", criterion, s.criterionFields("r", "c")));
            String lowerTask = task.toLowerCase(Locale.ROOT);
            String lowerSpec = spec.toLowerCase(Locale.ROOT);
            String lowerCriterion = criterion.toLowerCase(Locale.ROOT);

            assertThat(s.count("SELECT COUNT(*) FROM task WHERE id = ?", lowerTask)).isEqualTo(1);
            assertThat(s.count("SELECT COUNT(*) FROM spec WHERE id = ?", lowerSpec)).isEqualTo(1);
            assertThat(s.count("SELECT COUNT(*) FROM milestone_criterion WHERE id = ?", lowerCriterion)).isEqualTo(1);

            TaskRepository tasks = ctx.bean(TaskRepository.class);
            SpecRepository specs = ctx.bean(SpecRepository.class);
            CriterionRepository criteria = ctx.bean(CriterionRepository.class);
            assertThat(tasks.findById(TaskId.of(task))).isPresent();
            assertThat(tasks.findById(TaskId.of(lowerTask))).isEmpty();
            assertThat(tasks.existsById(TaskId.of(lowerTask))).isFalse();
            assertThat(specs.findById(SpecId.of(spec))).isPresent();
            assertThat(specs.findById(SpecId.of(lowerSpec))).isEmpty();
            assertThat(specs.existsById(SpecId.of(lowerSpec))).isFalse();
            assertThat(criteria.findById(CriterionId.of(criterion))).isPresent();
            assertThat(criteria.findById(CriterionId.of(lowerCriterion))).isEmpty();
            assertThat(criteria.existsById(CriterionId.of(lowerCriterion))).isFalse();

            Rows rows = ctx.bean(Rows.class);
            Expect.failure(() -> rows.task(TaskId.of(lowerTask)), LookupCode.NOT_FOUND);
            Expect.failure(() -> rows.spec(SpecId.of(lowerSpec)), LookupCode.NOT_FOUND);
            Expect.failure(() -> ctx.tx(() -> rows.taskForWrite(TaskId.of(lowerTask), null)), LookupCode.NOT_FOUND);
            Expect.failure(() -> ctx.tx(() -> rows.specForWrite(SpecId.of(lowerSpec), null)), LookupCode.NOT_FOUND);
            Expect.failure(() -> ctx.tx(() -> rows.criterionForWrite(CriterionId.of(lowerCriterion), null)),
                    LookupCode.NOT_FOUND);
            assertThat(ctx.tx(() -> rows.taskForWrite(TaskId.of(task), null)).id().value()).isEqualTo(task);
            assertThat(ctx.tx(() -> rows.specForWrite(SpecId.of(spec), null)).id().value()).isEqualTo(spec);

            Expect.failure(() -> ctx.bean(DetailService.class).task(lowerTask), LookupCode.NOT_FOUND);
            Expect.failure(() -> ctx.bean(DetailService.class).spec(lowerSpec), LookupCode.NOT_FOUND);
            assertThat(ctx.bean(DetailService.class).task(task).id()).isEqualTo(task);
            assertThat(ctx.bean(DetailService.class).spec(spec).id()).isEqualTo(spec);
            GetService.Result got = ctx.bean(GetService.class).get(List.of(lowerTask, task, lowerSpec, spec,
                    lowerCriterion));
            assertThat(got.items()).extracting(GetService.Item::id).containsExactly(task, spec);
            assertThat(got.notFound()).containsExactlyInAnyOrder(lowerTask, lowerSpec, lowerCriterion);

            HistoryService history = ctx.bean(HistoryService.class);
            assertThat(history.history(new HistoryQuery(null, null, null, "task", lowerTask, null, null, null, null, null,
                    false, 50, null)).items()).isEmpty();
            assertThat(history.history(new HistoryQuery(null, null, null, "task", task, null, null, null, null, null,
                    false, 50, null)).items()).hasSize(1);

            Expect.failure(() -> s.bean(TransitionService.class).transition(Scenario.AUTHOR, new TransitionCommand(
                    Scenario.uuid(), "task", lowerTask, 1L, "ACTIVE", Scenario.REASON, null, null, null)),
                    LookupCode.NOT_FOUND);
            Expect.failure(() -> s.bean(LinkService.class).link(Scenario.AUTHOR, new LinkCommand(Scenario.uuid(),
                    "criterion_task", criterion, lowerTask, Scenario.REASON, null)), LookupCode.NOT_FOUND);
            Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("task", lowerTask,
                    s.taskFields("sibling"))), ConflictCode.DUPLICATE_KEY);
            assertThat(s.count("SELECT COUNT(*) FROM task WHERE project_id = ?", s.projectId())).isEqualTo(1);
        });
    }
}
