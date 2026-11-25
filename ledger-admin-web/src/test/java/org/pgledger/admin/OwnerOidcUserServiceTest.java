// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.admin.app.LedgerAdminProperties;
import org.pgledger.admin.app.SignInMethod;
import org.pgledger.admin.security.OwnerOidcUserService;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.util.unit.DataSize;

/**
 * ADM-058 (security audit U4) and PSK-OIDC-01: the owner of the OpenID Connect sign-in is one e-mail address, bound to one
 * issuer. The address counts for its own issuer only, whatever the other issuer says about its users, another address is
 * refused, an address the provider did not verify is refused, and the comparison does not depend on the default locale of the
 * JVM.
 */
class OwnerOidcUserServiceTest {

    private static final String ISSUER_A = "https://issuer-a.example";
    private static final String ISSUER_B = "https://issuer-b.example";
    private static final String OWNER = "Owner@Example.Test";

    private static LedgerAdminProperties properties(String issuer, String email) {
        return new LedgerAdminProperties("a-token-of-sixteen-chars", "an-agent-token-of-sixteen", List.of("localhost"),
                DataSize.ofKilobytes(64), java.nio.file.Path.of("/unused"), java.nio.file.Path.of("/unused-drivers"),
                new LedgerAdminProperties.Lockout(Duration.ofSeconds(60), 50, Duration.ofSeconds(1)),
                new LedgerAdminProperties.Oidc(issuer, email), new LedgerAdminProperties.Mcp(null, Duration.ofSeconds(2)),
                SignInMethod.OIDC, new LedgerAdminProperties.Passkey(null, List.of(), "Ledger admin"));
    }

    private static OidcUserRequest request(String issuer, String subject, Consumer<OidcIdToken.Builder> tweak) {
        ClientRegistration registration = ClientRegistration.withRegistrationId("mock").clientId("client")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://admin.example/login/oauth2/code/mock").authorizationUri(issuer + "/authorize")
                .tokenUri(issuer + "/token").build();
        Instant now = Instant.now();
        OAuth2AccessToken access = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "access-token", now,
                now.plusSeconds(60));
        OidcIdToken.Builder id = OidcIdToken.withTokenValue("id-token").issuer(issuer).subject(subject)
                .issuedAt(now).expiresAt(now.plusSeconds(60)).claim("email_verified", true);
        tweak.accept(id);
        return new OidcUserRequest(registration, access, id.build());
    }

    private static void email(OidcIdToken.Builder id, String address) {
        id.claim("email", address);
    }

    @Test
    @DisplayName("ADM-058/PSK-OIDC-01: the verified e-mail of the owner signs in at its own issuer, whatever its letter case, whatever the subject")
    void adm058_emailAtItsIssuer() {
        OwnerOidcUserService service = new OwnerOidcUserService(properties(ISSUER_A, OWNER));

        OidcUser user = service.loadUser(request(ISSUER_A, "any-subject", id -> email(id, "OWNER@example.TEST")));

        assertThat(user.getSubject()).isEqualTo("any-subject");
        assertThat(user.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_OWNER");
    }

    @Test
    @DisplayName("ADM-058: the same verified e-mail from another issuer is refused")
    void adm058_emailFromAnotherIssuer() {
        OwnerOidcUserService service = new OwnerOidcUserService(properties(ISSUER_A, OWNER));

        assertThatThrownBy(() -> service.loadUser(request(ISSUER_B, "any", id -> email(id, OWNER))))
                .isInstanceOf(OAuth2AuthenticationException.class);
    }

    @Test
    @DisplayName("PSK-OIDC-01: another e-mail address at the issuer is refused, also with the subject the owner had before")
    void psk_anotherEmail() {
        OwnerOidcUserService service = new OwnerOidcUserService(properties(ISSUER_A, OWNER));

        assertThatThrownBy(() -> service.loadUser(request(ISSUER_A, "subject-1", id -> email(id, "other@example.test"))))
                .isInstanceOf(OAuth2AuthenticationException.class);
        assertThatThrownBy(() -> service.loadUser(request(ISSUER_A, "subject-1", id -> { })))
                .isInstanceOf(OAuth2AuthenticationException.class);
    }

    @Test
    @DisplayName("ADM-058: an e-mail that the provider did not verify, or none, is refused")
    void adm058_unverifiedEmail() {
        OwnerOidcUserService service = new OwnerOidcUserService(properties(ISSUER_A, OWNER));

        assertThatThrownBy(() -> service.loadUser(request(ISSUER_A, "s", id -> {
            email(id, OWNER);
            id.claim("email_verified", false);
        }))).isInstanceOf(OAuth2AuthenticationException.class);
        assertThatThrownBy(() -> service.loadUser(request(ISSUER_A, "s", id -> { })))
                .isInstanceOf(OAuth2AuthenticationException.class);
    }

    @Test
    @DisplayName("PSK-OIDC-01: with no owner configured nobody signs in")
    void psk_nobodyWithoutAnOwner() {
        OwnerOidcUserService service = new OwnerOidcUserService(properties(null, null));

        assertThatThrownBy(() -> service.loadUser(request(ISSUER_A, "s", id -> email(id, OWNER))))
                .isInstanceOf(OAuth2AuthenticationException.class);
    }

    @Test
    @DisplayName("ADM-058: the e-mail comparison does not depend on the default locale (Turkish: I and i)")
    void adm058_localeRoot() {
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            OwnerOidcUserService service = new OwnerOidcUserService(properties(ISSUER_A, "ILKER@Example.Test"));

            assertThat(service.loadUser(request(ISSUER_A, "s", id -> email(id, "ilker@example.test")))).isNotNull();
            assertThat(service.loadUser(request(ISSUER_A, "s", id -> email(id, "ILKER@EXAMPLE.TEST")))).isNotNull();
        } finally {
            Locale.setDefault(before);
        }
    }
}
