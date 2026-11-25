// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.database.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.pgledger.database.time.UtcTime;
import org.pgledger.domain.model.SessionToken;
import org.pgledger.domain.value.AuthorId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads, touches, and revokes {@code session_token} rows using plain ANSI SQL. Tokens are never deleted.
 */
@Repository
public class SessionTokenStateRepository {

    private static final String COLUMNS = "id, author_id, token_hash, token_prefix, issued_by, issued_at, "
            + "expires_at, revoked_at, last_used_at, note";

    private final JdbcClient jdbc;

    public SessionTokenStateRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Active non-expired token matching {@code tokenHash}, if any. */
    public Optional<SessionToken> findLiveByHash(String tokenHash, Instant now) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM session_token WHERE token_hash = :hash "
                + "AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at > :now)")
                .param("hash", tokenHash)
                .param("now", UtcTime.toDb(now))
                .query((rs, n) -> read(rs))
                .list().stream().findFirst();
    }

    /** All active (non-revoked, non-expired) tokens for {@code author}, oldest first. */
    public List<SessionToken> findLiveByAuthor(AuthorId author, Instant now) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM session_token WHERE author_id = :author "
                + "AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at > :now) ORDER BY id")
                .param("author", author.value())
                .param("now", UtcTime.toDb(now))
                .query((rs, n) -> read(rs))
                .list();
    }

    /** All non-revoked tokens (ordered by author, id) for owner inspection. */
    public List<SessionToken> findAllUnrevoked() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM session_token WHERE revoked_at IS NULL ORDER BY author_id, id")
                .query((rs, n) -> read(rs))
                .list();
    }

    /** Updates {@code last_used_at} when the token is still active. */
    public void touchLastUsed(long id, Instant now) {
        jdbc.sql("UPDATE session_token SET last_used_at = :now WHERE id = :id AND revoked_at IS NULL")
                .param("now", UtcTime.toDb(now))
                .param("id", id)
                .update();
    }

    /** Revokes a live token; returns true if this call revoked it. */
    public boolean revoke(long id, Instant now) {
        return jdbc.sql("UPDATE session_token SET revoked_at = :now WHERE id = :id AND revoked_at IS NULL")
                .param("now", UtcTime.toDb(now))
                .param("id", id)
                .update() == 1;
    }

    private static SessionToken read(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new SessionToken(rs.getLong("id"), AuthorId.of(rs.getString("author_id")),
                rs.getString("token_hash"), rs.getString("token_prefix"), AuthorId.of(rs.getString("issued_by")),
                UtcTime.read(rs, "issued_at"), UtcTime.read(rs, "expires_at"), UtcTime.read(rs, "revoked_at"),
                UtcTime.read(rs, "last_used_at"), rs.getString("note"));
    }
}
