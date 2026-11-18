// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.admin.setup;

import java.util.ArrayList;
import java.util.List;
import org.pgledger.admin.app.AdminMode;
import org.pgledger.admin.app.LedgerAdminProperties;
import org.pgledger.admin.app.SignInMethod;
import org.pgledger.domain.error.AdminCode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The login page: the sign-in the owner chose ({@code ledger.admin.sign-in}) and the one-time code of the owner's shell that
 * reaches the registration of a passkey. With the token it has the token form; with OpenID Connect it has one link per
 * registered provider; with a passkey it has the passkey button. In setup mode (no database, so no passkey) it only says how to register the JDBC driver.
 */
@Controller
public class LoginController {

    /** A configured OpenID Connect provider: the registration id (the path of its sign-in) and its display name. */
    public record Provider(String id, String name) {
    }

    private final List<Provider> providers = new ArrayList<>();
    private final boolean passkeySignIn;
    private final boolean tokenSignIn;
    private final boolean enrollment;
    private final boolean setup;

    public LoginController(ObjectProvider<ClientRegistrationRepository> clients, LedgerAdminProperties properties,
            AdminMode mode) {
        this.setup = mode == AdminMode.SETUP;
        boolean oidc = properties.effectiveSignIn() == SignInMethod.OIDC;
        this.passkeySignIn = !setup && properties.effectiveSignIn() == SignInMethod.PASSKEY;
        this.tokenSignIn = !setup && properties.effectiveSignIn() == SignInMethod.TOKEN;
        this.enrollment = !setup;
        if (!setup && oidc && clients.getIfAvailable() instanceof Iterable<?> registrations) {
            for (Object registration : registrations) {
                if (registration instanceof ClientRegistration client) {
                    providers.add(new Provider(client.getRegistrationId(), client.getClientName()));
                }
            }
        }
    }

    @GetMapping("/login")
    String page(@RequestParam(name = "error", required = false) AdminCode error,
            @RequestParam(name = "logout", required = false) String logout, Model model) {
        model.addAttribute("providers", providers);
        model.addAttribute("passkeySignIn", passkeySignIn);
        model.addAttribute("tokenSignIn", tokenSignIn);
        model.addAttribute("enrollment", enrollment);
        model.addAttribute("setupMode", setup);
        model.addAttribute("errorKey", error == null ? null : error.messageKey());
        model.addAttribute("loggedOut", logout != null);
        return "login";
    }
}
