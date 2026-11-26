// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.admin.console;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.pgledger.admin.web.AdminHttpExceptionAdvice;
import org.pgledger.admin.web.FormErrors;
import org.pgledger.application.admin.GrantService;
import org.pgledger.application.admin.GrantService.GrantCommand;
import org.pgledger.application.admin.GrantService.RevokeCommand;
import org.pgledger.application.admin.SessionKeyService;
import org.pgledger.application.admin.SessionKeyService.PresentedKey;
import org.pgledger.application.admin.SessionTokenService;
import org.pgledger.application.admin.SessionTokenService.IssueCommand;
import org.pgledger.application.admin.SessionTokenService.IssuedToken;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationReason;
import org.pgledger.domain.model.SessionPublicKey;
import org.pgledger.domain.rule.Scope;
import org.pgledger.domain.value.OwnerIdentity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The live session grants, registered session keys, and HTTP Bearer session tokens.
 *
 * <p>Attested sessions register an Ed25519 public key together with their grants ({@link GrantService#grantWithKey})
 * after comparing the fingerprint on the preview screen. Standard HTTP Bearer sessions receive a one-time session
 * token ({@link SessionTokenService#issue}) and may be granted scopes on projects ({@link GrantService#grant}). Every
 * operation is recorded with the signed-in owner.
 */
@Controller
public class GrantsController {

    private static final String TAB_GRANTS = "tab-btn-grants";
    private static final String TAB_TOKENS = "tab-btn-tokens";
    private static final String TAB_KEYS = "tab-btn-keys";

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

    /** The form that closes a key: the author and the fingerprint of its live key, typed by the owner. */
    public record KeyRevokeForm(String author, String fingerprint) {
    }

    /** Issues grants for an author that already holds a live session key or bearer session token. */
    public record AuthorGrantForm(String author, String project, List<String> scopes, String expiresAt, String note) {
    }

    /** Issues a standard HTTP Bearer session token for an author; the raw secret is shown once in the response. */
    public record TokenIssueForm(String author, String expiresAt, String note) {
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
        AdminHttpExceptionAdvice.recoverHtml(request, "grants", () -> recoveryModel(TAB_GRANTS, "grantForm", form));
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
        model.addAttribute("activeTab", TAB_GRANTS);
        return "grants";
    }

    @PostMapping("/grants")
    String grant(@Valid @ModelAttribute GrantForm form, BindingResult formErrors,
            @Valid @ModelAttribute Confirmation confirmation, BindingResult confirmationErrors, OwnerIdentity actor,
            HttpServletRequest request, RedirectAttributes flash) {
        AdminHttpExceptionAdvice.recoverHtml(request, "grants", () -> recoveryModel(TAB_GRANTS, "grantForm", form));
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
                () -> recoveryModel(TAB_GRANTS, "authorGrantForm", form));
        grants.grant(new GrantCommand(form.author(), form.project(), form.scopes(), form.expiresAt(), form.note()),
                actor);
        flash.addFlashAttribute("flashKey", "flash.grantIssued");
        return "redirect:/grants";
    }

    @PostMapping("/grants/revoke")
    String revoke(@ModelAttribute RevokeCommand command, OwnerIdentity actor, HttpServletRequest request,
            RedirectAttributes flash) {
        AdminHttpExceptionAdvice.recoverHtml(request, "grants",
                () -> recoveryModel(TAB_GRANTS, "revokeGrantForm", command));
        grants.revoke(command, actor);
        flash.addFlashAttribute("flashKey", "flash.grantRevoked");
        return "redirect:/grants";
    }

    @PostMapping("/grants/keys/revoke")
    String revokeKey(@ModelAttribute KeyRevokeForm form, OwnerIdentity actor, HttpServletRequest request,
            RedirectAttributes flash) {
        AdminHttpExceptionAdvice.recoverHtml(request, "grants",
                () -> recoveryModel(TAB_KEYS, "keyRevokeForm", form));
        keys.revokeConfirmed(form.author(), form.fingerprint(), actor);
        flash.addFlashAttribute("flashKey", "flash.keyRevoked");
        return "redirect:/grants#tab-keys";
    }

    @PostMapping("/grants/tokens")
    String issueToken(@ModelAttribute TokenIssueForm form, OwnerIdentity actor, Model model,
            HttpServletRequest request) {
        AdminHttpExceptionAdvice.recoverHtml(request, "grants",
                () -> recoveryModel(TAB_TOKENS, "tokenIssueForm", form));
        IssuedToken issued = tokens.issue(new IssueCommand(form.author(), form.expiresAt(), form.note()), actor);
        fill(model);
        model.addAttribute("issuedToken", issued);
        model.addAttribute("activeTab", TAB_TOKENS);
        return "grants";
    }

    @PostMapping("/grants/tokens/revoke")
    String revokeToken(@Valid @ModelAttribute TokenRevokeForm form, BindingResult formErrors, OwnerIdentity actor,
            HttpServletRequest request, RedirectAttributes flash) {
        AdminHttpExceptionAdvice.recoverHtml(request, "grants",
                () -> recoveryModel(TAB_TOKENS, "tokenRevokeForm", form));
        forms.raiseIfAny(formErrors);
        tokens.revoke(form.id(), actor);
        flash.addFlashAttribute("flashKey", "flash.tokenRevoked");
        return "redirect:/grants#tab-tokens";
    }

    private void fill(Model model) {
        model.addAttribute("grants", grants.liveAll());
        model.addAttribute("keys", keys.liveKeys());
        model.addAttribute("tokens", tokens.listUnrevoked());
        model.addAttribute("choice", choices.project(null));
        model.addAttribute("scopes", Scope.values());
    }

    private Map<String, Object> recoveryModel(String activeTab, String formKey, Object formValue) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("grants", grants.liveAll());
        model.put("keys", keys.liveKeys());
        model.put("tokens", tokens.listUnrevoked());
        model.put("choice", choices.project(null));
        model.put("scopes", Scope.values());
        model.put("activeTab", activeTab);
        model.put(formKey, formValue);
        return model;
    }

    /** The owner repeated the fingerprint of the pasted key (letters of either case): compared with the computed one. */
    private static void requireTheKeysFingerprint(Confirmation confirmation, String fingerprint) {
        if (!Objects.equals(fingerprint, confirmation.fingerprint().toLowerCase(Locale.ROOT))) {
            throw ValidationException.invalid("fingerprint", ValidationReason.MUST_MATCH, fingerprint);
        }
    }
}
