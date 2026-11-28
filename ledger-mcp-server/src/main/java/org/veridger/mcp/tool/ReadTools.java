// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.mcp.tool;

import java.util.List;
import org.veridger.application.read.AttentionService;
import org.veridger.application.read.CatalogService;
import org.veridger.application.read.DetailService;
import org.veridger.application.read.GetService;
import org.veridger.application.read.HistoryService;
import org.veridger.application.read.ListService;
import org.veridger.application.read.SearchService;
import org.veridger.application.read.StatsService;
import org.veridger.domain.error.ValidationException;
import org.veridger.domain.error.ValidationReason;
import org.veridger.domain.rule.AttentionKind;
import org.veridger.domain.rule.CodeGroup;
import org.veridger.domain.rule.CriterionState;
import org.veridger.domain.rule.HistoryEntity;
import org.veridger.domain.rule.HistoryOp;
import org.veridger.domain.rule.IssueStatus;
import org.veridger.domain.rule.SpecStatus;
import org.veridger.domain.rule.TaskState;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * The fifteen read tools of the 2.0 universal ledger. No attestation or project grant is required to read; every call
 * runs in a read-only transaction with explicit typed filter arguments and zero raw query strings.
 */
@Component
public class ReadTools implements ToolAdapter {

    private static final int MAX_DETAIL_IDS = 10;

    private final GetService get;
    private final DetailService detail;
    private final HistoryService history;
    private final SearchService search;
    private final ListService lists;
    private final AttentionService attention;
    private final StatsService stats;
    private final CatalogService catalog;

    public ReadTools(GetService get, DetailService detail, HistoryService history, SearchService search,
            ListService lists, AttentionService attention, StatsService stats, CatalogService catalog) {
        this.get = get;
        this.detail = detail;
        this.history = history;
        this.search = search;
        this.lists = lists;
        this.attention = attention;
        this.stats = stats;
        this.catalog = catalog;
    }

    @McpTool(name = "ledger_get", description = "tool.ledger_get", generateOutputSchema = true)
    public ReadOutputs.Got get(@McpToolParam(description = "param.ids", required = true) List<String> ids,
            @McpToolParam(description = "param.include_details", required = false) Boolean include_details) {
        boolean withDetails = Boolean.TRUE.equals(include_details);
        if (withDetails && ids != null && ids.size() > MAX_DETAIL_IDS) {
            throw ValidationException.invalid("ids", ValidationReason.MUST_HOLD_ITEMS, "1",
                    Integer.toString(MAX_DETAIL_IDS));
        }
        GetService.Result result = get.get(ids);
        return ReadOutputs.got(result, withDetails ? detail.details(result.items()) : null);
    }

    @McpTool(name = "ledger_issue", description = "tool.ledger_issue", generateOutputSchema = true)
    public ReadOutputs.Issue issue(@McpToolParam(description = "param.issue_id", required = true) String id) {
        return ReadOutputs.issue(detail.issue(id));
    }

    @McpTool(name = "ledger_spec", description = "tool.ledger_spec", generateOutputSchema = true)
    public ReadOutputs.Spec spec(@McpToolParam(description = "param.spec_id", required = true) String id) {
        return ReadOutputs.spec(detail.spec(id));
    }

    @McpTool(name = "ledger_task", description = "tool.ledger_task", generateOutputSchema = true)
    public ReadOutputs.Task task(@McpToolParam(description = "param.task_id", required = true) String id) {
        return ReadOutputs.task(detail.task(id));
    }

    @McpTool(name = "ledger_criterion", description = "tool.ledger_criterion", generateOutputSchema = true)
    public ReadOutputs.Criterion criterion(
            @McpToolParam(description = "param.criterion_id", required = true) String id) {
        return ReadOutputs.criterion(detail.criterion(id));
    }

    @McpTool(name = "ledger_history", description = "tool.ledger_history", generateOutputSchema = true)
    public ReadOutputs.Items<ReadOutputs.History> history(
            @McpToolParam(description = "param.history_ids", required = false) List<Long> ids,
            @McpToolParam(description = "param.id_from", required = false) Long id_from,
            @McpToolParam(description = "param.id_to", required = false) Long id_to,
            @McpToolParam(description = "param.history_entity", required = false)
            @Choice(from = HistoryEntity.class) String entity,
            @McpToolParam(description = "param.entity_id", required = false) String entity_id,
            @McpToolParam(description = "param.project", required = false) String project,
            @McpToolParam(description = "param.op", required = false) @Choice(from = HistoryOp.class) String op,
            @McpToolParam(description = "param.history_author_id", required = false) String author_id,
            @McpToolParam(description = "param.at_from", required = false) String at_from,
            @McpToolParam(description = "param.at_to", required = false) String at_to,
            @McpToolParam(description = "param.include_before", required = false) Boolean include_before,
            @McpToolParam(description = "param.max_rows", required = false) Integer max_rows,
            @McpToolParam(description = "param.cursor", required = false) String cursor) {
        boolean withBefore = include_before != null && include_before;
        return ReadOutputs.page(history.history(new HistoryService.HistoryQuery(ids, id_from, id_to, entity,
                entity_id, project, op, author_id, at_from, at_to, include_before, max_rows, cursor)),
                row -> ReadOutputs.history(row, withBefore));
    }

    /** Keyword matching mode for {@code ledger_search}. */
    public enum SearchMatch {
        ANY, ALL
    }

    @McpTool(name = "ledger_search", description = "tool.ledger_search", generateOutputSchema = true)
    public ReadOutputs.Items<ReadOutputs.Hit> search(
            @McpToolParam(description = "param.project", required = false) String project,
            @McpToolParam(description = "param.search_kinds", required = false)
            @Choice(from = HistoryEntity.class, only = {"ISSUE", "SPEC", "TASK", "CRITERION"}) List<String> kinds,
            @McpToolParam(description = "param.keywords", required = false) List<String> keywords,
            @McpToolParam(description = "param.match", required = false)
            @Choice(from = SearchMatch.class) String match,
            @McpToolParam(description = "param.exclude_status", required = false) List<String> exclude_status,
            @McpToolParam(description = "param.created_from", required = false) String created_from,
            @McpToolParam(description = "param.created_to", required = false) String created_to,
            @McpToolParam(description = "param.max_rows", required = false) Integer max_rows,
            @McpToolParam(description = "param.cursor", required = false) String cursor) {
        return ReadOutputs.page(search.search(new SearchService.SearchQuery(project, kinds, keywords, match,
                exclude_status, created_from, created_to, max_rows, cursor)), ReadOutputs::hit);
    }

    @McpTool(name = "ledger_issues", description = "tool.ledger_issues", generateOutputSchema = true)
    public ReadOutputs.Items<ReadOutputs.IssueRow> issues(
            @McpToolParam(description = "param.project", required = true) String project,
            @McpToolParam(description = "param.issue_status", required = false)
            @Choice(from = IssueStatus.class) String status,
            @McpToolParam(description = "param.severity", required = false) String severity,
            @McpToolParam(description = "param.category", required = false) String category,
            @McpToolParam(description = "param.attention_kind", required = false) String attention,
            @McpToolParam(description = "param.owner", required = false) String owner,
            @McpToolParam(description = "param.project_version", required = false) String project_version,
            @McpToolParam(description = "param.max_rows", required = false) Integer max_rows,
            @McpToolParam(description = "param.cursor", required = false) String cursor) {
        return ReadOutputs.page(lists.issues(new ListService.IssuesQuery(project, status, severity, category,
                null, null, owner, attention, null, null, max_rows, cursor, project_version)), ReadOutputs::issueRow);
    }

    @McpTool(name = "ledger_specs", description = "tool.ledger_specs", generateOutputSchema = true)
    public ReadOutputs.Items<ReadOutputs.SpecRow> specs(
            @McpToolParam(description = "param.project", required = true) String project,
            @McpToolParam(description = "param.spec_status", required = false)
            @Choice(from = SpecStatus.class) String status,
            @McpToolParam(description = "param.parent_spec_id", required = false) String parent_id,
            @McpToolParam(description = "param.priority_filter", required = false) String priority,
            @McpToolParam(description = "param.owner", required = false) String owner,
            @McpToolParam(description = "param.attention_kind", required = false) String attention,
            @McpToolParam(description = "param.max_rows", required = false) Integer max_rows,
            @McpToolParam(description = "param.cursor", required = false) String cursor) {
        return ReadOutputs.page(lists.specs(new ListService.SpecsQuery(project, status, parent_id, priority,
                owner, attention, max_rows, cursor)), ReadOutputs::specRow);
    }

    @McpTool(name = "ledger_tasks", description = "tool.ledger_tasks", generateOutputSchema = true)
    public ReadOutputs.Items<ReadOutputs.TaskRow> tasks(
            @McpToolParam(description = "param.project", required = true) String project,
            @McpToolParam(description = "param.task_state", required = false)
            @Choice(from = TaskState.class) String state,
            @McpToolParam(description = "param.owner", required = false) String owner,
            @McpToolParam(description = "param.attention_kind", required = false) String attention,
            @McpToolParam(description = "param.max_rows", required = false) Integer max_rows,
            @McpToolParam(description = "param.cursor", required = false) String cursor) {
        return ReadOutputs.page(lists.tasks(new ListService.TasksQuery(project, state, owner, attention, max_rows,
                cursor)), ReadOutputs::taskRow);
    }

    @McpTool(name = "ledger_criteria", description = "tool.ledger_criteria", generateOutputSchema = true)
    public ReadOutputs.Items<ReadOutputs.CriterionRow> criteria(
            @McpToolParam(description = "param.project", required = true) String project,
            @McpToolParam(description = "param.criterion_state", required = false)
            @Choice(from = CriterionState.class) String state,
            @McpToolParam(description = "param.milestone_name", required = false) String milestone_name,
            @McpToolParam(description = "param.max_rows", required = false) Integer max_rows,
            @McpToolParam(description = "param.cursor", required = false) String cursor) {
        return ReadOutputs.page(lists.criteria(new ListService.CriteriaQuery(project, state, milestone_name, max_rows,
                cursor)), ReadOutputs::criterionRow);
    }

    @McpTool(name = "ledger_attention", description = "tool.ledger_attention", generateOutputSchema = true)
    public ReadOutputs.Items<ReadOutputs.AttentionRow> attention(
            @McpToolParam(description = "param.project", required = false) String project,
            @McpToolParam(description = "param.attention_filter", required = false)
            @Choice(from = AttentionKind.class) String kind,
            @McpToolParam(description = "param.include_attention_context", required = false) Boolean include_context,
            @McpToolParam(description = "param.max_rows", required = false) Integer max_rows,
            @McpToolParam(description = "param.cursor", required = false) String cursor) {
        var query = new AttentionService.AttentionQuery(project, kind, max_rows, cursor);
        return Boolean.TRUE.equals(include_context)
                ? ReadOutputs.page(attention.attentionWithContext(query), ReadOutputs::attentionRow)
                : ReadOutputs.page(attention.attention(query), ReadOutputs::attentionRow);
    }

    @McpTool(name = "ledger_stats", description = "tool.ledger_stats", generateOutputSchema = true)
    public ReadOutputs.Stats stats(@McpToolParam(description = "param.project", required = false) String project,
            @McpToolParam(description = "param.bucket", required = false) String bucket) {
        return ReadOutputs.stats(stats.stats(new StatsService.StatsQuery(project, bucket)));
    }

    @McpTool(name = "ledger_projects", description = "tool.ledger_projects", generateOutputSchema = true)
    public ReadOutputs.Items<ReadOutputs.ProjectRow> projects(
            @McpToolParam(description = "param.include_project_versions", required = false) Boolean include_versions,
            @McpToolParam(description = "param.max_rows", required = false) Integer max_rows,
            @McpToolParam(description = "param.cursor", required = false) String cursor) {
        var page = catalog.projects(new CatalogService.ProjectsQuery(max_rows, cursor));
        if (!Boolean.TRUE.equals(include_versions)) {
            return ReadOutputs.page(page, project -> ReadOutputs.projectRow(project, null));
        }
        var versions = catalog.versionsByProject(page.items());
        return ReadOutputs.page(page,
                project -> ReadOutputs.projectRow(project, versions.getOrDefault(project.id(), List.of())));
    }

    @McpTool(name = "ledger_codes", description = "tool.ledger_codes", generateOutputSchema = true)
    public ReadOutputs.Items<ReadOutputs.CodeRow> codes(
            @McpToolParam(description = "param.code_group", required = false)
            @Choice(from = CodeGroup.class) String group,
            @McpToolParam(description = "param.include_inactive", required = false) Boolean include_inactive,
            @McpToolParam(description = "param.max_rows", required = false) Integer max_rows,
            @McpToolParam(description = "param.cursor", required = false) String cursor) {
        return ReadOutputs.page(catalog.codes(new CatalogService.CodesQuery(group, include_inactive, max_rows,
                cursor)), ReadOutputs::codeRow);
    }
}
