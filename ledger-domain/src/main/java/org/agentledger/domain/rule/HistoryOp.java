// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.domain.rule;

import org.agentledger.domain.value.Token;

/** What a history entry records. The last three exist only for rows imported from another ledger. */
public enum HistoryOp implements Token {
    CREATE, EDIT, TRANSITION, LINK, UNLINK, CORRECT, IMPORT, IMPORTED_NOTE, IMPORTED_CHANGE;

    @Override
    public String token() {
        return name();
    }

    public boolean importedOnly() {
        return this == IMPORT || this == IMPORTED_NOTE || this == IMPORTED_CHANGE;
    }
}
