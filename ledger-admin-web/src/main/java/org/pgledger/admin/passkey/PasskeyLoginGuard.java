package org.pgledger.admin.passkey;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import org.pgledger.admin.security.AdminAuthenticationException;
import org.pgledger.admin.security.LoginAttempts;
import org.pgledger.admin.security.LoginDelay;
import org.pgledger.admin.security.SecurityFailures;
import org.pgledger.domain.error.AdminCode;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The lock after failed attempts for the passkey sign-in ({@code POST /login/webauthn}, whose filter belongs to Spring
 * Security and has no lock of its own): the attempt is counted before the assertion is looked at, a locked client is refused
 * with {@code TOO_MANY_AUTH_FAILURES} (the same {@link LoginAttempts} as the admin token and the enrolment code, so the limits
 * and the global throttle are shared), and a successful sign-in gives the count back. The filter is in the security chain only
 * (its container registration is switched off in {@link PasskeyConfiguration}), after the CSRF check and before the sign-in
 * filters.
 */
@Component
public final class PasskeyLoginGuard extends OncePerRequestFilter {

    static final String LOGIN_PATH = "/login/webauthn";

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
        return !"POST".equals(request.getMethod())
                || !Objects.equals(request.getContextPath() + LOGIN_PATH, request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String client = request.getRemoteAddr();
        LoginAttempts.Admission admission = attempts.admit(client);
        if (!admission.allowed()) {
            failures.onAuthenticationFailure(request, response,
                    new AdminAuthenticationException(AdminCode.TOO_MANY_AUTH_FAILURES));
            return;
        }
        try {
            delays.await(admission.delay());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            failures.onAuthenticationFailure(request, response,
                    new AdminAuthenticationException(AdminCode.TOO_MANY_AUTH_FAILURES, interrupted));
            return;
        }
        chain.doFilter(request, response);
        if (response.getStatus() < HttpServletResponse.SC_BAD_REQUEST) {
            attempts.succeeded(client);
        }
    }
}
