package org.pgledger.application.support;

import static org.awaitility.Awaitility.await;

import java.time.Duration;

/**
 * Whether two statements overlapped is judged by what the database shows, not by the time that passed (design 9.11): a statement
 * that waits for a lock of the connection the test holds is visible in {@code pg_stat_activity} with the wait event type
 * {@code Lock}, and {@code pg_blocking_pids} names the connection it waits for. This is the lock wait query of PostgreSQL, the
 * database of the tests (principle 7.3: no other database is asked). The wait is for that condition; when it is not reached by the
 * deadline the test fails.
 */
public final class LockWaits {

    private static final Duration DEADLINE = Duration.ofSeconds(30);
    private static final Duration POLL = Duration.ofMillis(10);

    private LockWaits() {
    }

    /** How many statements wait now for a lock that the backend {@code holderPid} holds. */
    public static long waitingFor(LedgerAppContext ctx, int holderPid) {
        return ctx.jdbc().sql("SELECT COUNT(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' "
                + "AND :holder = ANY (pg_blocking_pids(pid))").param("holder", holderPid).query(Long.class).single();
    }

    /** Returns when a statement of another connection waits for a lock of the connection {@code holderPid}; fails at the deadline. */
    public static void awaitWaitingFor(LedgerAppContext ctx, int holderPid) {
        await().atMost(DEADLINE).pollInterval(POLL).until(() -> waitingFor(ctx, holderPid) > 0);
    }
}
