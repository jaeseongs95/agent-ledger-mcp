package org.pgledger.admin.security;

import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.pgledger.admin.app.LedgerAdminProperties;
import org.springframework.stereotype.Component;

/**
 * The limit on credential checks (the admin token, the one-time code, the passkey sign-in): a <em>global</em> budget that
 * delays and never refuses. {@code globalMaxFailures} checks of all callers together within {@code window} are the budget;
 * every check beyond it is <em>delayed</em> by {@code globalDelay} (it is still made, so the right credential always logs
 * in, only later). The check is counted when it is admitted ({@link #admit}), in one atomic step with the decision about
 * the delay, so concurrent checks cannot overshoot the budget; a success gives its count back ({@link #succeeded}), so the
 * owner's own successful logins do not use the budget up. Time is read from the monotonic {@link Ticker} (production:
 * {@code System.nanoTime}), never from the wall clock, so a change of the system time cannot end or extend a window; times
 * of the ticker are compared by their difference ({@code end - now}), as the documentation of {@link System#nanoTime}
 * asks, because a value of the ticker may be negative and may wrap around.
 *
 * <p>Why there is no lock, and no client: a lock that anybody can trigger with wrong credentials locks the owner out as
 * well (a denial of service of the only login), and a lock needs a client to lock. The personal edition has none: both
 * services listen on loopback only and are published by the operator's reverse proxy on the same host, so the remote
 * address of a request is the proxy, one address for everybody; a per-address lock would be a lock of everybody, and the
 * address a proxy forwards ({@code X-Forwarded-For}) is a claim of whoever sends the request, so trusting it would be a
 * setting with nothing to trust. The credentials cannot be guessed (the admin token is 256 bits from
 * {@code SecureRandom}, the one-time code 100 bits), so a delay is all the limit has to do: it makes guessing slower and
 * costs the owner nothing but the same delay.
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
    private final Ticker ticker;
    private final int budget;
    private final Duration delay;
    private final long window;

    public LoginAttempts(LedgerAdminProperties properties, Ticker ticker) {
        LedgerAdminProperties.Lockout limits = properties.lockout();
        this.ticker = ticker;
        this.budget = limits.globalMaxFailures();
        this.delay = limits.globalDelay();
        this.window = limits.window().toNanos();
    }

    /**
     * Counts a check and tells how long it waits before the credential is compared: zero while the budget lasts, the
     * global delay once the check is beyond it. It never refuses.
     */
    public Duration admit() {
        long now = ticker.read();
        Budget counted = everyone.updateAndGet(current -> current.counted(now, window));
        return counted.used() > budget ? delay : Duration.ZERO;
    }

    /** A right credential: the check gives its count back to the budget. */
    public void succeeded() {
        everyone.updateAndGet(Budget::refunded);
    }
}
