// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.mcp.security;

import java.util.List;
import org.pgledger.application.admin.SessionTokenService;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Authenticates standard per-author HTTP {@code Authorization: Bearer lgt_...} session tokens against
 * {@link SessionTokenService}, binding the resolved {@link org.pgledger.domain.value.AuthorId} as the security
 * principal of the request.
 */
@Component
public final class SessionTokenProvider implements AuthenticationProvider {

    private final SessionTokenService sessionTokens;

    public SessionTokenProvider(SessionTokenService sessionTokens) {
        this.sessionTokens = sessionTokens;
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        BearerTokenAuthenticationToken presented = (BearerTokenAuthenticationToken) authentication;
        String raw = presented.getToken();
        if (raw == null || !raw.startsWith(SessionTokenService.TOKEN_PREFIX)) {
            throw new BadCredentialsException("The bearer token is not valid.");
        }
        return sessionTokens.authenticate(raw)
                .map(token -> (Authentication) UsernamePasswordAuthenticationToken.authenticated(
                        token.authorId(), null, List.of(new SimpleGrantedAuthority("ROLE_AGENT"))))
                .orElseThrow(() -> new BadCredentialsException("The bearer token is not valid."));
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return BearerTokenAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
