// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.admin.passkey;

import org.pgledger.application.admin.PasskeyService;
import org.pgledger.domain.value.OwnerIdentity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The passkeys of the owner: the live ones (label, fingerprint, registration time), the registration of another one (the
 * ceremonies are the framework's endpoints {@code /webauthn/register/*}, driven by the script of this page; the owner reaches
 * the page with the one-time code of the owner's shell, see {@code EnrollmentTokenService}) and the closing of one. Closing a
 * passkey is an owner write like any other: it needs the approval of a passkey ({@link ApprovalInterceptor}), and the last live
 * passkey is never closed here.
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
    String page(@RequestParam(name = "success", required = false) String success, Model model) {
        model.addAttribute("passkeys", passkeys.live());
        model.addAttribute("rpId", party.rpId());
        model.addAttribute("registered", success != null);
        return "passkeys";
    }

    @PostMapping("/passkeys/revoke")
    String revoke(@RequestParam("credentialId") String credentialId, OwnerIdentity actor) {
        passkeys.revoke(credentialId, actor);
        return "redirect:/passkeys";
    }
}
