// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.admin.console;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.agentledger.admin.web.AdminHttpExceptionAdvice;
import org.agentledger.application.admin.GrantService;
import org.agentledger.application.admin.GrantService.GrantCommand;
import org.agentledger.application.admin.GrantService.RevokeCommand;
import org.agentledger.application.admin.GrantService.SyncCommand;
import org.agentledger.application.admin.GrantService.SyncResult;
import org.agentledger.domain.model.SessionGrant;
import org.agentledger.domain.rule.Scope;
import org.agentledger.domain.value.OwnerIdentity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The session-centric access control console ({@code /grants}): grants, per-project scope synchronization, and
 * revocations by session author ({@code <vendor>:<session_id>}).
 *
 * <p>Every state-changing endpoint requires an authenticated owner session (plus Spring Security CSRF protection) and
 * records the signed-in owner in {@code owner_audit}. No per-session public key or bearer token registration is
 * required before granting access.
 */
@Controller
public class GrantsController {

    /** Issues project grants directly for any valid {@code <vendor>:<session_id>} author. */
    public record AuthorGrantForm(String author, String project, List<String> scopes, String expiresAt, String note) {
    }

    /** Synchronizes the active scopes of one author on one project in a single transaction. */
    public record SyncForm(String author, String project, List<String> scopes, String expiresAt, String note) {
    }

    private final GrantService grants;
    private final Choices choices;

    public GrantsController(GrantService grants, Choices choices) {
        this.grants = grants;
        this.choices = choices;
    }

    @GetMapping("/grants")
    String page(Model model) {
        fill(model);
        return "grants";
    }

    @PostMapping("/grants/author")
    String grantAuthor(@ModelAttribute AuthorGrantForm form, OwnerIdentity actor, HttpServletRequest request,
            RedirectAttributes flash) {
        AdminHttpExceptionAdvice.recoverHtml(request, "grants", () -> recoveryModel("authorGrantForm", form));
        grants.grant(new GrantCommand(form.author(), form.project(), form.scopes(), form.expiresAt(), form.note()),
                actor);
        flash.addFlashAttribute("flashKey", "flash.grantIssued");
        return "redirect:/grants";
    }

    @PostMapping("/grants/sync")
    String sync(@ModelAttribute SyncForm form, OwnerIdentity actor, HttpServletRequest request,
            RedirectAttributes flash) {
        AdminHttpExceptionAdvice.recoverHtml(request, "grants", () -> recoveryModel("syncForm", form));
        SyncResult result = grants.syncProjectScopes(
                new SyncCommand(form.author(), form.project(), form.scopes(), form.expiresAt(), form.note()), actor);
        if (result.added() == 0 && result.revoked() == 0) {
            flash.addFlashAttribute("flashKey", "flash.grantUnchanged");
        } else {
            flash.addFlashAttribute("flashKey", "flash.grantSynced");
        }
        return "redirect:/grants";
    }

    @PostMapping("/grants/revoke")
    String revoke(@ModelAttribute RevokeCommand command, OwnerIdentity actor, HttpServletRequest request,
            RedirectAttributes flash) {
        AdminHttpExceptionAdvice.recoverHtml(request, "grants", () -> recoveryModel("revokeGrantForm", command));
        grants.revoke(command, actor);
        flash.addFlashAttribute("flashKey", "flash.grantRevoked");
        return "redirect:/grants";
    }

    private void fill(Model model) {
        populateCommon(model.asMap());
    }

    private Map<String, Object> recoveryModel(String formKey, Object formValue) {
        Map<String, Object> model = new LinkedHashMap<>();
        populateCommon(model);
        model.put(formKey, formValue);
        return model;
    }

    private void populateCommon(Map<String, Object> target) {
        List<SessionGrant> liveGrants = grants.liveAll();
        Choices.ProjectChoice choice = choices.project(null);
        List<String> projectIds = choice.projects().stream().map(p -> p.id()).toList();
        List<SessionAccessView.SessionCard> sessions = SessionAccessView.build(liveGrants, projectIds);

        target.put("grants", liveGrants);
        target.put("sessions", sessions);
        target.put("knownAuthors", SessionAccessView.knownAuthors(sessions));
        target.put("choice", choice);
        target.put("scopes", Scope.values());
    }
}
