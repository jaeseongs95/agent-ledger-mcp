// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.application.admin;

import org.agentledger.domain.error.ValidationException;
import org.agentledger.domain.error.ValidationReason;
import org.agentledger.domain.value.AuthorId;

/** The author an owner operation names: the one reading of it, for the grants and for the session keys. */
final class Authors {

    private Authors() {
    }

    /**
     * The author of the text the owner gave: a missing text is a missing {@code author} (a parameter of the operation,
     * REQUIRED); the format is the domain's ({@link AuthorId}).
     *
     * @throws ValidationException REQUIRED when there is no text
     */
    static AuthorId of(String raw) {
        if (raw == null) {
            throw ValidationException.invalid("author", ValidationReason.REQUIRED);
        }
        return AuthorId.of(raw);
    }
}
