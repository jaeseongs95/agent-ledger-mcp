// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.testing;

import org.veridger.domain.value.OwnerIdentity;

/** The owner identity that the tests of the services use as the signed-in owner. */
public final class TestOwners {

    /** The actor of an owner operation in a test; the audit trail shows it as {@code test:owner}. */
    public static final OwnerIdentity OWNER = OwnerIdentity.of("test:owner");

    private TestOwners() {
    }
}
