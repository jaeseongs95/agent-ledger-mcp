// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.database.repository;

import java.util.List;
import java.util.Optional;
import org.agentledger.database.time.UtcTime;
import org.agentledger.domain.model.Attachment;
import org.agentledger.domain.value.AuthorId;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Immutable bundle records in {@code attachment}. */
@Repository
public class AttachmentRepository {

    private static final RowMapper<Attachment> MAPPER = (rs, rowNum) -> new Attachment(
            rs.getString("id"),
            AuthorId.of(rs.getString("created_by")),
            UtcTime.read(rs, "created_at"));

    private final JdbcClient jdbc;

    public AttachmentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Attachment attachment) {
        jdbc.sql("INSERT INTO attachment (id, created_by, created_at) VALUES (?, ?, ?)")
                .param(attachment.id())
                .param(attachment.createdBy().value())
                .param(UtcTime.toDb(attachment.createdAt()))
                .update();
    }

    public Optional<Attachment> findById(String id) {
        return jdbc.sql("SELECT id, created_by, created_at FROM attachment WHERE id = ?")
                .param(id).query(MAPPER).optional();
    }

    public List<Attachment> all() {
        return jdbc.sql("SELECT id, created_by, created_at FROM attachment ORDER BY created_at, id")
                .query(MAPPER).list();
    }

    public List<String> unreferencedIds() {
        return jdbc.sql("SELECT a.id FROM attachment a WHERE NOT EXISTS ("
                + "SELECT 1 FROM history h WHERE h.attachment_id = a.id) ORDER BY a.created_at, a.id")
                .query(String.class).list();
    }
}
