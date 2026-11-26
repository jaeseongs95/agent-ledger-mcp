// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.admin.passkey;

import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.security.web.webauthn.api.AuthenticatorSelectionCriteria;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRpEntity;
import org.springframework.security.web.webauthn.api.ResidentKeyRequirement;
import org.springframework.security.web.webauthn.api.UserVerificationRequirement;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;
import org.springframework.security.web.webauthn.management.Webauthn4JRelyingPartyOperations;
import org.springframework.security.web.webauthn.management.WebAuthnRelyingPartyOperations;

/**
 * The passkeys of the admin web: the WebAuthn support of Spring Security on the ledger database (the two storage
 * repositories), the sign-in with the one-time code, and the passkey management page. Brought in by
 * {@code ConsoleConfiguration}, so it exists in console mode only (setup mode has no database to keep a passkey in). The scan
 * has the same {@link TypeExcludeFilter} as the scan of the console.
 */
@Configuration(proxyBeanMethods = false)
@ComponentScan(basePackageClasses = PasskeyConfiguration.class, excludeFilters = @ComponentScan.Filter(
        type = FilterType.CUSTOM, classes = TypeExcludeFilter.class))
public class PasskeyConfiguration {

    /**
     * The one engine of every WebAuthn ceremony (sign-in and registration): WebAuthn4J behind Spring Security's
     * {@link Webauthn4JRelyingPartyOperations}, for the host name and the origins of the admin web, with user verification
     * required at registration and at sign-in. A {@code @Bean} method because the class is the framework's and is
     * configured, not written.
     */
    @Bean
    WebAuthnRelyingPartyOperations webAuthnRelyingPartyOperations(PublicKeyCredentialUserEntityRepository users,
            UserCredentialRepository credentials, PasskeyRelyingParty party) {
        Webauthn4JRelyingPartyOperations operations = new Webauthn4JRelyingPartyOperations(users, credentials,
                PublicKeyCredentialRpEntity.builder().name(party.rpName()).id(party.rpId()).build(), party.origins());
        operations.setCustomizeCreationOptions(options -> options.authenticatorSelection(AuthenticatorSelectionCriteria
                .builder().userVerification(UserVerificationRequirement.REQUIRED)
                .residentKey(ResidentKeyRequirement.REQUIRED).build()));
        operations.setCustomizeRequestOptions(options -> options.userVerification(UserVerificationRequirement.REQUIRED));
        return operations;
    }

    /**
     * The guard of the passkey sign-in runs in the security chain only: Spring Boot would also put every filter bean into the
     * servlet container, where it would count each attempt a second time. A {@code @Bean} method because a registration bean is
     * the Boot type that decides where a filter runs.
     */
    @Bean
    FilterRegistrationBean<PasskeyLoginGuard> passkeyLoginGuardRegistration(PasskeyLoginGuard guard) {
        FilterRegistrationBean<PasskeyLoginGuard> registration = new FilterRegistrationBean<>(guard);
        registration.setEnabled(false);
        return registration;
    }

    /** The gate of the passkey endpoints runs in the security chain only, for the same reason as the guard. */
    @Bean
    FilterRegistrationBean<PasskeyEndpointGate> passkeyEndpointGateRegistration(PasskeyEndpointGate gate) {
        FilterRegistrationBean<PasskeyEndpointGate> registration = new FilterRegistrationBean<>(gate);
        registration.setEnabled(false);
        return registration;
    }
}
