// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.admin.passkey;

import java.util.Objects;
import org.agentledger.application.admin.PasskeyService;
import org.agentledger.domain.value.OwnerIdentity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The passkeys of the owner: the live ones (label, fingerprint, registration time), the registration of another one (the
 * ceremonies are the framework's endpoints {@code /webauthn/register/*}, driven by the script of this page; the registration is
 * open only to the enrolment session of the one-time code of the owner's shell, see {@code EnrollmentTokenService}, so the page
 * offers the registration to that session and tells any other how to get one) and the closing of one. Closing a
 * passkey requires an authenticated owner session with CSRF protection, and the last live passkey is never closed here.
 */
@Controller
public class PasskeysController {

    private final PasskeyService passkeys;
    private final PasskeyRelyingParty party;

    public PasskeysController(PasskeyService passkeys, PasskeyRelyingParty party) {
        this.passkeys = passkeys;
        this.party = party;
    }

    @GetMapping("/passkeys")
    String page(@RequestParam(name = "success", required = false) String success, Authentication authentication,
            Model model) {
        model.addAttribute("passkeys", passkeys.live());
        model.addAttribute("enrolling", authentication.getAuthorities().stream()
                .anyMatch(granted -> Objects.equals(OwnerAccounts.ENROLL_AUTHORITY, granted.getAuthority())));
        model.addAttribute("rpId", party.rpId());
        model.addAttribute("registered", success != null);
        return "passkeys";
    }

    @PostMapping("/passkeys/revoke")
    String revoke(@RequestParam("credentialId") String credentialId, OwnerIdentity actor, RedirectAttributes flash) {
        passkeys.revoke(credentialId, actor);
        flash.addFlashAttribute("flashKey", "flash.passkeyRevoked");
        return "redirect:/passkeys";
    }
}
