// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.hook.resident;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tells when nobody uses the resident program any more: no call is being answered and the last one ended (or the program
 * started) longer ago than the idle timeout. Both ends of a call refresh it, so a call that takes longer than the timeout is
 * never cut off by it.
 */
final class IdleWatch {

    private final Clock clock;
    private final Duration timeout;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicReference<Instant> lastActivity;

    IdleWatch(Clock clock, Duration timeout) {
        this.clock = clock;
        this.timeout = timeout;
        this.lastActivity = new AtomicReference<>(clock.instant());
    }

    /** A call starts. */
    void begin() {
        inFlight.incrementAndGet();
        lastActivity.set(clock.instant());
    }

    /** A call has ended. */
    void end() {
        lastActivity.set(clock.instant());
        inFlight.decrementAndGet();
    }

    /** Whether the program has been unused for the whole timeout. */
    boolean expired() {
        return inFlight.get() == 0 && !clock.instant().isBefore(lastActivity.get().plus(timeout));
    }

    /** How often {@link #expired} is worth asking: a tenth of the timeout, between a tenth of a second and five seconds. */
    Duration pollInterval() {
        Duration tenth = timeout.dividedBy(10);
        if (tenth.compareTo(Duration.ofMillis(100)) < 0) {
            return Duration.ofMillis(100);
        }
        return tenth.compareTo(Duration.ofSeconds(5)) > 0 ? Duration.ofSeconds(5) : tenth;
    }
}
