// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.admin.console;

import java.util.LinkedHashMap;
import java.util.Map;
import org.pgledger.application.read.DetailService;
import org.pgledger.application.read.ListService;
import org.pgledger.application.read.ListService.CriteriaQuery;
import org.pgledger.application.read.ListService.TasksQuery;
import org.pgledger.application.read.StatsService;
import org.pgledger.application.read.StatsService.StatsQuery;
import org.pgledger.domain.rule.AttentionKind;
import org.pgledger.domain.rule.CriterionState;
import org.pgledger.domain.rule.TaskState;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestParam;

/** The tasks and the milestone criteria of a project (filtered, paged) and the detail of a task. */
@Controller
public class TasksController {

    /** The filters and the paging position of the task list. */
    public record TaskFilter(String project, String state, String owner, String attention, Integer limit,
            String cursor, String back) {
    }

    /** The filters and the paging position of the milestone criteria list. */
    public record CriterionFilter(String project, String state, String milestone, Integer limit, String cursor,
            String back) {
    }

    private final Choices choices;
    private final ListService lists;
    private final DetailService details;
    private final StatsService stats;
    private final Links links;

    public TasksController(Choices choices, ListService lists, DetailService details, StatsService stats, Links links) {
        this.choices = choices;
        this.lists = lists;
        this.details = details;
        this.stats = stats;
        this.links = links;
    }

    @GetMapping("/tasks")
    String tasks(@ModelAttribute("filter") TaskFilter filter, Model model) {
        Choices.ProjectChoice project = choices.project(filter.project());
        model.addAttribute("choice", project);
        model.addAttribute("states", TaskState.values());
        model.addAttribute("attentions", new AttentionKind[] {AttentionKind.TASK_UNKNOWN,
            AttentionKind.DONE_NOT_VERIFIED});
        model.addAttribute("sizes", Paging.SIZES);
        boolean hasFilters = StringUtils.hasText(filter.state()) || StringUtils.hasText(filter.owner())
                || StringUtils.hasText(filter.attention());
        model.addAttribute("hasFilters", hasFilters);
        model.addAttribute("resetHref", links.projectHref("/tasks", project.selected()));
        if (project.selected() != null) {
            var page = lists.tasks(new TasksQuery(project.selected(), filter.state(), filter.owner(),
                    filter.attention(), Paging.limitOf(filter.limit()), filter.cursor()));
            Map<String, String> filters = new LinkedHashMap<>();
            filters.put("project", project.selected());
            filters.put("state", filter.state());
            filters.put("owner", filter.owner());
            filters.put("attention", filter.attention());
            model.addAttribute("page", page);
            model.addAttribute("paging", Paging.of("/tasks", filters, filter.limit(), filter.cursor(),
                    filter.back(), page.nextCursor()));
            model.addAttribute("total", stats.stats(new StatsQuery(project.selected(), null)).tasks());
        }
        return "tasks";
    }

    /**
     * The page of one task. The id is a query parameter and not a path segment: ids are free text and may contain
     * characters (a slash, a number sign, a question mark) that a path segment cannot carry, and the web container
     * refuses an encoded slash.
     */
    @GetMapping("/tasks/detail")
    String task(@RequestParam("id") String id, Model model) {
        model.addAttribute("task", details.task(id));
        return "task";
    }

    @GetMapping("/criteria")
    String criteria(@ModelAttribute("filter") CriterionFilter filter, Model model) {
        Choices.ProjectChoice project = choices.project(filter.project());
        model.addAttribute("choice", project);
        model.addAttribute("states", CriterionState.values());
        model.addAttribute("sizes", Paging.SIZES);
        boolean hasFilters = StringUtils.hasText(filter.state()) || StringUtils.hasText(filter.milestone());
        model.addAttribute("hasFilters", hasFilters);
        model.addAttribute("resetHref", links.projectHref("/criteria", project.selected()));
        if (project.selected() != null) {
            var page = lists.criteria(new CriteriaQuery(project.selected(), filter.state(), filter.milestone(),
                    Paging.limitOf(filter.limit()), filter.cursor()));
            Map<String, String> filters = new LinkedHashMap<>();
            filters.put("project", project.selected());
            filters.put("state", filter.state());
            filters.put("milestone", filter.milestone());
            model.addAttribute("page", page);
            model.addAttribute("paging", Paging.of("/criteria", filters, filter.limit(), filter.cursor(),
                    filter.back(), page.nextCursor()));
            model.addAttribute("total", stats.stats(new StatsQuery(project.selected(), null)).criteria());
        }
        return "criteria";
    }
}
