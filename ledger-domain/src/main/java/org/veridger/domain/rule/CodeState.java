// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.domain.rule;

import org.veridger.domain.value.Token;

/** Whether a common code may be used by new writes. Codes are never deleted. */
public enum CodeState implements Token {
    ACTIVE, INACTIVE;

    @Override
    public String token() {
        return name();
    }
}
