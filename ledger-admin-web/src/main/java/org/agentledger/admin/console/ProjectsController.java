// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.admin.console;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.agentledger.admin.web.AdminHttpExceptionAdvice;
import org.agentledger.application.admin.ProjectService;
import org.agentledger.application.admin.ProjectService.ProjectCommand;
import org.agentledger.domain.value.OwnerIdentity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** The projects: the list and the form that adds one (stamped {@code user:owner} by the service, recorded with the signed-in owner). */
@Controller
public class ProjectsController {

    private final Choices choices;
    private final ProjectService projects;

    public ProjectsController(Choices choices, ProjectService projects) {
        this.choices = choices;
        this.projects = projects;
    }

    @GetMapping("/projects")
    String page(Model model) {
        model.addAttribute("projects", choices.projects());
        return "projects";
    }

    @PostMapping("/projects")
    String add(@ModelAttribute ProjectCommand command, OwnerIdentity actor, HttpServletRequest request,
            RedirectAttributes flash) {
        AdminHttpExceptionAdvice.recoverHtml(request, "projects", () -> {
            Map<String, Object> model = new LinkedHashMap<>();
            model.put("projects", choices.projects());
            model.put("projectForm", command);
            return model;
        });
        projects.add(command, actor);
        flash.addFlashAttribute("flashKey", "flash.projectAdded");
        return "redirect:/projects";
    }
}
