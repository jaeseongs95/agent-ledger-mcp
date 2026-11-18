// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.database.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.pgledger.database.time.UtcTime;
import org.pgledger.domain.model.OwnerPasskey;
import org.pgledger.domain.model.PasskeyUser;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The passkeys of the owner and the owner as the user entity of WebAuthn, as plain ANSI statements. The history is append-only:
 * a credential row is inserted and never changed, closing a passkey inserts a row into {@code owner_passkey_revocation}, and a
 * passkey is live while it has none. The repository only joins the transaction of the service method that calls it.
 */
@Repository
public class PasskeyRepository {

    private static final String COLUMNS = "p.credential_id, p.user_id, p.public_key, p.signature_count, p.uv_initialized, "
            + "p.backup_eligible, p.backup_state, p.transports, p.attestation_object, p.label, p.fingerprint, "
            + "p.registered_by, p.registered_at";
    private static final String LIVE = " NOT EXISTS (SELECT 1 FROM owner_passkey_revocation r "
            + "WHERE r.credential_id = p.credential_id)";

    private final JdbcClient jdbc;

    public PasskeyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts a new passkey row. */
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
    }

    /** Whether a passkey row (live or closed) has this credential id: a credential id is never registered twice. */
    public boolean exists(String credentialId) {
        return jdbc.sql("SELECT COUNT(*) FROM owner_passkey WHERE credential_id = :id").param("id", credentialId)
                .query(Integer.class).single() > 0;
    }

    /** The live passkey with this credential id. */
    public Optional<OwnerPasskey> findLive(String credentialId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM owner_passkey p WHERE p.credential_id = :id AND" + LIVE)
                .param("id", credentialId).query((rs, n) -> read(rs)).list().stream().findFirst();
    }

    /** Every live passkey, oldest first. */
    public List<OwnerPasskey> findAllLive() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM owner_passkey p WHERE" + LIVE
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

    /** The user entity with this handle. */
    public Optional<PasskeyUser> findUserById(String userId) {
        return jdbc.sql("SELECT user_id, user_name, display_name FROM owner_passkey_user WHERE user_id = :id")
                .param("id", userId).query((rs, n) -> readUser(rs)).list().stream().findFirst();
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
