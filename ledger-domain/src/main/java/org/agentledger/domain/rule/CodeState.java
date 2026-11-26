// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.domain.rule;

import org.agentledger.domain.value.Token;

/** Whether a common code may be used by new writes. Codes are never deleted. */
public enum CodeState implements Token {
    ACTIVE, INACTIVE;

    @Override
    public String token() {
        return name();
    }
}
