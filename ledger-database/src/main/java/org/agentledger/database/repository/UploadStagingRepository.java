// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.database.repository;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.agentledger.database.time.UtcTime;
import org.agentledger.domain.error.InfrastructureCode;
import org.agentledger.domain.error.InfrastructureException;
import org.agentledger.domain.model.UploadStaging;
import org.agentledger.domain.rule.UploadState;
import org.agentledger.domain.value.AuthorId;
import org.agentledger.domain.value.Sha256;
import org.agentledger.domain.value.StorageKey;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Staging rows of file uploads in progress (and finished rows waiting for the reaper). */
@Repository
public class UploadStagingRepository {

    private static final String COLUMNS = "id, attachment_id, seq, owner, operation_key, file_name, media_type, "
            + "declared_size, declared_sha256, received_bytes, stored_bytes, next_sequence, last_chunk_sha256, "
            + "storage_key, key_id, state, expires_at, created_at, updated_at";

    private static final RowMapper<UploadStaging> MAPPER = (rs, rowNum) -> new UploadStaging(
            rs.getString("id"),
            rs.getString("attachment_id"),
            rs.getInt("seq"),
            AuthorId.of(rs.getString("owner")),
            rs.getString("operation_key"),
            rs.getString("file_name"),
            rs.getString("media_type"),
            rs.getLong("declared_size"),
            Sha256.of(rs.getString("declared_sha256")),
            rs.getLong("received_bytes"),
            rs.getLong("stored_bytes"),
            rs.getLong("next_sequence"),
            rs.getString("last_chunk_sha256") == null ? null : Sha256.of(rs.getString("last_chunk_sha256")),
            StorageKey.of(rs.getString("storage_key")),
            rs.getString("key_id"),
            UploadState.valueOf(rs.getString("state")),
            UtcTime.read(rs, "expires_at"),
            UtcTime.read(rs, "created_at"),
            UtcTime.read(rs, "updated_at"));

    private final JdbcClient jdbc;
    private final SqlBuilder sql;

    public UploadStagingRepository(JdbcClient jdbc, SqlBuilder sql) {
        this.jdbc = jdbc;
        this.sql = sql;
    }

    /**
     * Acquires an ANSI row lock on the single-row {@code upload_quota_gate} table for the caller's transaction so
     * concurrent {@code artifact_begin} requests serialize idempotent key lookup and quota accounting.
     */
    // ceiling: single-row ANSI gate serializing upload begin quota checks; shard by owner if begin throughput requires it.
    public void lockQuotaGate() {
        int rows = jdbc.sql("UPDATE upload_quota_gate SET revision = revision + 1 WHERE id = 1").update();
        if (rows != 1) {
            throw new InfrastructureException(InfrastructureCode.INTEGRITY_VIOLATION,
                    List.of("upload_quota_gate row missing"));
        }
    }

    public void insert(UploadStaging row) {
        jdbc.sql("INSERT INTO upload_staging (id, attachment_id, seq, owner, operation_key, file_name, media_type, "
                + "declared_size, declared_sha256, received_bytes, stored_bytes, next_sequence, last_chunk_sha256, "
                + "storage_key, key_id, state, expires_at, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .param(row.id())
                .param(row.attachmentId())
                .param(row.seq())
                .param(row.owner().value())
                .param(row.operationKey())
                .param(row.fileName())
                .param(row.mediaType())
                .param(row.declaredSize())
                .param(row.declaredSha256().hex())
                .param(row.receivedBytes())
                .param(row.storedBytes())
                .param(row.nextSequence())
                .param(row.lastChunkSha256() == null ? null : row.lastChunkSha256().hex())
                .param(row.storageKey().hex())
                .param(row.keyId())
                .param(row.state().name())
                .param(UtcTime.toDb(row.expiresAt()))
                .param(UtcTime.toDb(row.createdAt()))
                .param(UtcTime.toDb(row.updatedAt()))
                .update();
    }

    public Optional<UploadStaging> findById(String id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM upload_staging WHERE id = ?")
                .param(id).query(MAPPER).optional();
    }

    public List<UploadStaging> findByOwnerAndOperationKey(String owner, String operationKey) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM upload_staging WHERE owner = ? AND operation_key = ? ORDER BY seq")
                .param(owner).param(operationKey).query(MAPPER).list();
    }

    public List<UploadStaging> findByAttachmentId(String attachmentId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM upload_staging WHERE attachment_id = ? ORDER BY seq")
                .param(attachmentId).query(MAPPER).list();
    }

    /** Open bundles of {@code owner} (one begin creates one or more rows under the same {@code attachment_id}). */
    public long countOpenBundles(String owner) {
        return jdbc.sql("SELECT COUNT(DISTINCT attachment_id) FROM upload_staging WHERE owner = ? AND state = 'STAGING'")
                .param(owner).query(Long.class).single();
    }

    public long declaredOpenBytes() {
        return jdbc.sql("SELECT COALESCE(SUM(declared_size), 0) FROM upload_staging WHERE state = 'STAGING'")
                .query(Long.class).single();
    }

    /** Conditional advance of one staging upload row (design 4.4 step 4). */
    public boolean advance(String id, long expectedSequence, long storedBytes, long receivedBytes, Sha256 chunkSha256,
            Instant newExpiresAt, Instant updatedAt) {
        int rows = jdbc.sql("UPDATE upload_staging SET stored_bytes = ?, received_bytes = ?, next_sequence = ?, "
                + "last_chunk_sha256 = ?, expires_at = ?, updated_at = ? "
                + "WHERE id = ? AND state = 'STAGING' AND next_sequence = ?")
                .param(storedBytes)
                .param(receivedBytes)
                .param(expectedSequence + 1)
                .param(chunkSha256.hex())
                .param(UtcTime.toDb(newExpiresAt))
                .param(UtcTime.toDb(updatedAt))
                .param(id)
                .param(expectedSequence)
                .update();
        return rows == 1;
    }

    /** Extends the expiry of every open file of the same bundle together so no file expires before its siblings finish. */
    public void extendBundle(String attachmentId, Instant expiresAt, Instant updatedAt) {
        jdbc.sql("UPDATE upload_staging SET expires_at = ?, updated_at = ? WHERE attachment_id = ? AND state = 'STAGING'")
                .param(UtcTime.toDb(expiresAt))
                .param(UtcTime.toDb(updatedAt))
                .param(attachmentId)
                .update();
    }

    /** Marks one open upload row as COMPLETED on finish. */
    public boolean complete(String id, long finalStoredBytes, Instant updatedAt) {
        int rows = jdbc.sql("UPDATE upload_staging SET state = 'COMPLETED', stored_bytes = ?, updated_at = ? "
                + "WHERE id = ? AND state = 'STAGING'")
                .param(finalStoredBytes)
                .param(UtcTime.toDb(updatedAt))
                .param(id)
                .update();
        return rows == 1;
    }

    public List<UploadStaging> open() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM upload_staging WHERE state = 'STAGING' ORDER BY created_at, id")
                .query(MAPPER).list();
    }

    public Set<String> openKeyIds() {
        return new LinkedHashSet<>(jdbc.sql("SELECT DISTINCT key_id FROM upload_staging WHERE state = 'STAGING'")
                .query(String.class).list());
    }

    public boolean holdsStorageKey(StorageKey storageKey) {
        return jdbc.sql("SELECT COUNT(*) FROM upload_staging WHERE storage_key = ?")
                .param(storageKey.hex()).query(Long.class).single() > 0;
    }

    public List<UploadStaging> expired(Instant now, int limit) {
        String statement = sql.applyLimit(
                "SELECT " + COLUMNS + " FROM upload_staging WHERE state = 'STAGING' AND expires_at <= :now ORDER BY expires_at, id",
                ":row_count");
        return jdbc.sql(statement).param("now", UtcTime.toDb(now)).param("row_count", limit).query(MAPPER).list();
    }

    public List<UploadStaging> completed(int limit) {
        String statement = sql.applyLimit(
                "SELECT " + COLUMNS + " FROM upload_staging WHERE state = 'COMPLETED' ORDER BY updated_at, id",
                ":row_count");
        return jdbc.sql(statement).param("row_count", limit).query(MAPPER).list();
    }

    public boolean deleteExpired(String id, Instant now) {
        return jdbc.sql("DELETE FROM upload_staging WHERE id = ? AND state = 'STAGING' AND expires_at <= ?")
                .param(id).param(UtcTime.toDb(now)).update() == 1;
    }

    public boolean deleteCompleted(String id) {
        return jdbc.sql("DELETE FROM upload_staging WHERE id = ? AND state = 'COMPLETED'")
                .param(id).update() == 1;
    }
}
