package org.pgledger.database.repository;

import java.util.List;
import java.util.Optional;
import org.pgledger.database.time.UtcTime;
import org.pgledger.domain.model.AttachmentFile;
import org.pgledger.domain.value.StorageKey;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The files of the attachments: insert and read only (the application account has no UPDATE and no DELETE on the table). The
 * file of an attachment is its latest row; an older row names a replaced file. The repository joins the transaction of the service
 * method that calls it.
 */
@Repository
public class AttachmentFileRepository {

    private static final String COLUMNS = "SELECT f.id, f.attachment_id, f.storage_key, f.key_id, f.created_at "
            + "FROM attachment_file f ";

    /** A row is the current one when no later row (or, at the same time, a row of a greater id) of the attachment exists. */
    private static final String IS_CURRENT = "NOT EXISTS (SELECT 1 FROM attachment_file n WHERE n.attachment_id = f.attachment_id "
            + "AND (n.created_at > f.created_at OR (n.created_at = f.created_at AND n.id > f.id)))";

    private static final RowMapper<AttachmentFile> ROW = (rs, n) -> new AttachmentFile(rs.getString("id"),
            rs.getString("attachment_id"), StorageKey.of(rs.getString("storage_key")), rs.getString("key_id"),
            UtcTime.read(rs, "created_at"));

    private final JdbcClient jdbc;
    private final SqlBuilder sqlBuilder;

    public AttachmentFileRepository(JdbcClient jdbc, SqlBuilder sqlBuilder) {
        this.jdbc = jdbc;
        this.sqlBuilder = sqlBuilder;
    }

    public void insert(AttachmentFile file) {
        jdbc.sql("INSERT INTO attachment_file (id, attachment_id, storage_key, key_id, created_at) "
                + "VALUES (:id, :attachmentId, :storageKey, :keyId, :createdAt)")
                .param("id", file.id())
                .param("attachmentId", file.attachmentId())
                .param("storageKey", file.storageKey().hex())
                .param("keyId", file.keyId())
                .param("createdAt", UtcTime.toDb(file.createdAt()))
                .update();
    }

    /** The current file of the attachment: its latest row. */
    public Optional<AttachmentFile> currentOf(String attachmentId) {
        return jdbc.sql(COLUMNS + "WHERE f.attachment_id = :id AND " + IS_CURRENT).param("id", attachmentId).query(ROW)
                .optional();
    }

    /** The current files in the order of the attachment id after {@code afterAttachmentId}, at most {@code limit}. */
    public List<AttachmentFile> currentPage(String afterAttachmentId, int limit) {
        return jdbc.sql(sqlBuilder.applyLimit(COLUMNS + "WHERE f.attachment_id > :after AND " + IS_CURRENT
                + " ORDER BY f.attachment_id", ":row_count"))
                .param("after", afterAttachmentId)
                .param("row_count", limit)
                .query(ROW)
                .list();
    }

    /** True when a row (current or replaced) has this storage key. */
    public boolean holdsStorageKey(StorageKey key) {
        return jdbc.sql("SELECT COUNT(*) FROM attachment_file WHERE storage_key = :key").param("key", key.hex())
                .query(Long.class).single() > 0;
    }

    /** True when the key belongs to a row that is not the latest of its attachment: a replaced file. */
    public boolean isReplaced(StorageKey key) {
        return jdbc.sql("SELECT COUNT(*) FROM attachment_file f WHERE f.storage_key = :key AND NOT " + IS_CURRENT)
                .param("key", key.hex()).query(Long.class).single() > 0;
    }

    /** The ids of the key files that the current files need (never the keys): a replaced file needs no key. */
    public List<String> keyIdsInUse() {
        return jdbc.sql("SELECT DISTINCT f.key_id FROM attachment_file f WHERE " + IS_CURRENT).query(String.class).list();
    }

    /** How many current files are written with the key (an old key may be removed when this is 0 and no upload uses it). */
    public long currentFilesOfKey(String keyId) {
        return jdbc.sql("SELECT COUNT(*) FROM attachment_file f WHERE f.key_id = :key AND " + IS_CURRENT)
                .param("key", keyId).query(Long.class).single();
    }
}
