package org.pgledger.admin.console;

import java.util.LinkedHashMap;
import java.util.Map;
import org.pgledger.application.read.DetailService;
import org.pgledger.application.read.ListService;
import org.pgledger.application.read.ListService.IssuesQuery;
import org.pgledger.application.read.StatsService;
import org.pgledger.application.read.StatsService.StatsQuery;
import org.pgledger.domain.rule.AttentionKind;
import org.pgledger.domain.rule.CodeGroup;
import org.pgledger.domain.rule.IssueStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;

/** The issues of a project (filtered, paged) and the detail of one with its deliverables, links, and recent history. */
@Controller
public class IssuesController {

    /** The filters and the paging position of the list, as the query string carries them. */
    public record IssueFilter(String project, String status, String severity, String category, String attention,
            String owner, Integer limit, String cursor, String back) {
    }

    private final Choices choices;
    private final ListService lists;
    private final DetailService details;
    private final StatsService stats;

    public IssuesController(Choices choices, ListService lists, DetailService details, StatsService stats) {
        this.choices = choices;
        this.lists = lists;
        this.details = details;
        this.stats = stats;
    }

    @GetMapping("/issues")
    String list(@ModelAttribute("filter") IssueFilter filter, Model model) {
        Choices.ProjectChoice project = choices.project(filter.project());
        model.addAttribute("choice", project);
        model.addAttribute("statuses", IssueStatus.values());
        model.addAttribute("attentions", new AttentionKind[] {AttentionKind.OPEN_WITH_DELIVERABLE,
            AttentionKind.RESOLVED_WITHOUT_DELIVERABLE, AttentionKind.OPEN_NO_OWNER});
        model.addAttribute("severities", choices.codes(CodeGroup.ISSUE_SEVERITY));
        model.addAttribute("categories", choices.codes(CodeGroup.ISSUE_CATEGORY));
        model.addAttribute("sizes", Paging.SIZES);
        if (project.selected() != null) {
            var page = lists.issues(new IssuesQuery(project.selected(), filter.status(), filter.severity(),
                    filter.category(), filter.owner(), filter.attention(), Paging.limitOf(filter.limit()),
                    filter.cursor()));
            Map<String, String> filters = new LinkedHashMap<>();
            filters.put("project", project.selected());
            filters.put("status", filter.status());
            filters.put("severity", filter.severity());
            filters.put("category", filter.category());
            filters.put("attention", filter.attention());
            filters.put("owner", filter.owner());
            model.addAttribute("page", page);
            model.addAttribute("paging", Paging.of("/issues", filters, filter.limit(), filter.cursor(),
                    filter.back(), page.nextCursor()));
            model.addAttribute("total", stats.stats(new StatsQuery(project.selected(), null)).issues());
        }
        return "issues";
    }

    @GetMapping("/issues/{id}")
    String detail(@PathVariable("id") String id, Model model) {
        model.addAttribute("issue", details.issue(id));
        return "issue";
    }
}
