package org.pgledger.database.repository;

import java.util.List;
import java.util.Optional;
import org.pgledger.domain.model.AttachmentFile;
import org.pgledger.domain.value.Sha256;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Immutable file entries within a kept attachment bundle in {@code attachment_file}. */
@Repository
public class AttachmentFileRepository {

    private static final RowMapper<AttachmentFile> MAPPER = (rs, rowNum) -> new AttachmentFile(
            rs.getString("attachment_id"),
            rs.getInt("seq"),
            rs.getString("file_name"),
            rs.getString("media_type"),
            rs.getLong("size_bytes"),
            Sha256.of(rs.getString("content_sha256")));

    private final JdbcClient jdbc;

    public AttachmentFileRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(AttachmentFile file) {
        jdbc.sql("INSERT INTO attachment_file (attachment_id, seq, file_name, media_type, size_bytes, content_sha256) "
                + "VALUES (?, ?, ?, ?, ?, ?)")
                .param(file.attachmentId())
                .param(file.seq())
                .param(file.fileName())
                .param(file.mediaType())
                .param(file.sizeBytes())
                .param(file.contentSha256().hex())
                .update();
    }

    public List<AttachmentFile> ofAttachment(String attachmentId) {
        return jdbc.sql("SELECT attachment_id, seq, file_name, media_type, size_bytes, content_sha256 "
                + "FROM attachment_file WHERE attachment_id = ? ORDER BY seq")
                .param(attachmentId).query(MAPPER).list();
    }

    public Optional<AttachmentFile> at(String attachmentId, int seq) {
        return jdbc.sql("SELECT attachment_id, seq, file_name, media_type, size_bytes, content_sha256 "
                + "FROM attachment_file WHERE attachment_id = ? AND seq = ?")
                .param(attachmentId).param(seq).query(MAPPER).optional();
    }

    public List<AttachmentFile> all() {
        return jdbc.sql("SELECT attachment_id, seq, file_name, media_type, size_bytes, content_sha256 "
                + "FROM attachment_file ORDER BY attachment_id, seq")
                .query(MAPPER).list();
    }

    public long totalBytes() {
        return jdbc.sql("SELECT COALESCE(SUM(size_bytes), 0) FROM attachment_file")
                .query(Long.class).single();
    }
}
