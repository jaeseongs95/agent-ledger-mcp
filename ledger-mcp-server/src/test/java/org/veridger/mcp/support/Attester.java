// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.mcp.support;

import org.springframework.context.ApplicationContext;

/**
 * Pass-through test attester: in v4.0.0 the client hook stamps {@code _hostAttestation} directly with the plain
 * {@code <vendor>:<session_id>} author identifier, so test calls that already supply a plain author need no extra
 * signing step.
 */
public final class Attester {

    private static final Attester INSTANCE = new Attester();

    private Attester() {
    }

    public static Attester of(ApplicationContext context) {
        return INSTANCE;
    }

    public static Attester none() {
        return INSTANCE;
    }

    public Object stamp(String tool, Object arguments) {
        return arguments;
    }
}
