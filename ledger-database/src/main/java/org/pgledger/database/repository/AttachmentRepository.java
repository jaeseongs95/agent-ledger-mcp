package org.pgledger.database.repository;

import java.util.List;
import java.util.Optional;
import org.pgledger.database.time.UtcTime;
import org.pgledger.domain.model.Attachment;
import org.pgledger.domain.value.AuthorId;
import org.pgledger.domain.value.Sha256;
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

    private static final String COLUMNS = "SELECT id, file_name, media_type, size_bytes, content_sha256, created_by, created_at "
            + "FROM attachment ";

    private static final RowMapper<Attachment> ROW = (rs, n) -> new Attachment(rs.getString("id"),
            rs.getString("file_name"), rs.getString("media_type"), rs.getLong("size_bytes"),
            Sha256.of(rs.getString("content_sha256")), AuthorId.of(rs.getString("created_by")),
            UtcTime.read(rs, "created_at"));

    private final JdbcClient jdbc;
    private final SqlBuilder sqlBuilder;

    public AttachmentRepository(JdbcClient jdbc, SqlBuilder sqlBuilder) {
        this.jdbc = jdbc;
        this.sqlBuilder = sqlBuilder;
    }

    public void insert(Attachment attachment) {
        jdbc.sql("INSERT INTO attachment (id, file_name, media_type, size_bytes, content_sha256, created_by, created_at) "
                + "VALUES (:id, :fileName, :mediaType, :size, :sha, :createdBy, :createdAt)")
                .param("id", attachment.id())
                .param("fileName", attachment.fileName())
                .param("mediaType", attachment.mediaType())
                .param("size", attachment.sizeBytes())
                .param("sha", attachment.contentSha256().hex())
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

    /** The sizes of all kept attachments added up: what the attachment budget has been used for. */
    public long keptBytes() {
        return jdbc.sql("SELECT COALESCE(SUM(size_bytes), 0) FROM attachment").query(Long.class).single();
    }

    /** True when a history row points to the attachment. */
    public boolean isReferenced(String id) {
        return jdbc.sql("SELECT COUNT(*) FROM history WHERE attachment_id = :id").param("id", id)
                .query(Long.class).single() > 0;
    }
}
