package org.pgledger.application.support;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * A transaction on its own thread and its own connection that does its work and then stays open: the rows it changed stay locked until
 * {@link #release()}, which lets it commit. The test starts a second statement on another connection, waits until the database shows it
 * waiting for this connection ({@link LockWaits}), and then releases. This is the two-connection race of the tests, made deterministic by
 * the database's own lock and not by the clock.
 */
public final class HeldTransaction<T> {

    private static final long SECONDS = 60;

    private final CountDownLatch held = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicInteger backend = new AtomicInteger();
    private final Future<T> result;

    private HeldTransaction(LedgerAppContext ctx, Supplier<T> work) {
        this.result = Parallel.start(() -> ctx.tx(() -> {
            try {
                T value = work.get();
                backend.set(ctx.jdbc().sql("SELECT pg_backend_pid()").query(Integer.class).single());
                held.countDown();
                if (!release.await(SECONDS, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("the held transaction was not released");
                }
                return value;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while the transaction was held open", interrupted);
            } finally {
                held.countDown();
            }
        }));
    }

    /** Runs the work in a transaction that stays open. */
    public static <T> HeldTransaction<T> start(LedgerAppContext ctx, Supplier<T> work) {
        return new HeldTransaction<>(ctx, work);
    }

    /**
     * Waits until the work is done and the transaction is open; the process id of its database connection.
     *
     * @throws IllegalStateException when the work failed (the cause is its failure)
     */
    public int awaitHeld() throws InterruptedException {
        if (!held.await(SECONDS, TimeUnit.SECONDS)) {
            throw new IllegalStateException("the held transaction did not reach its work");
        }
        if (backend.get() == 0) {
            try {
                result.get(SECONDS, TimeUnit.SECONDS);
            } catch (ExecutionException failure) {
                throw new IllegalStateException("the work of the held transaction failed", failure.getCause());
            } catch (TimeoutException timeout) {
                throw new IllegalStateException("the held transaction did not end", timeout);
            }
            throw new IllegalStateException("the held transaction ended before it was released");
        }
        return backend.get();
    }

    /** Lets the transaction commit and returns what the work answered. */
    public T release() throws InterruptedException, ExecutionException, TimeoutException {
        release.countDown();
        return result.get(SECONDS, TimeUnit.SECONDS);
    }
}
