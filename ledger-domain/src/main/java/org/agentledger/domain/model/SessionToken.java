// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.domain.model;

import java.time.Instant;
import org.agentledger.domain.error.ValidationException;
import org.agentledger.domain.error.ValidationReason;
import org.agentledger.domain.value.AuthorId;
import org.agentledger.domain.value.TextRule;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * An opaque bearer token binding a standard HTTP MCP client to a declared {@link AuthorId} without requiring
 * vendor-specific hooks. Only {@code tokenHash} (64-char lower-case SHA-256 hex) and short {@code tokenPrefix}
 * are stored.
 */
@Table("session_token")
public record SessionToken(@Id Long id, AuthorId authorId, String tokenHash, String tokenPrefix, AuthorId issuedBy,
        Instant issuedAt, Instant expiresAt, Instant revokedAt, Instant lastUsedAt, String note) {

    public SessionToken {
        if (expiresAt != null && !expiresAt.isAfter(issuedAt)) {
            throw ValidationException.invalid("expires_at", ValidationReason.MUST_BE_AFTER_GRANT_TIME);
        }
    }

    public static SessionToken issue(long id, AuthorId author, String tokenHash, String tokenPrefix, AuthorId by,
            Instant now, Instant expiresAt, String note) {
        return new SessionToken(id, author, tokenHash, tokenPrefix, by, now, expiresAt, null, null,
                TextRule.GRANT_NOTE.optional(note));
    }
}
