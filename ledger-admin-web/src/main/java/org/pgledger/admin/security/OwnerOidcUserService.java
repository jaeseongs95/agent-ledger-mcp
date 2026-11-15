package org.pgledger.admin.security;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.pgledger.admin.app.LedgerAdminProperties;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Who may sign in with OpenID Connect: the owner is <em>one e-mail address</em> ({@code ledger.admin.oidc.owner-email}) that the
 * provider verified, at <em>one issuer</em> ({@code ledger.admin.oidc.issuer}). Spring Security has already validated the ID
 * token (issuer, audience, nonce, expiry, signature) and the {@code state} when this is called; this service only decides who
 * the owner is. An address is only meaningful inside the issuer that verified it (another provider can hand out the same address
 * to somebody else), so the address counts for its own issuer and no other. Anybody else, and an address the provider did not
 * verify, is refused, so no authenticated session exists. The provider is configuration
 * ({@code spring.security.oauth2.client.*}); no provider is named here. E-mail addresses are compared in lower case with
 * {@link Locale#ROOT}, so the default locale of the JVM cannot change the comparison.
 */
@Component
public final class OwnerOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {

    /** The one person that may sign in: the e-mail address (lower case) and the issuer it counts for. */
    private record Owner(String issuer, String email) {
    }

    /** One element, or none when the sign-in is not OpenID Connect (it is not wired then, and nobody would be let in). */
    private final Set<Owner> owners;

    public OwnerOidcUserService(LedgerAdminProperties properties) {
        LedgerAdminProperties.Oidc oidc = properties.oidc();
        if (StringUtils.hasText(oidc.issuer()) && StringUtils.hasText(oidc.ownerEmail())) {
            this.owners = Set.of(new Owner(oidc.issuer().strip(), oidc.ownerEmail().strip().toLowerCase(Locale.ROOT)));
        } else {
            this.owners = Set.of();
        }
    }

    @Override
    public OidcUser loadUser(OidcUserRequest request) {
        OidcIdToken token = request.getIdToken();
        String email = token.getEmail();
        boolean verified = Boolean.TRUE.equals(token.getEmailVerified()) && StringUtils.hasText(email);
        if (!verified
                || !owners.contains(new Owner(String.valueOf(token.getIssuer()), email.strip().toLowerCase(Locale.ROOT)))) {
            throw new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.ACCESS_DENIED));
        }
        return new DefaultOidcUser(List.of(new SimpleGrantedAuthority(AdminTokenProvider.OWNER_AUTHORITY)), token);
    }
}
