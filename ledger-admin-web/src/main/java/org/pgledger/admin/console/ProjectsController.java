// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.admin.console;

import org.pgledger.application.admin.ProjectService;
import org.pgledger.application.admin.ProjectService.ProjectCommand;
import org.pgledger.domain.value.OwnerIdentity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;

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
    String add(@ModelAttribute ProjectCommand command, OwnerIdentity actor) {
        projects.add(command, actor);
        return "redirect:/projects";
    }
}
