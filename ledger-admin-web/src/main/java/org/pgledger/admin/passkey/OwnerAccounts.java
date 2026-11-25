// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.admin.passkey;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Objects;
import org.pgledger.admin.security.AdminTokenProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;

/**
 * The two accounts a WebAuthn or one-time-token sign-in can end in, for the {@link UserDetailsService} that Spring Security's
 * passkey and one-time-token sign-in load the signed-in account from: the owner (a passkey sign-in; the owner role) and the
 * enrolment account (a sign-in with the one-time code of the owner's shell; the enrolment role, which may register a passkey and
 * nothing else). Nobody signs in with a password: each account has a password nobody knows (random at every start), and no
 * password sign-in is configured.
 */
@Component
public final class OwnerAccounts implements UserDetailsService {

    /** The account of the owner. */
    public static final String OWNER = LedgerPublicKeyCredentialUserEntityRepository.OWNER;
    /** The account of a sign-in with the one-time code. */
    public static final String ENROLL = "enroll";
    /** The authority of the enrolment account. */
    public static final String ENROLL_AUTHORITY = "ROLE_ENROLL";

    private static final int SECRET_BYTES = 32;

    private final String password;

    public OwnerAccounts() {
        byte[] secret = new byte[SECRET_BYTES];
        new SecureRandom().nextBytes(secret);
        this.password = "{noop}" + HexFormat.of().formatHex(secret);
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        if (Objects.equals(OWNER, username)) {
            return User.withUsername(OWNER).password(password).authorities(AdminTokenProvider.OWNER_AUTHORITY).build();
        }
        if (Objects.equals(ENROLL, username)) {
            return User.withUsername(ENROLL).password(password).authorities(ENROLL_AUTHORITY).build();
        }
        throw new UsernameNotFoundException("no such account");
    }

    /** True when the session holds only the one-time enrolment authority and not the owner authority. */
    public static boolean isEnrolmentOnly(Authentication auth) {
        if (auth == null) {
            return false;
        }
        boolean enroll = false;
        for (var granted : auth.getAuthorities()) {
            String authority = granted.getAuthority();
            if (Objects.equals(AdminTokenProvider.OWNER_AUTHORITY, authority)) {
                return false;
            }
            if (Objects.equals(ENROLL_AUTHORITY, authority)) {
                enroll = true;
            }
        }
        return enroll;
    }
}
