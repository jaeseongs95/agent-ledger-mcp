// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.admin.console;

import org.springframework.security.authentication.ott.OneTimeTokenAuthentication;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.webauthn.authentication.WebAuthnAuthentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The read-only JSON of the admin: who is signed in and the overview. A client reads with the admin token as
 * {@code Authorization: Bearer}; the browser pages use the session. There is no endpoint that changes the ledger.
 */
@RestController
public class ApiController {

    /** How the owner signed in. */
    public enum SignIn {
        TOKEN, OIDC, PASSKEY, CODE
    }

    public record SessionView(boolean authenticated, String name, SignIn signIn) {
    }

    private final OverviewService overview;

    public ApiController(OverviewService overview) {
        this.overview = overview;
    }

    @GetMapping("/api/session")
    SessionView session(Authentication authentication) {
        SignIn signIn = switch (authentication) {
            case OAuth2AuthenticationToken oidc -> SignIn.OIDC;
            case WebAuthnAuthentication passkey -> SignIn.PASSKEY;
            case OneTimeTokenAuthentication code -> SignIn.CODE;
            default -> SignIn.TOKEN;
        };
        return new SessionView(authentication.isAuthenticated(), authentication.getName(), signIn);
    }

    @GetMapping("/api/overview")
    OverviewService.Overview overview(@RequestParam(name = "project", required = false) String project) {
        return overview.overview(project);
    }
}
