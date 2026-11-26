// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.application;

import static org.assertj.core.api.Assertions.assertThat;
import org.agentledger.testing.TestOwners;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.agentledger.application.admin.CommonCodeService;
import org.agentledger.application.admin.CommonCodeService.CodeCommand;
import org.agentledger.application.admin.ProjectService;
import org.agentledger.application.admin.ProjectService.ProjectCommand;
import org.agentledger.application.read.AttentionService;
import org.agentledger.application.read.AttentionService.AttentionQuery;
import org.agentledger.application.read.CatalogService;
import org.agentledger.application.read.CatalogService.CodesQuery;
import org.agentledger.application.read.CatalogService.ProjectsQuery;
import org.agentledger.application.read.HistoryService;
import org.agentledger.application.read.HistoryService.HistoryQuery;
import org.agentledger.application.read.ListService;
import org.agentledger.application.read.ListService.CriteriaQuery;
import org.agentledger.application.read.ListService.IssuesQuery;
import org.agentledger.application.read.ListService.SpecsQuery;
import org.agentledger.application.read.ListService.TasksQuery;
import org.agentledger.application.read.Page;
import org.agentledger.application.read.SearchService;
import org.agentledger.application.read.SearchService.SearchQuery;
import org.agentledger.application.support.Expect;
import org.agentledger.application.support.Fields;
import org.agentledger.application.support.LedgerAppContext;
import org.agentledger.application.support.LedgerServiceTest;
import org.agentledger.application.support.Scenario;
import org.agentledger.application.write.BatchOpInput;
import org.agentledger.application.write.BatchService;
import org.agentledger.application.write.SaveService;
import org.agentledger.domain.error.ValidationCode;
import org.agentledger.domain.error.ValidationException;

/**
 * SVC side of MCR-002 .. MCR-009: every list of the read tools pages the same way (row limit, truncated, next
 * cursor, stability, damaged cursors). One fixture of 137 rows per kind is built once for the whole class.
 */
@LedgerServiceTest
class ReadListTest {

    private static final int ROWS = 137;

    /** The nine list tools. */
    enum Tool {
        ISSUES, SPECS, TASKS, CRITERIA, HISTORY, SEARCH, ATTENTION, PROJECTS, CODES
    }

    /** How a cursor is damaged. */
    enum Damage {
        GARBAGE, CUT, FOREIGN
    }

    private static Scenario fixture(LedgerAppContext ctx) {
        return ctx.once("read-list", () -> {
            Scenario s = new Scenario(ctx);
            List<BatchOpInput> ops = new ArrayList<>();
            IntStream.rangeClosed(1, ROWS).forEach(i -> {
                ops.add(new BatchOpInput.Save("issue", Fields.of(s.issueFields("row " + i)), Scenario.REASON, null,
                        null));
                ops.add(new BatchOpInput.Save("spec", Fields.of(s.specFields("row " + i)), Scenario.REASON, null,
                        null));
                ops.add(new BatchOpInput.Save("task", Fields.of(s.taskFields("row " + i)), Scenario.REASON, null, null));
            });
            for (int from = 0; from < ops.size(); from += 100) {
                s.bean(BatchService.class).batch(Scenario.AUTHOR, Scenario.uuid(), ops.subList(from, Math.min(from + 100,
                        ops.size())));
            }
            List<BatchOpInput> criteria = IntStream.rangeClosed(1, ROWS).mapToObj(i -> (BatchOpInput)
                    new BatchOpInput.Save("criterion", Fields.of(s.criterionFields("r1", "c" + i)), Scenario.REASON,
                            s.criterionId(String.format("%04d", i)), null)).toList();
            s.bean(BatchService.class).batch(Scenario.AUTHOR, Scenario.uuid(), criteria);
            IntStream.rangeClosed(1, ROWS).forEach(i -> s.bean(ProjectService.class).add(new ProjectCommand(
                    "list-" + s.code().toLowerCase() + "-" + i, "L" + s.code() + "X" + i, "list project " + i),
                    TestOwners.OWNER));
            IntStream.rangeClosed(1, ROWS).forEach(i -> s.bean(CommonCodeService.class).set(new CodeCommand(
                    "RELATION_KIND", "LST_" + s.code() + "_" + i, "목록 " + i, null, null, null, 500 + i,
                    "INACTIVE"), TestOwners.OWNER));
            return s;
        });
    }

    private static Page<?> call(Scenario s, Tool tool, Number maxRows, String cursor, boolean narrowed) {
        String project = s.projectId();
        return switch (tool) {
            case ISSUES -> s.bean(ListService.class).issues(new IssuesQuery(project, narrowed ? "OPEN" : null, null,
                    null, null, null, maxRows, cursor));
            case SPECS -> s.bean(ListService.class).specs(new SpecsQuery(project, narrowed ? "PLANNED" : null, null,
                    null, null, null, maxRows, cursor));
            case TASKS -> s.bean(ListService.class).tasks(new TasksQuery(project, narrowed ? "PLANNED" : null, null,
                    null, maxRows, cursor));
            case CRITERIA -> s.bean(ListService.class).criteria(new CriteriaQuery(project, narrowed ? "UNKNOWN"
                    : null, null, maxRows, cursor));
            case HISTORY -> s.bean(HistoryService.class).history(new HistoryQuery(null, null, null, narrowed
                    ? "issue" : null, null, project, null, null, null, null, null, maxRows, cursor));
            case SEARCH -> s.bean(SearchService.class).search(new SearchQuery(project, null, List.of("row"), null,
                    narrowed ? List.of("MERGED") : null, null, null, maxRows, cursor));
            case ATTENTION -> s.bean(AttentionService.class).attention(new AttentionQuery(project, narrowed
                    ? "OPEN_NO_OWNER" : null, maxRows, cursor));
            case PROJECTS -> s.bean(CatalogService.class).projects(new ProjectsQuery(maxRows, cursor));
            case CODES -> s.bean(CatalogService.class).codes(new CodesQuery("RELATION_KIND", true, maxRows, cursor));
        };
    }

    private static int total(Scenario s, Tool tool) {
        return (int) switch (tool) {
            case ISSUES, SPECS, TASKS, CRITERIA, ATTENTION -> ROWS;
            case HISTORY -> s.count("SELECT COUNT(*) FROM history WHERE entity_id IN (SELECT id FROM issue "
                    + "WHERE project_id = ?)", s.projectId()) + s.count("SELECT COUNT(*) FROM history WHERE "
                    + "entity_id IN (SELECT id FROM spec WHERE project_id = ?)", s.projectId()) + s.count(
                    "SELECT COUNT(*) FROM history WHERE entity_id IN (SELECT id FROM task WHERE project_id = ?)",
                    s.projectId()) + s.count("SELECT COUNT(*) FROM history WHERE entity_id IN (SELECT id FROM "
                    + "milestone_criterion WHERE project_id = ?)", s.projectId());
            case SEARCH -> 3L * ROWS;
            case PROJECTS -> s.count("SELECT COUNT(*) FROM project");
            case CODES -> s.count("SELECT COUNT(*) FROM common_code WHERE code_group = 'RELATION_KIND'");
        };
    }

    @ParameterizedTest
    @EnumSource(Tool.class)
    @DisplayName("MCR-002: a limit of 1 and of 1000 is accepted")
    void mcr002_limits(Tool tool, LedgerAppContext ctx) {
        Scenario s = fixture(ctx);
        assertThat(call(s, tool, 1, null, false).items()).hasSize(1);
        assertThat(call(s, tool, 1000, null, false).items()).isNotEmpty();
    }

    static Stream<Arguments> everyToolWithEachBadLimit() {
        return Stream.of(Tool.values()).flatMap(tool -> Stream.<Number>of(0, -1, 1001, 50.5)
                .map(limit -> Arguments.of(tool, limit)));
    }

    @ParameterizedTest(name = "{0} with max_rows {1}")
    @MethodSource("everyToolWithEachBadLimit")
    @DisplayName("MCR-003: a limit outside 1..1000 or with a fraction is refused and named")
    void mcr003_badLimits(Tool tool, Number limit, LedgerAppContext ctx) {
        Scenario s = fixture(ctx);
        ValidationException e = (ValidationException) Expect.failure(() -> call(s, tool, limit, null, false),
                ValidationCode.VALIDATION_FAILED);
        assertThat(e.issues().get(0).path()).isEqualTo("max_rows");
    }

    @ParameterizedTest
    @EnumSource(Tool.class)
    @DisplayName("MCR-004: without a limit 50 rows come, with truncated and a cursor")
    void mcr004_defaultLimit(Tool tool, LedgerAppContext ctx) {
        Page<?> page = call(fixture(ctx), tool, null, null, false);
        assertThat(page.items()).hasSize(50);
        assertThat(page.truncated()).isTrue();
        assertThat(page.nextCursor()).isNotBlank();
    }

    @ParameterizedTest
    @EnumSource(Tool.class)
    @DisplayName("MCR-005: exactly the limit is not truncated and has no cursor")
    void mcr005_exactLimit(Tool tool, LedgerAppContext ctx) {
        Scenario s = fixture(ctx);
        int total = total(s, tool);
        Page<?> exact = call(s, tool, Math.min(total, 1000), null, false);
        assertThat(exact.items()).hasSize(Math.min(total, 1000));
        assertThat(exact.truncated()).isEqualTo(total > 1000);
        assertThat(exact.nextCursor() == null).isEqualTo(total <= 1000);
    }

    @ParameterizedTest
    @EnumSource(Tool.class)
    @DisplayName("MCR-006: one row more than the limit is truncated and has a cursor")
    void mcr006_oneMore(Tool tool, LedgerAppContext ctx) {
        Scenario s = fixture(ctx);
        int limit = Math.min(total(s, tool) - 1, 1000);
        Page<?> page = call(s, tool, limit, null, false);
        assertThat(page.items()).hasSize(limit);
        assertThat(page.truncated()).isTrue();
        assertThat(page.nextCursor()).isNotBlank();
    }

    @ParameterizedTest
    @EnumSource(Tool.class)
    @DisplayName("MCR-007: following the cursor gives every row exactly once, the last page is not truncated")
    void mcr007_everyRowOnce(Tool tool, LedgerAppContext ctx) {
        Scenario s = fixture(ctx);
        List<Object> seen = new ArrayList<>();
        String cursor = null;
        Page<?> page;
        do {
            page = call(s, tool, 10, cursor, false);
            seen.addAll(page.items());
            cursor = page.nextCursor();
        } while (page.truncated());
        assertThat(seen).hasSize(total(s, tool));
        assertThat(new HashSet<>(seen)).hasSameSizeAs(seen);
        assertThat(page.nextCursor()).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = Tool.class, names = {"ISSUES", "SPECS", "TASKS", "HISTORY", "SEARCH"})
    @DisplayName("MCR-008: new rows and edits between two pages cause no duplicate and no repeated row")
    void mcr008_cursorStability(Tool tool, LedgerAppContext ctx) {
        Scenario s = ctx.once("read-list-stability", () -> {
            Scenario own = new Scenario(ctx);
            List<BatchOpInput> ops = new ArrayList<>();
            IntStream.rangeClosed(1, 40).forEach(i -> {
                ops.add(new BatchOpInput.Save("issue", Fields.of(own.issueFields("row " + i)), Scenario.REASON, null,
                        null));
                ops.add(new BatchOpInput.Save("spec", Fields.of(own.specFields("row " + i)), Scenario.REASON, null,
                        null));
                ops.add(new BatchOpInput.Save("task", Fields.of(own.taskFields("row " + i)), Scenario.REASON, null, null));
            });
            for (int from = 0; from < ops.size(); from += 100) {
                own.bean(BatchService.class).batch(Scenario.AUTHOR, Scenario.uuid(), ops.subList(from, Math.min(
                        from + 100, ops.size())));
            }
            return own;
        });
        Page<?> first = call(s, tool, 10, null, false);
        List<Object> original = new ArrayList<>(first.items());
        String cursor = first.nextCursor();
        String marker = Scenario.uuid();
        IntStream.range(0, 5).forEach(i -> s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("issue", null,
                s.issueFields("row new " + i))));
        IntStream.rangeClosed(1, 5).mapToObj(s::issueId).forEach(id -> s.bean(SaveService.class).save(
                Scenario.AUTHOR, s.edit("issue", id, s.issue(id).version(), Map.of("title", "row " + marker))));
        List<Object> later = new ArrayList<>();
        Page<?> page = first;
        while (page.truncated()) {
            page = call(s, tool, 10, cursor, false);
            later.addAll(page.items());
            cursor = page.nextCursor();
        }
        Set<Object> distinct = new HashSet<>(later);
        assertThat(distinct).as("no duplicate across the later pages").hasSameSizeAs(later);
        assertThat(later).doesNotContainAnyElementsOf(original);
    }

    static Stream<Arguments> everyToolWithEachDamage() {
        return Stream.of(Tool.values()).flatMap(tool -> Stream.of(Damage.values())
                .map(damage -> Arguments.of(tool, damage)));
    }

    @ParameterizedTest(name = "{0} with a {1} cursor")
    @MethodSource("everyToolWithEachDamage")
    @DisplayName("MCR-009: a garbage, a cut or a foreign cursor is refused")
    void mcr009_damagedCursors(Tool tool, Damage damage, LedgerAppContext ctx) {
        Scenario s = fixture(ctx);
        String valid = call(s, tool, 10, null, false).nextCursor();
        String foreign = call(s, tool == Tool.ISSUES ? Tool.TASKS : Tool.ISSUES, 10, null, false).nextCursor();
        String bad = switch (damage) {
            case GARBAGE -> "%%%garbage%%%";
            case CUT -> valid.substring(0, valid.length() / 2);
            case FOREIGN -> foreign;
        };
        ValidationException e = (ValidationException) Expect.failure(() -> call(s, tool, 10, bad, false),
                ValidationCode.VALIDATION_FAILED);
        assertThat(e.issues().get(0).path()).isEqualTo("cursor");
    }

    @ParameterizedTest
    @EnumSource(value = Tool.class, names = {"PROJECTS", "CODES"}, mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("MCR-009: a cursor made for other filters is refused")
    void mcr009_changedFilters(Tool tool, LedgerAppContext ctx) {
        Scenario s = fixture(ctx);
        String cursor = call(s, tool, 10, null, false).nextCursor();
        ValidationException e = (ValidationException) Expect.failure(() -> call(s, tool, 10, cursor, true),
                ValidationCode.VALIDATION_FAILED);
        assertThat(e.issues().get(0).path()).isEqualTo("cursor");
    }
}
