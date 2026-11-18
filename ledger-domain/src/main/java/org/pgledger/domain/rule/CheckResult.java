// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.domain.rule;

import org.pgledger.domain.value.Token;

/** Outcome of an external check, review, test, or inspection attached to a deliverable. */
public enum CheckResult implements Token {
    PASSED("passed"),
    FAILED("failed"),
    CANCELLED("cancelled");

    private final String token;

    CheckResult(String token) {
        this.token = token;
    }

    @Override
    public String token() {
        return token;
    }
}
