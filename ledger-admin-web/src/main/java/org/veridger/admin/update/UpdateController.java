// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.admin.update;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.veridger.domain.value.OwnerIdentity;

/**
 * Owner-only system update console ({@code /updates}). Displays current and latest Veridger release versions, schema
 * compatibility, host updater status, and a CSRF-protected action to verify and stage an update.
 */
@Controller
public class UpdateController {

    private final UpdateService updates;

    public UpdateController(UpdateService updates) {
        this.updates = updates;
    }

    @GetMapping("/updates")
    String page(Model model) {
        model.addAttribute("update", updates.inspect());
        return "updates";
    }

    @PostMapping("/updates/apply")
    String apply(OwnerIdentity actor, RedirectAttributes flash) {
        UpdateService.ApplyResult result = updates.applyLatest(actor);
        if (result.accepted()) {
            flash.addFlashAttribute("flashKey", result.flashKey());
        } else {
            flash.addFlashAttribute("updateErrorKey", result.flashKey());
        }
        return "redirect:/updates";
    }
}
