// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.hook.resident;

import com.sun.net.httpserver.BasicAuthenticator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Lets a request through only if it knows the secret of this start of the program (the JDK's HTTP Basic authentication with the
 * secret as the password). The loopback address is open to every account of the PC, so this is what keeps another user from calling
 * the program: the secret is in the address file, which belongs to its owner alone. The comparison takes the same time whatever
 * the first differing byte is ({@link MessageDigest#isEqual}); a failed request says nothing about the secret and is not logged
 * with it.
 */
final class SecretAuthenticator extends BasicAuthenticator {

    private static final String REALM = "ledger-client-hook";
    private static final String USER = "hook";

    private final byte[] secret;

    SecretAuthenticator(String secret) {
        super(REALM);
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public boolean checkCredentials(String username, String password) {
        boolean user = USER.equals(username);
        boolean known = MessageDigest.isEqual(secret, password.getBytes(StandardCharsets.UTF_8));
        return user & known;
    }
}
