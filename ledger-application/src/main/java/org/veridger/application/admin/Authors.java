// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application.admin;

import java.util.List;
import org.veridger.domain.error.TrustCode;
import org.veridger.domain.error.TrustException;
import org.veridger.domain.error.ValidationException;
import org.veridger.domain.error.ValidationReason;
import org.veridger.domain.value.AuthorId;

/** The session author an owner operation names: the one reading of it, for the grants. */
final class Authors {

    private Authors() {
    }

    /**
     * The session author of the text the owner gave: a missing text is a missing {@code author} (REQUIRED), and the
     * reserved owner identity {@code user:owner} is refused as {@code AUTHOR_ID_FORMAT} so no MCP session grant can
     * ever be issued to the owner role.
     *
     * @throws ValidationException REQUIRED when there is no text
     * @throws TrustException      AUTHOR_ID_FORMAT when not a valid {@code <vendor>:<session-id>} session author
     */
    static AuthorId of(String raw) {
        if (raw == null) {
            throw ValidationException.invalid("author", ValidationReason.REQUIRED);
        }
        if (!AuthorId.isSessionId(raw)) {
            throw new TrustException(TrustCode.AUTHOR_ID_FORMAT, List.of(raw));
        }
        return AuthorId.of(raw);
    }
}
