package org.pgledger.admin.security;

import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import org.pgledger.admin.app.LedgerAdminProperties;
import org.pgledger.domain.error.AdminCode;
import org.pgledger.domain.value.Sha256;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * The one provider of the admin token: the {@code Authorization: Bearer} header of the read-only JSON endpoints and, only when
 * {@code ledger.admin.sign-in=token}, the login form of the application's own page end here. Presented and expected tokens are
 * hashed first (the shared SHA-256 of the domain) and the digests are
 * compared with {@link MessageDigest#isEqual}, which takes the same time whatever the input is. The token of the
 * MCP agents is refused with its own code. When the global budget of checks is used up the comparison is delayed, never
 * refused: no number of wrong tokens keeps the right one out (see {@link LoginAttempts}). No token is ever written to a
 * message or a log.
 */
@Component
public final class AdminTokenProvider implements AuthenticationProvider {

    /** The authority of a signed-in owner (token form, OpenID Connect or passkey): the one place the name is written. */
    public static final String OWNER_AUTHORITY = "ROLE_OWNER";

    /**
     * The authority of the admin token as a bearer header: it reads the JSON endpoints under {@code /api} and nothing else, and
     * approves no change (the approval is a passkey), so it is not the owner.
     */
    public static final String API_READ_AUTHORITY = "ROLE_API_READ";

    /** The shortest token that is accepted as the secret of the owner. */
    static final int MIN_TOKEN_LENGTH = LedgerAdminProperties.MIN_TOKEN_LENGTH;

    private final byte[] expected;
    private final byte[] agent;
    private final LoginAttempts attempts;
    private final LoginDelay delays;

    /**
     * The agent token is required and must differ from the admin token (the binding of {@link LedgerAdminProperties}
     * stops the start otherwise); the checks here guard a provider made without that binding.
     *
     * @throws IllegalArgumentException when the token is missing, blank or too short, or the agent token is missing or
     *         equals it; the message names the key and never the value
     */
    public AdminTokenProvider(LedgerAdminProperties properties, LoginAttempts attempts, LoginDelay delays) {
        String token = properties.token();
        if (token == null || token.isBlank() || token.length() < MIN_TOKEN_LENGTH) {
            throw new IllegalArgumentException("ledger.admin.token must be set to at least " + MIN_TOKEN_LENGTH
                    + " characters");
        }
        this.expected = digest(token);
        String agentToken = properties.agentToken();
        if (agentToken == null || agentToken.isBlank()) {
            throw new IllegalArgumentException("ledger.admin.agent-token must be set");
        }
        this.agent = digest(agentToken);
        if (MessageDigest.isEqual(agent, expected)) {
            throw new IllegalArgumentException("ledger.admin.agent-token must differ from ledger.admin.token");
        }
        this.attempts = attempts;
        this.delays = delays;
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        slowDown(attempts.admit());
        byte[] presented = digest(presentedToken(authentication));
        if (MessageDigest.isEqual(agent, presented)) {
            throw new AdminAuthenticationException(AdminCode.AGENT_TOKEN_FORBIDDEN);
        }
        if (!MessageDigest.isEqual(expected, presented)) {
            throw new AdminAuthenticationException(AdminCode.UNAUTHORIZED);
        }
        attempts.succeeded();
        // the header reads the JSON endpoints only; the login form (ledger.admin.sign-in=token) is the owner, whose writes
        // still need the approval of a passkey
        String authority = authentication instanceof BearerTokenAuthenticationToken ? API_READ_AUTHORITY : OWNER_AUTHORITY;
        return UsernamePasswordAuthenticationToken.authenticated("owner", null,
                List.of(new SimpleGrantedAuthority(authority)));
    }

    /**
     * Waits before the token is compared when the global budget of checks is used up (see {@link LoginAttempts}): the
     * check is slowed down and still made, so the owner with the right token logs in, only later. A wait that is
     * interrupted (the server is stopping) restores the interrupt and refuses the attempt without comparing, since the
     * delay it stands for did not happen. The wait is the injected {@link LoginDelay}.
     */
    private void slowDown(Duration delay) {
        try {
            delays.await(delay);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AdminAuthenticationException(AdminCode.TOO_MANY_AUTH_FAILURES, interrupted);
        }
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return BearerTokenAuthenticationToken.class.isAssignableFrom(authentication)
                || UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }

    private static String presentedToken(Authentication authentication) {
        return authentication instanceof BearerTokenAuthenticationToken bearer ? bearer.getToken()
                : String.valueOf(authentication.getCredentials());
    }

    private static byte[] digest(String token) {
        return HexFormat.of().parseHex(Sha256.ofText(token).hex());
    }
}
