// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.domain.rule;

import org.agentledger.domain.value.Token;

/**
 * One of the two ends of a relation between defects: {@link #A} is the defect named first (for a directed relation kind the
 * subject: in {@code caused_by} a is caused by b), {@link #B} the other one. The token is the name of the argument of the
 * link tool that carries the defect.
 */
public enum RelationEnd implements Token {
    A("a"), B("b");

    private final String token;

    RelationEnd(String token) {
        this.token = token;
    }

    @Override
    public String token() {
        return token;
    }
}
