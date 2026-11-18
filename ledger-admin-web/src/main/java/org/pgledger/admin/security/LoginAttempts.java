// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.admin.security;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import org.pgledger.admin.app.LedgerAdminProperties;
import org.springframework.stereotype.Component;

/**
 * The limit on credential checks (the admin token, the one-time code, the passkey sign-in): a <em>global</em> budget that
 * delays and never refuses. {@code globalMaxFailures} checks of all callers together within {@code window} are the budget;
 * every check beyond it is <em>delayed</em> by {@code globalDelay} (it is still made, so the right credential always logs
 * in, only later). The check is counted when it is admitted ({@link #admit}), in one atomic step with the decision about
 * the delay, so concurrent checks cannot overshoot the budget; a success gives its count back ({@link #succeeded}), so the
 * owner's own successful logins do not use the budget up. Time is read from the monotonic clock of the JVM (production:
 * {@code System.nanoTime}, a {@link LongSupplier} that a test replaces), never from the wall clock, so a change of the system
 * time cannot end or extend a window; the times are compared by their difference ({@code end - now}), as the documentation of
 * {@link System#nanoTime} asks, because a value may be negative and may wrap around.
 *
 * <p>Why there is no lock, and no client: a lock that anybody can trigger with wrong credentials locks the owner out as
 * well (a denial of service of the only login), and a lock needs a key to lock by. The personal edition is one person, so
 * there is nobody to tell apart. The public reaches the service through one reverse proxy of the operator on the same host,
 * and the address that proxy hands on ({@code X-Forwarded-For}, believed from the loopback address only, see
 * {@code application.yml}) is a record of the request and not an identity: depending on how the proxy is set up, every user
 * arrives as one address or as whatever the proxy passes on, so a lock keyed by that address would lock the owner together
 * with the attacker, or leave the attacker free to rotate. The forwarded address is kept for the records only. A token made
 * by {@code token-generate} (256 bits from {@code SecureRandom}) and a one-time code (100 bits) cannot be guessed, so a delay
 * is all the limit has to do: it makes guessing slower and costs the owner nothing but the same delay. (A token that the
 * owner chose by hand may be weaker, which is why the installer makes the admin token with {@code token-generate}.)
 *
 * <p>Why a delay of each check and no queue: a check beyond the budget waits by itself and is made after its delay. Serialising
 * the checks beyond the budget would let a caller that keeps the queue full keep the owner's check waiting behind it for as long
 * as it sends, which is the denial of service that a lock was; so the limit stays a plain delay and the owner's check is never
 * behind another one.
 *
 * <p>Why this is ours (principle 2.1): Spring Security publishes authentication events
 * ({@code AuthenticationFailureBadCredentialsEvent} and the others) but has no lockout or throttle feature, see
 * https://docs.spring.io/spring-security/reference/servlet/authentication/events.html . The budget is one
 * {@link AtomicReference#updateAndGet} (https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/atomic/AtomicReference.html).
 * The check runs in {@link AdminTokenProvider} (every way of presenting the token: login form, bearer),
 * {@code EnrollmentTokenService} (the one-time code) and {@code PasskeyLoginGuard} (the passkey sign-in).
 */
@Component
public class LoginAttempts {

    /** The global budget: checks counted in the current window ({@code used} is 0 before the first one) and when it ends. */
    private record Budget(int used, long windowEnds) {

        static final Budget NONE = new Budget(0, 0);

        Budget counted(long now, long window) {
            boolean fresh = used == 0 || windowEnds - now <= 0;
            return new Budget((fresh ? 0 : used) + 1, fresh ? now + window : windowEnds);
        }

        /** One count given back (the check succeeded); never below none. */
        Budget refunded() {
            return used > 0 ? new Budget(used - 1, windowEnds) : this;
        }
    }

    private final AtomicReference<Budget> everyone = new AtomicReference<>(Budget.NONE);
    private final LongSupplier nanos;
    private final int budget;
    private final Duration delay;
    private final long window;

    public LoginAttempts(LedgerAdminProperties properties, LongSupplier nanos) {
        LedgerAdminProperties.Lockout limits = properties.lockout();
        this.nanos = nanos;
        this.budget = limits.globalMaxFailures();
        this.delay = limits.globalDelay();
        this.window = limits.window().toNanos();
    }

    /**
     * Counts a check and tells how long it waits before the credential is compared: zero while the budget lasts, the
     * global delay once the check is beyond it. It never refuses.
     */
    public Duration admit() {
        long now = nanos.getAsLong();
        Budget counted = everyone.updateAndGet(current -> current.counted(now, window));
        return counted.used() > budget ? delay : Duration.ZERO;
    }

    /** A right credential: the check gives its count back to the budget. */
    public void succeeded() {
        everyone.updateAndGet(Budget::refunded);
    }
}
