// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.admin.passkey;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import org.agentledger.admin.app.LedgerAdminProperties;
import org.agentledger.admin.app.SignInMethod;
import org.agentledger.admin.security.SecurityFailures;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Who may use the endpoints of Spring Security's WebAuthn support, decided in front of its filters. Those filters answer their
 * paths before the authorization filter runs, so a rule of {@code authorizeHttpRequests} never reaches them; the two rules of the
 * admin web are therefore kept here:
 *
 * <ul>
 * <li>one sign-in at a time: unless the owner chose passkey sign-in ({@code ledger.admin.sign-in}), the passkey sign-in
 * endpoints ({@code /webauthn/authenticate/**}, {@code /login/webauthn}) are refused with 403;
 * <li>a passkey is registered only in the enrolment session of the one-time code of the owner's shell: the registration
 * endpoints ({@code /webauthn/register/**}) are refused with 403 to a session of the owner and with 401 to nobody signed in.
 * </ul>
 *
 * The filter is in the security chain only, in front of {@link PasskeyLoginGuard}, so a refused sign-in does not count against the
 * budget of the credential checks (its container registration is switched off in {@link PasskeyConfiguration}). The paths are
 * matched as the framework matches them (decoded, within the context path), not by the text of the request URI.
 */
@Component
public final class PasskeyEndpointGate extends OncePerRequestFilter {

    private static final RequestMatcher SIGN_IN = any("/login/webauthn", "/webauthn/authenticate/**");
    private static final RequestMatcher REGISTRATION = any("/webauthn/register/**");

    private final boolean passkeySignIn;
    private final SecurityFailures failures;

    public PasskeyEndpointGate(LedgerAdminProperties properties, SecurityFailures failures) {
        this.passkeySignIn = properties.effectiveSignIn() == SignInMethod.PASSKEY;
        this.failures = failures;
    }

    private static RequestMatcher any(String... patterns) {
        return new OrRequestMatcher(Arrays.stream(patterns)
                .map(pattern -> (RequestMatcher) PathPatternRequestMatcher.withDefaults().matcher(pattern)).toList());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !SIGN_IN.matches(request) && !REGISTRATION.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (SIGN_IN.matches(request) && !passkeySignIn) {
            failures.handle(request, response,
                    new AccessDeniedException("the passkey is not the sign-in of this installation"));
            return;
        }
        if (REGISTRATION.matches(request)) {
            Authentication signedIn = SecurityContextHolder.getContext().getAuthentication();
            if (signedIn == null || signedIn instanceof AnonymousAuthenticationToken) {
                failures.commence(request, response,
                        new AuthenticationCredentialsNotFoundException("nobody is signed in"));
                return;
            }
            if (signedIn.getAuthorities().stream()
                    .noneMatch(granted -> Objects.equals(OwnerAccounts.ENROLL_AUTHORITY, granted.getAuthority()))) {
                failures.handle(request, response,
                        new AccessDeniedException("a passkey is registered in the session of a one-time code only"));
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
