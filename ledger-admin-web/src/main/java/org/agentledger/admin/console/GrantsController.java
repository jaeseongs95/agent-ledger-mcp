// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.admin.console;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.agentledger.admin.web.AdminHttpExceptionAdvice;
import org.agentledger.admin.web.FormErrors;
import org.agentledger.application.admin.GrantService;
import org.agentledger.application.admin.GrantService.GrantCommand;
import org.agentledger.application.admin.GrantService.IssuedTokenWithGrants;
import org.agentledger.application.admin.GrantService.RevokeCommand;
import org.agentledger.application.admin.GrantService.SyncCommand;
import org.agentledger.application.admin.GrantService.SyncResult;
import org.agentledger.application.admin.SessionKeyService;
import org.agentledger.application.admin.SessionKeyService.PresentedKey;
import org.agentledger.application.admin.SessionTokenService;
import org.agentledger.application.admin.SessionTokenService.IssueCommand;
import org.agentledger.domain.error.ValidationException;
import org.agentledger.domain.error.ValidationReason;
import org.agentledger.domain.model.SessionGrant;
import org.agentledger.domain.model.SessionPublicKey;
import org.agentledger.domain.model.SessionToken;
import org.agentledger.domain.rule.Scope;
import org.agentledger.domain.value.OwnerIdentity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The session-centric access control console ({@code /grants}): live session grants, registered Ed25519 session keys,
 * and HTTP Bearer session tokens.
 *
 * <p>All state-changing endpoints require WebAuthn passkey approval via {@code ApprovalInterceptor} and record the
 * signed-in owner in {@code owner_audit}. Existing form endpoints remain fully supported alongside {@code /grants/sync}
 * (single-transaction per-project scope sync) and optional initial project grants during bearer token issuance.
 */
@Controller
public class GrantsController {

    private static final String CONNECT_TAB_TOKEN = "connect-tab-token";
    private static final String CONNECT_TAB_KEY = "connect-tab-key";

    /** The issue form with the pasted public key (the key is required: the grant screen registers it). */
    public record GrantForm(String author, String project, List<String> scopes, String expiresAt, String note,
            @NotNull(message = FormErrors.REQUIRED) String publicKey) {
    }

    /**
     * What the confirmation adds: the fingerprint that the owner saw and compared (and the box that says so), and for a
     * replacement the fingerprint of the key that is replaced.
     */
    @ComparedFingerprint
    public record Confirmation(String fingerprint, String confirmFingerprint, String replace,
            String replaceFingerprint) {
    }

    /**
     * What the owner compares before confirming: the fingerprint of the pasted key, whether the author has that key
     * already. The key the author has now, when it is another one, is the model attribute {@code currentKey}.
     */
    public record Preview(GrantForm form, String fingerprint, String server, boolean alreadyRegistered) {
    }

    /** The form that closes a key: the author and the fingerprint of its live key, confirmed by the owner. */
    public record KeyRevokeForm(String author, String fingerprint) {
    }

    /** Issues grants for an author that already holds a live session key or bearer session token. */
    public record AuthorGrantForm(String author, String project, List<String> scopes, String expiresAt, String note) {
    }

    /** Synchronizes the active scopes of one author on one project in a single passkey-approved submission. */
    public record SyncForm(String author, String project, List<String> scopes, String expiresAt, String note) {
    }

    /**
     * Issues a standard HTTP Bearer session token for an author; the raw secret is shown once in the response. Optional
     * {@code project} and {@code scopes} allow granting initial permissions in the same transaction.
     */
    public record TokenIssueForm(String author, String expiresAt, String note, String project, List<String> scopes) {
    }

    /** Revokes a standard HTTP Bearer session token by numeric id. */
    public record TokenRevokeForm(@NotNull(message = FormErrors.REQUIRED) Long id) {
    }

    private final GrantService grants;
    private final SessionKeyService keys;
    private final SessionTokenService tokens;
    private final Choices choices;
    private final FormErrors forms;

    public GrantsController(GrantService grants, SessionKeyService keys, SessionTokenService tokens, Choices choices,
            FormErrors forms) {
        this.grants = grants;
        this.keys = keys;
        this.tokens = tokens;
        this.choices = choices;
        this.forms = forms;
    }

    @GetMapping("/grants")
    String page(Model model) {
        fill(model);
        return "grants";
    }

    /**
     * Shows the fingerprint of the pasted key (and the key the author has now) before anything is stored. The owner may
     * paste the public key alone or the block that the hook prints; for a block the server it was made for is shown next
     * to the fingerprint, and the block must be for the author of the form.
     */
    @PostMapping("/grants/preview")
    String preview(@Valid @ModelAttribute GrantForm form, BindingResult formErrors, Model model,
            HttpServletRequest request) {
        AdminHttpExceptionAdvice.recoverHtml(request, "grants",
                () -> recoveryModel(CONNECT_TAB_KEY, "grantForm", form));
        forms.raiseIfAny(formErrors);
        PresentedKey presented = keys.present(form.author(), form.publicKey());
        Optional<SessionPublicKey> current = keys.live(form.author());
        boolean alreadyRegistered = current.map(live -> Objects.equals(live.fingerprint(), presented.fingerprint()))
                .orElse(false);
        fill(model);
        current.filter(live -> !Objects.equals(live.fingerprint(), presented.fingerprint()))
                .ifPresent(other -> model.addAttribute("currentKey", other));
        GrantForm shown = new GrantForm(form.author(), form.project(), form.scopes(), form.expiresAt(), form.note(),
                presented.publicKey());
        model.addAttribute("preview", new Preview(shown, presented.fingerprint(), presented.server(),
                alreadyRegistered));
        model.addAttribute("activeConnectTab", CONNECT_TAB_KEY);
        return "grants";
    }

    @PostMapping("/grants")
    String grant(@Valid @ModelAttribute GrantForm form, BindingResult formErrors,
            @Valid @ModelAttribute Confirmation confirmation, BindingResult confirmationErrors, OwnerIdentity actor,
            HttpServletRequest request, RedirectAttributes flash) {
        AdminHttpExceptionAdvice.recoverHtml(request, "grants",
                () -> recoveryModel(CONNECT_TAB_KEY, "grantForm", form));
        forms.raiseIfAny(formErrors);
        PresentedKey presented = keys.present(form.author(), form.publicKey());
        forms.raiseIfAny(confirmationErrors);
        requireTheKeysFingerprint(confirmation, presented.fingerprint());
        GrantCommand command = new GrantCommand(form.author(), form.project(), form.scopes(), form.expiresAt(),
                form.note());
        if (confirmation.replace() == null) {
            grants.grantWithKey(command, presented.publicKey(), actor);
        } else {
            grants.grantReplacingKey(command, presented.publicKey(), confirmation.replaceFingerprint(), actor);
        }
        flash.addFlashAttribute("flashKey", "flash.grantIssued");
        return "redirect:/grants";
    }

    @PostMapping("/grants/author")
    String grantAuthor(@ModelAttribute AuthorGrantForm form, OwnerIdentity actor, HttpServletRequest request,
            RedirectAttributes flash) {
        AdminHttpExceptionAdvice.recoverHtml(request, "grants",
                () -> recoveryModel(CONNECT_TAB_TOKEN, "authorGrantForm", form));
        grants.grant(new GrantCommand(form.author(), form.project(), form.scopes(), form.expiresAt(), form.note()),
                actor);
        flash.addFlashAttribute("flashKey", "flash.grantIssued");
        return "redirect:/grants";
    }

    @PostMapping("/grants/sync")
    String sync(@ModelAttribute SyncForm form, OwnerIdentity actor, HttpServletRequest request,
            RedirectAttributes flash) {
        AdminHttpExceptionAdvice.recoverHtml(request, "grants",
                () -> recoveryModel(CONNECT_TAB_TOKEN, "syncForm", form));
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
        AdminHttpExceptionAdvice.recoverHtml(request, "grants",
                () -> recoveryModel(CONNECT_TAB_TOKEN, "revokeGrantForm", command));
        grants.revoke(command, actor);
        flash.addFlashAttribute("flashKey", "flash.grantRevoked");
        return "redirect:/grants";
    }

    @PostMapping("/grants/keys/revoke")
    String revokeKey(@ModelAttribute KeyRevokeForm form, OwnerIdentity actor, HttpServletRequest request,
            RedirectAttributes flash) {
        AdminHttpExceptionAdvice.recoverHtml(request, "grants",
                () -> recoveryModel(CONNECT_TAB_KEY, "keyRevokeForm", form));
        keys.revokeConfirmed(form.author(), form.fingerprint(), actor);
        flash.addFlashAttribute("flashKey", "flash.keyRevoked");
        return "redirect:/grants";
    }

    @PostMapping("/grants/tokens")
    String issueToken(@ModelAttribute TokenIssueForm form, OwnerIdentity actor, Model model,
            HttpServletRequest request) {
        AdminHttpExceptionAdvice.recoverHtml(request, "grants",
                () -> recoveryModel(CONNECT_TAB_TOKEN, "tokenIssueForm", form));
        IssuedTokenWithGrants result = grants.issueTokenWithOptionalGrants(
                new IssueCommand(form.author(), form.expiresAt(), form.note()), form.project(), form.scopes(), actor);
        fill(model);
        model.addAttribute("issuedToken", result.token());
        if (result.project() != null && !result.scopes().isEmpty()) {
            model.addAttribute("issuedTokenProject", result.project());
            model.addAttribute("issuedTokenScopes", result.scopes());
        }
        model.addAttribute("activeConnectTab", CONNECT_TAB_TOKEN);
        return "grants";
    }

    @PostMapping("/grants/tokens/revoke")
    String revokeToken(@Valid @ModelAttribute TokenRevokeForm form, BindingResult formErrors, OwnerIdentity actor,
            HttpServletRequest request, RedirectAttributes flash) {
        AdminHttpExceptionAdvice.recoverHtml(request, "grants",
                () -> recoveryModel(CONNECT_TAB_TOKEN, "tokenRevokeForm", form));
        forms.raiseIfAny(formErrors);
        tokens.revoke(form.id(), actor);
        flash.addFlashAttribute("flashKey", "flash.tokenRevoked");
        return "redirect:/grants";
    }

    private void fill(Model model) {
        populateCommon(model.asMap());
    }

    private Map<String, Object> recoveryModel(String activeConnectTab, String formKey, Object formValue) {
        Map<String, Object> model = new LinkedHashMap<>();
        populateCommon(model);
        model.put("activeConnectTab", activeConnectTab);
        model.put(formKey, formValue);
        return model;
    }

    private void populateCommon(Map<String, Object> target) {
        List<SessionGrant> liveGrants = grants.liveAll();
        List<SessionPublicKey> liveKeys = keys.liveKeys();
        List<SessionToken> unrevokedTokens = tokens.listUnrevoked();
        Choices.ProjectChoice choice = choices.project(null);
        List<String> projectIds = choice.projects().stream().map(p -> p.id()).toList();
        List<SessionAccessView.SessionCard> sessions = SessionAccessView.build(liveGrants, liveKeys, unrevokedTokens,
                projectIds);

        target.put("grants", liveGrants);
        target.put("keys", liveKeys);
        target.put("tokens", unrevokedTokens);
        target.put("sessions", sessions);
        target.put("enrolledAuthors", SessionAccessView.enrolledAuthors(sessions));
        target.put("choice", choice);
        target.put("scopes", Scope.values());
    }

    /** The owner repeated the fingerprint of the pasted key (letters of either case): compared with the computed one. */
    private static void requireTheKeysFingerprint(Confirmation confirmation, String fingerprint) {
        if (!Objects.equals(fingerprint, confirmation.fingerprint().toLowerCase(Locale.ROOT))) {
            throw ValidationException.invalid("fingerprint", ValidationReason.MUST_MATCH, fingerprint);
        }
    }
}
