package org.pgledger.database.repository;

import java.util.List;
import java.util.Optional;
import org.pgledger.database.time.UtcTime;
import org.pgledger.domain.model.Attachment;
import org.pgledger.domain.value.AuthorId;
import org.pgledger.domain.value.Sha256;
import org.pgledger.domain.value.StorageKey;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The finished attachments: insert and read only. There is deliberately no update and no delete here, and the application
 * account has no right to UPDATE or DELETE the table (an attachment that was kept as evidence stays what it was). The
 * repository joins the transaction of the service method that calls it.
 */
@Repository
public class AttachmentRepository {

    private static final String COLUMNS = "SELECT id, file_name, media_type, size_bytes, content_sha256, storage_key, key_id, "
            + "created_by, created_at FROM attachment ";

    private static final RowMapper<Attachment> ROW = (rs, n) -> new Attachment(rs.getString("id"),
            rs.getString("file_name"), rs.getString("media_type"), rs.getLong("size_bytes"),
            Sha256.of(rs.getString("content_sha256")), StorageKey.of(rs.getString("storage_key")),
            rs.getString("key_id"), AuthorId.of(rs.getString("created_by")), UtcTime.read(rs, "created_at"));

    private final JdbcClient jdbc;
    private final SqlBuilder sqlBuilder;

    public AttachmentRepository(JdbcClient jdbc, SqlBuilder sqlBuilder) {
        this.jdbc = jdbc;
        this.sqlBuilder = sqlBuilder;
    }

    public void insert(Attachment attachment) {
        jdbc.sql("INSERT INTO attachment (id, file_name, media_type, size_bytes, content_sha256, storage_key, key_id, "
                + "created_by, created_at) VALUES (:id, :fileName, :mediaType, :size, :sha, :storageKey, :keyId, "
                + ":createdBy, :createdAt)")
                .param("id", attachment.id())
                .param("fileName", attachment.fileName())
                .param("mediaType", attachment.mediaType())
                .param("size", attachment.sizeBytes())
                .param("sha", attachment.contentSha256().hex())
                .param("storageKey", attachment.storageKey().hex())
                .param("keyId", attachment.keyId())
                .param("createdBy", attachment.createdBy().value())
                .param("createdAt", UtcTime.toDb(attachment.createdAt()))
                .update();
    }

    public Optional<Attachment> findById(String id) {
        return jdbc.sql(COLUMNS + "WHERE id = :id").param("id", id).query(ROW).optional();
    }

    /** The attachments in id order after {@code afterId} (the empty text for the first page), at most {@code limit}. */
    public List<Attachment> page(String afterId, int limit) {
        return jdbc.sql(sqlBuilder.applyLimit(COLUMNS + "WHERE id > :after ORDER BY id", ":row_count"))
                .param("after", afterId)
                .param("row_count", limit)
                .query(ROW)
                .list();
    }

    /** True when a row has this storage key. */
    public boolean holdsStorageKey(StorageKey key) {
        return jdbc.sql("SELECT COUNT(*) FROM attachment WHERE storage_key = :key").param("key", key.hex())
                .query(Long.class).single() > 0;
    }

    /** The ids of the key files that the rows need (never the keys). */
    public List<String> keyIdsInUse() {
        return jdbc.sql("SELECT DISTINCT key_id FROM attachment").query(String.class).list();
    }

    /** True when a history row points to the attachment. */
    public boolean isReferenced(String id) {
        return jdbc.sql("SELECT COUNT(*) FROM history WHERE attachment_id = :id").param("id", id)
                .query(Long.class).single() > 0;
    }
}
