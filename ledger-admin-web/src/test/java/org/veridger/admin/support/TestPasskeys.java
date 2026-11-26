// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.admin.support;

import org.veridger.testing.SoftwarePasskey;

/**
 * The passkey of the owner in the admin tests: a software authenticator ({@link SoftwarePasskey}) that
 * {@link AdminTestInfrastructure} registers in the database of every console context. The relying party and the origin are
 * the defaults of a loopback admin web (the host {@code localhost} and the origin {@code http://localhost:8865}), which is what the
 * contexts of the tests run with.
 */
public final class TestPasskeys {

    /** The one passkey that every console test context knows. */
    public static final SoftwarePasskey OWNER = new SoftwarePasskey();
    /** The user handle of the owner (base64url). */
    public static final String USER_ID = "dGVzdC1vd25lci1oYW5kbGU";
    public static final String RP_ID = "localhost";
    public static final String ORIGIN = "http://localhost:8865";

    private TestPasskeys() {
    }
}
