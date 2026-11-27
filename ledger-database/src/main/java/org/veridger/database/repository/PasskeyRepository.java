// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.database.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.veridger.database.time.UtcTime;
import org.veridger.domain.model.OwnerPasskey;
import org.veridger.domain.model.PasskeyUser;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The passkeys of the owner and the owner as the user entity of WebAuthn, as plain ANSI statements. The credential
 * definition table {@code owner_passkey} is append-only (never changed; closing a passkey inserts a row into
 * {@code owner_passkey_revocation}), while monotonic WebAuthn {@code signature_count} updates are tracked separately in
 * {@code owner_passkey_counter} so clone/rollback detection holds across restarts. All identifier lookups verify exact
 * character case in Java so case-insensitive DBMS collations cannot alias credentials.
 */
@Repository
public class PasskeyRepository {

    private static final String COLUMNS = "p.credential_id, p.user_id, p.public_key, "
            + "COALESCE(c.signature_count, p.signature_count) AS signature_count, p.uv_initialized, "
            + "p.backup_eligible, p.backup_state, p.transports, p.attestation_object, p.label, p.fingerprint, "
            + "p.registered_by, p.registered_at";
    private static final String FROM_WITH_COUNTER = "FROM owner_passkey p "
            + "LEFT JOIN owner_passkey_counter c ON c.credential_id = p.credential_id ";
    private static final String LIVE = " NOT EXISTS (SELECT 1 FROM owner_passkey_revocation r "
            + "WHERE r.credential_id = p.credential_id)";

    private final JdbcClient jdbc;

    public PasskeyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts a new passkey row along with its initial monotonic counter row. */
    public void insert(OwnerPasskey passkey) {
        jdbc.sql("INSERT INTO owner_passkey (credential_id, user_id, public_key, signature_count, uv_initialized, "
                + "backup_eligible, backup_state, transports, attestation_object, label, fingerprint, registered_by, "
                + "registered_at) VALUES (:id, :user, :key, :count, :uv, :eligible, :state, :transports, :attestation, "
                + ":label, :fingerprint, :by, :at)")
                .param("id", passkey.credentialId()).param("user", passkey.userId()).param("key", passkey.publicKey())
                .param("count", passkey.signatureCount()).param("uv", passkey.uvInitialized())
                .param("eligible", passkey.backupEligible()).param("state", passkey.backupState())
                .param("transports", passkey.transports()).param("attestation", passkey.attestationObject())
                .param("label", passkey.label()).param("fingerprint", passkey.fingerprint())
                .param("by", passkey.registeredBy()).param("at", UtcTime.toDb(passkey.registeredAt())).update();
        jdbc.sql("INSERT INTO owner_passkey_counter (credential_id, signature_count, updated_at) "
                + "VALUES (:id, :count, :at)")
                .param("id", passkey.credentialId())
                .param("count", passkey.signatureCount())
                .param("at", UtcTime.toDb(passkey.registeredAt()))
                .update();
    }

    /**
     * Atomically validates and advances the stored WebAuthn signature counter per W3C WebAuthn §7.2 Step 21. Returns
     * {@code true} when both stored and incoming counters are {@code 0} (synced/counterless authenticator) or when the
     * incoming counter strictly exceeds the stored counter and is persisted; returns {@code false} if the credential is
     * missing/revoked or the counter failed to advance (possible cloned authenticator or concurrent replay).
     *
     * <p>Every credential has a pre-seeded {@code owner_passkey_counter} row (backfilled by V13 for existing rows and
     * inserted atomically in {@link #insert(OwnerPasskey)} for new registrations), so concurrent logins serialize on a
     * single conditional {@code UPDATE} with zero {@code INSERT} race or PostgreSQL {@code 25P02} transaction abort.
     */
    public boolean advanceSignatureCount(String credentialId, long signatureCount, Instant now) {
        if (signatureCount < 0L) {
            return false;
        }
        Optional<OwnerPasskey> live = findLive(credentialId);
        if (live.isEmpty()) {
            return false;
        }
        long previous = live.orElseThrow().signatureCount();
        if (signatureCount == 0L && previous == 0L) {
            return true;
        }
        if (signatureCount <= previous) {
            return false;
        }
        String exactId = live.orElseThrow().credentialId();
        int updated = jdbc.sql("UPDATE owner_passkey_counter SET signature_count = :count, updated_at = :at "
                + "WHERE credential_id = :id AND signature_count < :count")
                .param("count", signatureCount)
                .param("at", UtcTime.toDb(now))
                .param("id", exactId)
                .update();
        return updated > 0;
    }

    /** Whether a passkey row (live or closed) has this exact credential id: a credential id is never registered twice. */
    public boolean exists(String credentialId) {
        return jdbc.sql("SELECT credential_id FROM owner_passkey WHERE credential_id = :id").param("id", credentialId)
                .query(String.class).list().stream().anyMatch(found -> Objects.equals(credentialId, found));
    }

    /** The passkey with this exact credential id, live or closed. */
    public Optional<OwnerPasskey> find(String credentialId) {
        return jdbc.sql("SELECT " + COLUMNS + " " + FROM_WITH_COUNTER + "WHERE p.credential_id = :id")
                .param("id", credentialId).query((rs, n) -> read(rs)).list().stream()
                .filter(found -> Objects.equals(credentialId, found.credentialId()))
                .findFirst();
    }

    /** The live passkey with this exact credential id. */
    public Optional<OwnerPasskey> findLive(String credentialId) {
        return jdbc.sql("SELECT " + COLUMNS + " " + FROM_WITH_COUNTER + "WHERE p.credential_id = :id AND" + LIVE)
                .param("id", credentialId).query((rs, n) -> read(rs)).list().stream()
                .filter(found -> Objects.equals(credentialId, found.credentialId()))
                .findFirst();
    }

    /** Every live passkey, oldest first. */
    public List<OwnerPasskey> findAllLive() {
        return jdbc.sql("SELECT " + COLUMNS + " " + FROM_WITH_COUNTER + "WHERE" + LIVE
                + " ORDER BY p.registered_at, p.credential_id").query((rs, n) -> read(rs)).list();
    }

    /** How many passkeys are live. */
    public int countLive() {
        return jdbc.sql("SELECT COUNT(*) FROM owner_passkey p WHERE" + LIVE).query(Integer.class).single();
    }

    /** Appends the revocation of one passkey. */
    public void revoke(String credentialId, String by, Instant now) {
        jdbc.sql("INSERT INTO owner_passkey_revocation (credential_id, revoked_by, revoked_at) VALUES (:id, :by, :at)")
                .param("id", credentialId).param("by", by).param("at", UtcTime.toDb(now)).update();
    }

    /** Appends the revocation of every live passkey (the reset from the owner's shell); how many were closed. */
    public int revokeAllLive(String by, Instant now) {
        return jdbc.sql("INSERT INTO owner_passkey_revocation (credential_id, revoked_by, revoked_at) "
                + "SELECT p.credential_id, :by, :at FROM owner_passkey p WHERE" + LIVE)
                .param("by", by).param("at", UtcTime.toDb(now)).update();
    }

    /** The owner as the user entity, if it was made. */
    public Optional<PasskeyUser> findUser() {
        return jdbc.sql("SELECT user_id, user_name, display_name FROM owner_passkey_user ORDER BY user_id")
                .query((rs, n) -> readUser(rs)).list().stream().findFirst();
    }

    /** The user entity with this exact handle. */
    public Optional<PasskeyUser> findUserById(String userId) {
        return jdbc.sql("SELECT user_id, user_name, display_name FROM owner_passkey_user WHERE user_id = :id")
                .param("id", userId).query((rs, n) -> readUser(rs)).list().stream()
                .filter(found -> Objects.equals(userId, found.userId()))
                .findFirst();
    }

    /** Inserts the one owner as the user entity. */
    public void insertUser(PasskeyUser user) {
        jdbc.sql("INSERT INTO owner_passkey_user (user_id, user_name, display_name) VALUES (:id, :name, :display)")
                .param("id", user.userId()).param("name", user.name()).param("display", user.displayName()).update();
    }

    private static OwnerPasskey read(ResultSet rs) throws SQLException {
        return new OwnerPasskey(rs.getString("credential_id"), rs.getString("user_id"), rs.getString("public_key"),
                rs.getLong("signature_count"), rs.getInt("uv_initialized"), rs.getInt("backup_eligible"),
                rs.getInt("backup_state"), rs.getString("transports"), rs.getString("attestation_object"),
                rs.getString("label"), rs.getString("fingerprint"), rs.getString("registered_by"),
                UtcTime.read(rs, "registered_at"));
    }

    private static PasskeyUser readUser(ResultSet rs) throws SQLException {
        return new PasskeyUser(rs.getString("user_id"), rs.getString("user_name"), rs.getString("display_name"));
    }
}
