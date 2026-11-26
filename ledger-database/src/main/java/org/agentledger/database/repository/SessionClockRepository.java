// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.database.repository;

import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads the wall clock of the database session (ANSI {@code LOCALTIMESTAMP}) for the UTC guard. */
@Repository
public class SessionClockRepository {

    private final JdbcClient jdbc;

    public SessionClockRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The session clock, read through an aggregate over our own table so that no vendor-specific "select without
     * table" is needed. Empty when the table has no row yet.
     *
     * <p>This is the one place that asks the database for the time, and on purpose (principle 4.5 forbids DB clock
     * functions for the data): the ANSI {@code LOCALTIMESTAMP} shows the wall clock of the database session, and that is what
     * the UTC guard ({@code UtcSessionCheck}) compares with the UTC {@code Clock} to find out whether the session zone is
     * UTC. No row is ever written with this value.
     */
    public Optional<LocalDateTime> sessionTimestamp() {
        return jdbc.sql("SELECT MAX(LOCALTIMESTAMP) FROM schema_history")
                .query((rs, n) -> rs.getObject(1, LocalDateTime.class))
                .optional();
    }
}
