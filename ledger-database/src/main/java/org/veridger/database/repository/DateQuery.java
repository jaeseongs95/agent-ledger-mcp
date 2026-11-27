// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.database.repository;

import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.veridger.database.time.UtcTime;

/** Optional date range and keyset order. SQL column names come from the fixed column set. */
public record DateQuery(Column field, Instant from, Instant until, Column sort, boolean ascending) {

    public enum Column {
        CREATED("created_at"), UPDATED("updated_at"), OCCURRED("h.occurred_at");

        private final String sql;

        Column(String sql) {
            this.sql = sql;
        }
    }

    public static final DateQuery NONE = new DateQuery(null, null, null, null, false);

    public Object cursorCriteria(Object defaults) {
        return equals(NONE) ? defaults : java.util.List.of(defaults, this);
    }

    public DateQuery defaultOrder(Column column, boolean oldestFirst) {
        return sort == null ? new DateQuery(field, from, until, column, oldestFirst) : this;
    }

    public Instant sortKey(Instant created, Instant updated) {
        return sort == Column.UPDATED ? updated : created;
    }

    void appendRange(StringBuilder sql) {
        if (from != null) {
            sql.append(" AND ").append(field.sql).append(" >= :dateFrom");
        }
        if (until != null) {
            sql.append(" AND ").append(field.sql).append(" < :dateUntil");
        }
    }

    JdbcClient.StatementSpec bindRange(JdbcClient.StatementSpec stmt) {
        if (from != null) {
            stmt = stmt.param("dateFrom", UtcTime.toDb(from));
        }
        if (until != null) {
            stmt = stmt.param("dateUntil", UtcTime.toDb(until));
        }
        return stmt;
    }

    void appendSeek(StringBuilder sql, String id, Instant after, boolean nullable) {
        String cmp = ascending ? " > " : " < ";
        String column = sort.sql;
        if (after == null) {
            sql.append(" AND ").append(column).append(" IS NULL AND ").append(id).append(cmp).append(":dateAfterId");
            return;
        }
        sql.append(" AND (").append(column).append(cmp).append(":dateAfter OR (").append(column)
                .append(" = :dateAfter AND ").append(id).append(cmp).append(":dateAfterId)");
        if (nullable) {
            sql.append(" OR ").append(column).append(" IS NULL");
        }
        sql.append(')');
    }

    JdbcClient.StatementSpec bindSeek(JdbcClient.StatementSpec stmt, Instant after, Object id) {
        if (after != null) {
            stmt = stmt.param("dateAfter", UtcTime.toDb(after));
        }
        return stmt.param("dateAfterId", id);
    }

    void appendOrder(StringBuilder sql, String id, boolean nullable) {
        String direction = ascending ? " ASC" : " DESC";
        sql.append(" ORDER BY ");
        if (nullable) {
            sql.append("CASE WHEN ").append(sort.sql).append(" IS NULL THEN 1 ELSE 0 END, ");
        }
        sql.append(sort.sql).append(direction).append(", ").append(id).append(direction);
    }
}
