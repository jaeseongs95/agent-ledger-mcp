package org.pgledger.database.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.pgledger.database.time.UtcTime;
import org.pgledger.domain.model.UploadStaging;
import org.pgledger.domain.rule.UploadState;
import org.pgledger.domain.value.AuthorId;
import org.pgledger.domain.value.Sha256;
import org.pgledger.domain.value.StorageKey;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The uploads that are received chunk by chunk. Every change is one conditional statement whose count of affected rows
 * decides who won (the chunk that moves {@code next_sequence}, the finish that moves the state, the reaper that removes an
 * expired row): no row is locked by hand and nothing is retried. The repository joins the transaction of the service
 * method that calls it.
 */
@Repository
public class UploadStagingRepository {

    private static final String COLUMNS = "SELECT id, owner, operation_key, file_name, media_type, declared_size, "
            + "declared_sha256, received_bytes, stored_bytes, next_sequence, last_chunk_sha256, storage_key, key_id, state, "
            + "expires_at, created_at, updated_at FROM upload_staging ";

    private static final RowMapper<UploadStaging> ROW = (rs, n) -> new UploadStaging(rs.getString("id"),
            AuthorId.of(rs.getString("owner")), rs.getString("operation_key"), rs.getString("file_name"),
            rs.getString("media_type"), rs.getLong("declared_size"), Sha256.of(rs.getString("declared_sha256")),
            rs.getLong("received_bytes"), rs.getLong("stored_bytes"), rs.getLong("next_sequence"),
            rs.getString("last_chunk_sha256") == null ? null : Sha256.of(rs.getString("last_chunk_sha256")),
            StorageKey.of(rs.getString("storage_key")), rs.getString("key_id"),
            UploadState.valueOf(rs.getString("state")), UtcTime.read(rs, "expires_at"), UtcTime.read(rs, "created_at"),
            UtcTime.read(rs, "updated_at"));

    private final JdbcClient jdbc;
    private final SqlBuilder sqlBuilder;

    public UploadStagingRepository(JdbcClient jdbc, SqlBuilder sqlBuilder) {
        this.jdbc = jdbc;
        this.sqlBuilder = sqlBuilder;
    }

    /**
     * Inserts the row.
     *
     * @throws org.springframework.dao.DuplicateKeyException when the operation key of the owner is used (the caller read the row
     *         first; two begins at the same moment end with this translated error, and nothing catches it to go on)
     */
    public void insert(UploadStaging row) {
        String lastChunk = row.lastChunkSha256() == null ? null : row.lastChunkSha256().hex();
        jdbc.sql("INSERT INTO upload_staging (id, owner, operation_key, file_name, media_type, declared_size, declared_sha256, "
                + "received_bytes, stored_bytes, next_sequence, last_chunk_sha256, storage_key, key_id, state, expires_at, "
                + "created_at, updated_at) VALUES (:id, :owner, :operationKey, :fileName, :mediaType, :declaredSize, "
                + ":declaredSha, :received, :stored, :next, :lastChunk, :storageKey, :keyId, :state, :expiresAt, "
                + ":createdAt, :updatedAt)")
                .param("id", row.id())
                .param("owner", row.owner().value())
                .param("operationKey", row.operationKey())
                .param("fileName", row.fileName())
                .param("mediaType", row.mediaType())
                .param("declaredSize", row.declaredSize())
                .param("declaredSha", row.declaredSha256().hex())
                .param("received", row.receivedBytes())
                .param("stored", row.storedBytes())
                .param("next", row.nextSequence())
                .param("lastChunk", lastChunk, java.sql.Types.VARCHAR)
                .param("storageKey", row.storageKey().hex())
                .param("keyId", row.keyId())
                .param("state", row.state().name())
                .param("expiresAt", UtcTime.toDb(row.expiresAt()))
                .param("createdAt", UtcTime.toDb(row.createdAt()))
                .param("updatedAt", UtcTime.toDb(row.updatedAt()))
                .update();
    }

    public Optional<UploadStaging> findById(String id) {
        return jdbc.sql(COLUMNS + "WHERE id = :id").param("id", id).query(ROW).optional();
    }

    public Optional<UploadStaging> findByOperation(AuthorId owner, String operationKey) {
        return jdbc.sql(COLUMNS + "WHERE owner = :owner AND operation_key = :key")
                .param("owner", owner.value()).param("key", operationKey).query(ROW).optional();
    }

    /** The declared sizes of the uploads that still receive bytes, added up: what the budget has been used for. */
    public long openDeclaredBytes() {
        return jdbc.sql("SELECT COALESCE(SUM(declared_size), 0) FROM upload_staging WHERE state = 'STAGING'")
                .query(Long.class).single();
    }

    /** The number of uploads of the owner that still receive bytes. */
    public long openCount(AuthorId owner) {
        return jdbc.sql("SELECT COUNT(*) FROM upload_staging WHERE owner = :owner AND state = 'STAGING'")
                .param("owner", owner.value()).query(Long.class).single();
    }

    /**
     * Records a chunk that was appended to the file: true when this call moved the row from the chunk number it expected, so
     * of two requests for the same chunk exactly one gets true. The expiry is counted again from this chunk.
     */
    public boolean advance(String id, long expectedNextSequence, long storedBytes, long chunkBytes, Sha256 chunkSha256,
            Instant expiresAt, Instant now) {
        return jdbc.sql("UPDATE upload_staging SET stored_bytes = :stored, received_bytes = received_bytes + :chunk, "
                + "next_sequence = :next, last_chunk_sha256 = :sha, expires_at = :expiresAt, updated_at = :now "
                + "WHERE id = :id AND next_sequence = :expected AND state = 'STAGING'")
                .param("stored", storedBytes)
                .param("chunk", chunkBytes)
                .param("next", expectedNextSequence + 1)
                .param("sha", chunkSha256.hex())
                .param("expiresAt", UtcTime.toDb(expiresAt))
                .param("now", UtcTime.toDb(now))
                .param("id", id)
                .param("expected", expectedNextSequence)
                .update() == 1;
    }

    /** Marks the upload finished: true when it was still STAGING at the chunk number the finish saw. */
    public boolean complete(String id, long expectedNextSequence, long storedBytes, Instant now) {
        return jdbc.sql("UPDATE upload_staging SET state = 'COMPLETED', stored_bytes = :stored, updated_at = :now "
                + "WHERE id = :id AND state = 'STAGING' AND next_sequence = :expected")
                .param("stored", storedBytes)
                .param("now", UtcTime.toDb(now))
                .param("id", id)
                .param("expected", expectedNextSequence)
                .update() == 1;
    }

    /** The uploads that were STAGING and had expired when they were read, oldest first (the reaper deletes each by its own condition). */
    public List<UploadStaging> findExpired(Instant now, int limit) {
        return jdbc.sql(sqlBuilder.applyLimit(
                COLUMNS + "WHERE state = 'STAGING' AND expires_at <= :now ORDER BY expires_at", ":row_count"))
                .param("now", UtcTime.toDb(now))
                .param("row_count", limit)
                .query(ROW)
                .list();
    }

    /** The finished uploads (their rows are only left to be removed), at most {@code limit}. */
    public List<UploadStaging> findCompleted(int limit) {
        return jdbc.sql(sqlBuilder.applyLimit(COLUMNS + "WHERE state = 'COMPLETED' ORDER BY updated_at", ":row_count"))
                .param("row_count", limit)
                .query(ROW)
                .list();
    }

    /**
     * Deletes the upload when it is STAGING and expired at {@code now}; the condition is evaluated on the row as it is now, so a
     * chunk that moved the expiry or a finish that completed it after the list was read makes this 0 rows. True for one row.
     */
    public boolean deleteExpired(String id, Instant now) {
        return jdbc.sql("DELETE FROM upload_staging WHERE id = :id AND state = 'STAGING' AND expires_at <= :now")
                .param("id", id).param("now", UtcTime.toDb(now)).update() == 1;
    }

    /** Deletes the row of a finished upload (its file belongs to the attachment). */
    public boolean deleteCompleted(String id) {
        return jdbc.sql("DELETE FROM upload_staging WHERE id = :id AND state = 'COMPLETED'")
                .param("id", id).update() == 1;
    }

    /** True when a row has this storage key. */
    public boolean holdsStorageKey(StorageKey key) {
        return jdbc.sql("SELECT COUNT(*) FROM upload_staging WHERE storage_key = :key").param("key", key.hex())
                .query(Long.class).single() > 0;
    }

    /**
     * The ids of the key files that the open uploads need (never the keys). A finished upload (its row waits for the reaper) needs none:
     * its file belongs to the attachment, and the current file row of the attachment names the key.
     */
    public List<String> keyIdsInUse() {
        return jdbc.sql("SELECT DISTINCT key_id FROM upload_staging WHERE state = 'STAGING'").query(String.class).list();
    }
}
