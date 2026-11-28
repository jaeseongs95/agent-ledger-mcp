// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.admin.console;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.veridger.admin.web.AdminHttpExceptionAdvice;
import org.veridger.application.admin.ProjectVersionService;
import org.veridger.application.admin.ProjectVersionService.ItemForm;
import org.veridger.domain.rule.CodeGroup;
import org.veridger.domain.rule.HistoryEntity;
import org.veridger.domain.value.OwnerIdentity;
import org.veridger.domain.value.Tokens;

@Controller
@RequestMapping("/projects/versions/{versionId}")
public class VersionWorkspaceController {
    private static final String VIEW = "version-workspace";
    private static final HistoryEntity[] KINDS = {
        HistoryEntity.SPEC, HistoryEntity.ISSUE, HistoryEntity.TASK, HistoryEntity.CRITERION
    };
    private static final int PAGE_SIZE = 25;

    private final Choices choices;
    private final ProjectVersionService versions;
    private final Links links;

    public VersionWorkspaceController(Choices choices, ProjectVersionService versions, Links links) {
        this.choices = choices;
        this.versions = versions;
        this.links = links;
    }

    @GetMapping
    String view(@PathVariable String versionId, @RequestParam String project,
            @RequestParam(defaultValue = "spec") String kind, @RequestParam(required = false) String after,
            Model model) {
        model.addAllAttributes(workspace(project, versionId, kind(kind), after));
        return VIEW;
    }

    @PostMapping("/items")
    String create(@PathVariable String versionId, @RequestParam String project, @RequestParam String kind,
            @ModelAttribute ItemForm form, OwnerIdentity owner, HttpServletRequest request, RedirectAttributes redirect) {
        HistoryEntity entity = kind(kind);
        AdminHttpExceptionAdvice.recoverHtml(request, VIEW, () -> {
            var model = workspace(project, versionId, entity, null);
            model.put("form", form);
            model.put("creating", true);
            return model;
        });
        versions.createItem(owner, project, versionId, entity, form);
        return saved(project, versionId, kind, "versions.itemSaved", redirect);
    }

    @PostMapping("/relationships")
    String relationship(@PathVariable String versionId, @RequestParam String project, @RequestParam String kind,
            @RequestParam String itemId, @RequestParam(defaultValue = "false") boolean remove,
            HttpServletRequest request, RedirectAttributes redirect) {
        HistoryEntity entity = kind(kind);
        AdminHttpExceptionAdvice.recoverHtml(request, VIEW, () -> {
            var model = workspace(project, versionId, entity, null);
            model.put("itemId", itemId);
            return model;
        });
        versions.changeItemLink(project, versionId, entity, itemId, !remove);
        return saved(project, versionId, kind, "versions.relationshipSaved", redirect);
    }

    private Map<String, Object> workspace(String project, String versionId, HistoryEntity kind, String after) {
        var result = versions.items(project, versionId, kind, after, PAGE_SIZE);
        var model = new LinkedHashMap<String, Object>();
        model.put("project", project);
        model.put("projectName", choices.projects().stream().filter(p -> Objects.equals(p.id(), project))
                .map(p -> p.displayName()).findFirst().orElse(project));
        model.put("workspace", result);
        model.put("itemPage", links.itemPage(kind.token()));
        model.put("kind", kind.token());
        model.put("kinds", List.of(KINDS));
        model.put("after", after);
        model.put("operationKey", UUID.randomUUID().toString());
        if (kind == HistoryEntity.ISSUE) {
            model.put("categories", choices.codes(CodeGroup.ISSUE_CATEGORY));
            model.put("severities", choices.codes(CodeGroup.ISSUE_SEVERITY));
        }
        return model;
    }

    private static HistoryEntity kind(String token) {
        return Tokens.parse("entity", token, KINDS);
    }

    private static String saved(String project, String versionId, String kind, String message, RedirectAttributes redirect) {
        redirect.addAttribute("versionId", versionId);
        redirect.addAttribute("project", project);
        redirect.addAttribute("kind", kind);
        redirect.addFlashAttribute("flashKey", message);
        return "redirect:/projects/versions/{versionId}";
    }
}
