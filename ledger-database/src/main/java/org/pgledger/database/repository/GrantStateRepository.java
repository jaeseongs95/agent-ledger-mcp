package org.pgledger.database.repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.pgledger.database.time.UtcTime;
import org.pgledger.domain.model.SessionGrant;
import org.pgledger.domain.rule.Scope;
import org.pgledger.domain.value.AuthorId;
import org.pgledger.domain.value.ProjectId;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * State changes of session grants as plain ANSI statements. The conditional UPDATE of {@link #markUsed} is the check of the
 * write and a real write of it (it stamps the last use): the race with a revocation is decided by the number of rows it
 * affects, one for a live grant and none for a grant that a committed revocation closed, and the write whose check affects
 * none ends with the translated SESSION_NOT_GRANTED. That the database makes a concurrent revocation of the same row wait
 * until the write's transaction ends is what any UPDATE of a row does; no statement exists only for that (principle 4.4).
 */
@Repository
public class GrantStateRepository {

    private final JdbcClient jdbc;

    public GrantStateRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * True when the author holds a live grant (not revoked, not expired) for the scope and its last use is stamped (the
     * affected row count of the conditional UPDATE is the answer); false when no live grant exists.
     */
    public boolean markUsed(AuthorId author, ProjectId project, Scope scope, Instant now) {
        return jdbc.sql("UPDATE session_grant SET last_used_at = :now "
                + "WHERE author_id = :author AND project_id = :project AND grant_scope = :scope "
                + "AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at > :now)")
                .param("now", UtcTime.toDb(now))
                .param("author", author.value())
                .param("project", project.value())
                .param("scope", scope.name())
                .update() == 1;
    }

    /** Revokes one live grant; false when it is already revoked or does not exist. */
    public boolean revoke(long id, Instant now) {
        return jdbc.sql("UPDATE session_grant SET revoked_at = :now, active_key = :key "
                + "WHERE id = :id AND revoked_at IS NULL")
                .param("now", UtcTime.toDb(now))
                .param("key", SessionGrant.revokedKey(id))
                .param("id", id)
                .update() == 1;
    }

    /**
     * Revokes the live grants of an author, optionally narrowed to a project and a scope, and returns how many.
     * Grants that were revoked meanwhile by someone else are not counted.
     */
    public int revokeLive(AuthorId author, ProjectId project, Scope scope, Instant now) {
        // a read of the live grants and then a conditional update of each, in the transaction of the calling service method
        String sql = "SELECT id FROM session_grant WHERE author_id = :author AND revoked_at IS NULL";
        if (project != null) {
            sql += " AND project_id = :project";
        }
        if (scope != null) {
            sql += " AND grant_scope = :scope";
        }
        JdbcClient.StatementSpec spec = jdbc.sql(sql).param("author", author.value());
        if (project != null) {
            spec = spec.param("project", project.value());
        }
        if (scope != null) {
            spec = spec.param("scope", scope.name());
        }
        int revoked = 0;
        for (Long id : spec.query(Long.class).list()) {
            if (revoke(id, now)) {
                revoked++;
            }
        }
        return revoked;
    }

    /** The live grants of an author at {@code now}, ordered by project and scope. */
    public List<SessionGrant> findLive(AuthorId author, Instant now) {
        return new ArrayList<>(jdbc.sql(LIVE_COLUMNS
                + "WHERE author_id = :author AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at > :now) "
                + "ORDER BY project_id, grant_scope")
                .param("author", author.value())
                .param("now", UtcTime.toDb(now))
                .query(GRANT_ROW)
                .list());
    }

    /** The live grants of every author at {@code now}, ordered by author, project and scope (the admin list). */
    public List<SessionGrant> findAllLive(Instant now) {
        return new ArrayList<>(jdbc.sql(LIVE_COLUMNS
                + "WHERE revoked_at IS NULL AND (expires_at IS NULL OR expires_at > :now) "
                + "ORDER BY author_id, project_id, grant_scope")
                .param("now", UtcTime.toDb(now))
                .query(GRANT_ROW)
                .list());
    }

    private static final String LIVE_COLUMNS = "SELECT id, author_id, project_id, grant_scope, granted_by, granted_at, "
            + "expires_at, revoked_at, last_used_at, note, active_key FROM session_grant ";

    private static final RowMapper<SessionGrant> GRANT_ROW = (rs, n) -> new SessionGrant(rs.getLong("id"),
            AuthorId.of(rs.getString("author_id")), ProjectId.of(rs.getString("project_id")),
            Scope.valueOf(rs.getString("grant_scope")), AuthorId.of(rs.getString("granted_by")),
            UtcTime.read(rs, "granted_at"), UtcTime.read(rs, "expires_at"), UtcTime.read(rs, "revoked_at"),
            UtcTime.read(rs, "last_used_at"), rs.getString("note"), rs.getString("active_key"));
}
