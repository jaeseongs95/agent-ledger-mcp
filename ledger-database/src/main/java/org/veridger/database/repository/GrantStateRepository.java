// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.database.repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.veridger.database.time.UtcTime;
import org.veridger.domain.model.SessionGrant;
import org.veridger.domain.rule.LockOrder;
import org.veridger.domain.rule.Scope;
import org.veridger.domain.value.AuthorId;
import org.veridger.domain.value.ProjectId;
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
     * Read-only pre-check that the author holds a live grant (not revoked, not expired) for the scope without taking a
     * row lock on {@code session_grant}. Uses the unique {@code active_key} ({@code uq_session_grant_active}) directly
     * so historical revoked rows for the same author/project/scope are never scanned. Write operations use this in
     * {@code WriteGate.open} and acquire the {@code session_grant} row lock via {@link #markUsed} in {@link LockOrder}
     * after entity pins.
     */
    public boolean hasLive(AuthorId author, ProjectId project, Scope scope, Instant now) {
        return jdbc.sql("SELECT 1 FROM session_grant "
                + "WHERE active_key = :activeKey AND (expires_at IS NULL OR expires_at > :now)")
                .param("activeKey", SessionGrant.liveKey(author, project, scope))
                .param("now", UtcTime.toDb(now))
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    /**
     * True when the author holds a live grant (not revoked, not expired) for the scope and its last use is stamped (the
     * affected row count of the conditional UPDATE is the answer); false when no live grant exists. Targets
     * {@code active_key} ({@code uq_session_grant_active}) directly.
     */
    public boolean markUsed(AuthorId author, ProjectId project, Scope scope, Instant now) {
        return jdbc.sql("UPDATE session_grant SET last_used_at = :now "
                + "WHERE active_key = :activeKey AND (expires_at IS NULL OR expires_at > :now)")
                .param("now", UtcTime.toDb(now))
                .param("activeKey", SessionGrant.liveKey(author, project, scope))
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

    /** One grant row to revoke, with what {@link LockOrder} sorts by. */
    private record LiveRow(long id, ProjectId project, Scope scope) {
    }

    /**
     * Revokes the live grants of an author, optionally narrowed to a project and a scope, and returns how many.
     * Grants that were revoked meanwhile by someone else are not counted. The rows are read without a lock, sorted in Java by
     * {@link LockOrder} (the order in which the write gate locks the same rows, which no collation of the database changes)
     * and updated one by one in that order, so a revocation and a write of the same author never wait for each other.
     */
    public int revokeLive(AuthorId author, ProjectId project, Scope scope, Instant now) {
        // a read of the live grants and then a conditional update of each, in the transaction of the calling service method
        String sql = "SELECT id, project_id, grant_scope FROM session_grant WHERE author_id = :author AND revoked_at IS NULL";
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
        List<LiveRow> rows = new ArrayList<>(spec.query((rs, n) -> new LiveRow(rs.getLong("id"),
                ProjectId.of(rs.getString("project_id")), Scope.valueOf(rs.getString("grant_scope")))).list());
        rows.sort(LockOrder.<LiveRow>grant(LiveRow::project, LiveRow::scope).thenComparingLong(LiveRow::id));
        for (LiveRow row : rows) {
            if (revoke(row.id(), now)) {
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
