// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.admin.security;

import java.util.List;
import org.veridger.domain.error.AdminCode;
import org.veridger.domain.error.AdminException;
import org.veridger.domain.value.OwnerIdentity;
import org.springframework.core.MethodParameter;
import org.springframework.security.authentication.ott.OneTimeTokenAuthentication;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.webauthn.authentication.WebAuthnAuthentication;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Resolves a controller argument of the type {@link OwnerIdentity}: who is signed in, as the identity that an owner
 * operation is recorded under: {@code passkey:owner} for a passkey sign-in, {@code oidc:<issuer>#<subject>} for an OpenID Connect
 * sign-in (a subject is only unique inside its issuer), {@code code:enroll} for the enrolment sign-in with the one-time code of
 * the owner's shell (which may register a passkey and nothing else) and {@code token:owner} for the admin token (read only).
 * Never the fixed role name.
 *
 * <p>An identity that is longer than the audit trail holds is refused, not cut: two long identities that differ only after
 * the cut would become the same actor, and the trail would name somebody who did not act. The refusal is the
 * {@code UNAUTHORIZED} of the admin (the sign-in cannot be recorded, so it cannot act as the owner).
 */
@Component
public final class OwnerActorResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return OwnerIdentity.class.equals(parameter.getParameterType());
    }

    @Override
    public OwnerIdentity resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
            NativeWebRequest request, WebDataBinderFactory binders) {
        if (!(request.getUserPrincipal() instanceof Authentication authentication)) {
            throw new AdminException(AdminCode.UNAUTHORIZED, List.of());
        }
        return of(authentication);
    }

    /**
     * The identity of the signed-in owner.
     *
     * @throws AdminException UNAUTHORIZED when the identity is longer than the audit trail holds
     */
    public OwnerIdentity of(Authentication authentication) {
        String identity = switch (authentication) {
            case OneTimeTokenAuthentication code -> "code:" + code.getName();
            case WebAuthnAuthentication passkey -> "passkey:" + passkey.getName();
            default -> switch (authentication.getPrincipal()) {
                case OidcUser oidc -> "oidc:" + oidc.getIssuer() + "#" + oidc.getSubject();
                default -> "token:" + authentication.getName();
            };
        };
        if (identity.length() > OwnerIdentity.MAX_LENGTH) {
            throw new AdminException(AdminCode.UNAUTHORIZED, List.of());
        }
        return OwnerIdentity.of(identity);
    }
}
