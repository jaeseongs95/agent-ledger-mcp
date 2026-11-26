// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.database.repository;

import java.time.Instant;
import org.agentledger.database.time.UtcTime;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The one-time registration codes of the owner's passkeys ({@code owner_passkey_code}): only the SHA-256 of a code is
 * stored. A code is spent by one conditional UPDATE ({@link #spend}), so a used or an expired code affects no row and a
 * code cannot be spent twice. The repository only joins the transaction of the service method that calls it.
 */
@Repository
public class PasskeyCodeRepository {

    private final JdbcClient jdbc;

    public PasskeyCodeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Stores a new code (by its hash). */
    public void insert(String codeHash, String issuedBy, Instant issuedAt, Instant expiresAt) {
        jdbc.sql("INSERT INTO owner_passkey_code (code_hash, issued_by, issued_at, expires_at) VALUES (:hash, :by, :at, "
                + ":expires)")
                .param("hash", codeHash).param("by", issuedBy).param("at", UtcTime.toDb(issuedAt))
                .param("expires", UtcTime.toDb(expiresAt)).update();
    }

    /** Ends every code that is neither used nor expired: asking for a new code replaces the earlier one. */
    public int expireOpen(Instant now) {
        return jdbc.sql("UPDATE owner_passkey_code SET expires_at = :now WHERE used_at IS NULL AND expires_at > :now")
                .param("now", UtcTime.toDb(now)).update();
    }

    /** Whether the code is unused and not expired at {@code now}. */
    public boolean isOpen(String codeHash, Instant now) {
        return jdbc.sql("SELECT COUNT(*) FROM owner_passkey_code WHERE code_hash = :hash AND used_at IS NULL "
                + "AND expires_at > :now").param("hash", codeHash).param("now", UtcTime.toDb(now))
                .query(Integer.class).single() > 0;
    }

    /** Spends the code; false when it is unknown, used or expired (no row was changed). */
    public boolean spend(String codeHash, Instant now) {
        return jdbc.sql("UPDATE owner_passkey_code SET used_at = :now WHERE code_hash = :hash AND used_at IS NULL "
                + "AND expires_at > :now").param("now", UtcTime.toDb(now)).param("hash", codeHash).update() == 1;
    }
}
