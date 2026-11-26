// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.testing.database;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Deterministic PostgreSQL lock-wait checks for concurrent tests: instead of asserting fixed sleeps or
 * {@code Future.get(timeout)}, polls {@code pg_stat_activity} and {@code pg_blocking_pids(pid)} until the blocked
 * statement waits for the held backend process id.
 */
public final class LockWaits {

    private static final Duration DEADLINE = Duration.ofSeconds(30);
    private static final Duration POLL = Duration.ofMillis(10);

    private LockWaits() {
    }

    /** Backend process id ({@code pg_backend_pid()}) of the current transaction's JDBC connection. */
    public static int backendPid(JdbcClient jdbc) {
        return jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single();
    }

    /** How many statements currently wait for a lock held by {@code holderPid}. */
    public static long waitingFor(JdbcClient jdbc, int holderPid) {
        return jdbc.sql("SELECT COUNT(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' "
                + "AND :holder = ANY (pg_blocking_pids(pid))")
                .param("holder", holderPid)
                .query(Long.class)
                .single();
    }

    /** Blocks until another session is waiting on a lock held by {@code holderPid}, or fails at the deadline. */
    public static void awaitWaitingFor(JdbcClient jdbc, int holderPid) {
        await().atMost(DEADLINE).pollInterval(POLL).until(() -> waitingFor(jdbc, holderPid) > 0);
    }
}
