package org.pgledger.database.repository;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.pgledger.database.time.UtcTime;
import org.pgledger.domain.model.AttachmentStorage;
import org.pgledger.domain.value.StorageKey;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Immutable stored copy rows in {@code attachment_storage}: rev 0 on finish, incremented on each rekey. */
@Repository
public class AttachmentStorageRepository {

    private static final String CURRENT_FILTER = "s.rev = (SELECT MAX(m.rev) FROM attachment_storage m "
            + "WHERE m.attachment_id = s.attachment_id AND m.seq = s.seq)";

    private static final RowMapper<AttachmentStorage> MAPPER = (rs, rowNum) -> new AttachmentStorage(
            rs.getString("attachment_id"),
            rs.getInt("seq"),
            rs.getInt("rev"),
            StorageKey.of(rs.getString("storage_key")),
            rs.getString("key_id"),
            UtcTime.read(rs, "created_at"));

    private final JdbcClient jdbc;

    public AttachmentStorageRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(AttachmentStorage storage) {
        jdbc.sql("INSERT INTO attachment_storage (attachment_id, seq, rev, storage_key, key_id, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?)")
                .param(storage.attachmentId())
                .param(storage.seq())
                .param(storage.rev())
                .param(storage.storageKey().hex())
                .param(storage.keyId())
                .param(UtcTime.toDb(storage.createdAt()))
                .update();
    }

    public Optional<AttachmentStorage> current(String attachmentId, int seq) {
        return jdbc.sql("SELECT attachment_id, seq, rev, storage_key, key_id, created_at FROM attachment_storage s "
                + "WHERE s.attachment_id = ? AND s.seq = ? AND " + CURRENT_FILTER)
                .param(attachmentId).param(seq).query(MAPPER).optional();
    }

    public int highestRev(String attachmentId, int seq) {
        return jdbc.sql("SELECT COALESCE(MAX(rev), -1) FROM attachment_storage WHERE attachment_id = ? AND seq = ?")
                .param(attachmentId).param(seq).query(Integer.class).single();
    }

    public List<AttachmentStorage> currents() {
        return jdbc.sql("SELECT s.attachment_id, s.seq, s.rev, s.storage_key, s.key_id, s.created_at "
                + "FROM attachment_storage s WHERE " + CURRENT_FILTER + " ORDER BY s.attachment_id, s.seq")
                .query(MAPPER).list();
    }

    public List<AttachmentStorage> currentsOfKey(String keyId) {
        return jdbc.sql("SELECT s.attachment_id, s.seq, s.rev, s.storage_key, s.key_id, s.created_at "
                + "FROM attachment_storage s WHERE " + CURRENT_FILTER + " AND s.key_id = ? "
                + "ORDER BY s.attachment_id, s.seq")
                .param(keyId).query(MAPPER).list();
    }

    public long currentFilesOfKey(String keyId) {
        return jdbc.sql("SELECT COUNT(*) FROM attachment_storage s WHERE " + CURRENT_FILTER + " AND s.key_id = ?")
                .param(keyId).query(Long.class).single();
    }

    public Set<String> currentKeyIds() {
        return new LinkedHashSet<>(jdbc.sql("SELECT DISTINCT s.key_id FROM attachment_storage s WHERE " + CURRENT_FILTER)
                .query(String.class).list());
    }

    public Optional<AttachmentStorage> byStorageKey(StorageKey storageKey) {
        return jdbc.sql("SELECT attachment_id, seq, rev, storage_key, key_id, created_at FROM attachment_storage "
                + "WHERE storage_key = ?")
                .param(storageKey.hex()).query(MAPPER).optional();
    }

    public Optional<AttachmentStorage> at(String attachmentId, int seq, int rev) {
        return jdbc.sql("SELECT attachment_id, seq, rev, storage_key, key_id, created_at FROM attachment_storage "
                + "WHERE attachment_id = ? AND seq = ? AND rev = ?")
                .param(attachmentId).param(seq).param(rev).query(MAPPER).optional();
    }

    public boolean holdsStorageKey(StorageKey storageKey) {
        return jdbc.sql("SELECT COUNT(*) FROM attachment_storage WHERE storage_key = ?")
                .param(storageKey.hex()).query(Long.class).single() > 0;
    }

    public boolean isReplaced(StorageKey storageKey) {
        return jdbc.sql("SELECT COUNT(*) FROM attachment_storage old WHERE old.storage_key = ? AND EXISTS ("
                + "SELECT 1 FROM attachment_storage cur WHERE cur.attachment_id = old.attachment_id "
                + "AND cur.seq = old.seq AND cur.rev > old.rev)")
                .param(storageKey.hex()).query(Long.class).single() > 0;
    }

    /** Instant when the next revision replaced {@code storageKey}, if it has been replaced. */
    public Optional<Instant> replacedAt(StorageKey storageKey) {
        return jdbc.sql("SELECT next_copy.created_at FROM attachment_storage old "
                + "JOIN attachment_storage next_copy ON next_copy.attachment_id = old.attachment_id "
                + "AND next_copy.seq = old.seq AND next_copy.rev = old.rev + 1 "
                + "WHERE old.storage_key = ?")
                .param(storageKey.hex())
                .query((rs, rowNum) -> UtcTime.read(rs, "created_at"))
                .optional();
    }
}
