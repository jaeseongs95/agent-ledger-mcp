// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.admin.passkey;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.pgledger.admin.security.AdminAuthenticationException;
import org.pgledger.admin.security.LoginAttempts;
import org.pgledger.admin.security.LoginDelay;
import org.pgledger.admin.security.SecurityFailures;
import org.pgledger.domain.error.AdminCode;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The throttle of the passkey sign-in ({@code POST /login/webauthn}, whose filter belongs to Spring Security and has no limit
 * of its own): the attempt is counted before the assertion is looked at and delayed beyond the global budget, never refused (the
 * same {@link LoginAttempts} as the admin token and the enrolment code, so the budget is shared), and a successful sign-in
 * gives the count back. A wait that is interrupted refuses the attempt with {@code TOO_MANY_AUTH_FAILURES}. The filter is in the security chain only
 * (its container registration is switched off in {@link PasskeyConfiguration}), after the CSRF check and before the sign-in
 * filters.
 */
@Component
public final class PasskeyLoginGuard extends OncePerRequestFilter {

    /** The sign-in as the framework matches it (decoded, within the context path), not as the text of the request reads. */
    private static final RequestMatcher LOGIN = PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST,
            "/login/webauthn");

    private final LoginAttempts attempts;
    private final LoginDelay delays;
    private final SecurityFailures failures;

    public PasskeyLoginGuard(LoginAttempts attempts, LoginDelay delays, SecurityFailures failures) {
        this.attempts = attempts;
        this.delays = delays;
        this.failures = failures;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !LOGIN.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            delays.await(attempts.admit());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            failures.onAuthenticationFailure(request, response,
                    new AdminAuthenticationException(AdminCode.TOO_MANY_AUTH_FAILURES, interrupted));
            return;
        }
        chain.doFilter(request, response);
        if (response.getStatus() < HttpServletResponse.SC_BAD_REQUEST) {
            attempts.succeeded();
        }
    }
}
