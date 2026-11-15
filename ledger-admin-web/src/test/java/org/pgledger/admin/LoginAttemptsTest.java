package org.pgledger.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.benmanes.caffeine.cache.Ticker;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.admin.app.LedgerAdminProperties;
import org.pgledger.admin.security.LoginAttempts;
import org.springframework.util.unit.DataSize;

/**
 * LGN-010 (defect D-0013): the global budget of credential checks on a ticker the test moves: the checks inside the budget
 * are not delayed, those beyond it get the delay, the window is counted by the difference of the ticker (also when it is
 * negative or wraps around), a success gives its count back, and concurrent checks cannot overshoot the budget. There is no
 * client and nothing is ever refused.
 */
class LoginAttemptsTest {

    private static final Duration WINDOW = Duration.ofSeconds(60);
    private static final Duration DELAY = Duration.ofSeconds(2);
    private static final int BUDGET = 3;
    private static final long SECOND = 1_000_000_000L;

    private final AtomicLong nanos = new AtomicLong();
    private final Ticker ticker = nanos::get;

    private LoginAttempts attempts(int budget) {
        return new LoginAttempts(new LedgerAdminProperties("a-token-of-sixteen-chars", "an-agent-token-of-sixteen",
                List.of("localhost"), DataSize.ofKilobytes(64), Path.of("/unused"), Path.of("/unused-drivers"),
                new LedgerAdminProperties.Lockout(WINDOW, budget, DELAY), new LedgerAdminProperties.Oidc(null, null),
                new LedgerAdminProperties.Mcp(null, Duration.ofSeconds(2)), null,
                new LedgerAdminProperties.Passkey(null, List.of(), "Ledger admin")), ticker);
    }

    private static List<Duration> admitted(LoginAttempts attempts, int times) {
        List<Duration> delays = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            delays.add(attempts.admit());
        }
        return delays;
    }

    @Test
    @DisplayName("LGN-010: three checks are inside the budget (no delay), every check beyond it gets the delay, and nothing is refused")
    void lgn010_theBudgetDelaysAndNeverRefuses() {
        LoginAttempts attempts = attempts(BUDGET);

        assertThat(admitted(attempts, 6)).containsExactly(Duration.ZERO, Duration.ZERO, Duration.ZERO, DELAY, DELAY, DELAY);
    }

    @Test
    @DisplayName("LGN-010: the window counts on the difference of the ticker: 59 s is still the window, 61 s starts a new one")
    void lgn010_theWindow() {
        LoginAttempts attempts = attempts(BUDGET);
        admitted(attempts, 4);

        nanos.addAndGet(59 * SECOND);
        assertThat(attempts.admit()).isEqualTo(DELAY);
        nanos.addAndGet(2 * SECOND);
        assertThat(admitted(attempts, 4)).containsExactly(Duration.ZERO, Duration.ZERO, Duration.ZERO, DELAY);
    }

    @Test
    @DisplayName("LGN-010: a success gives one count back, never below none")
    void lgn010_aSuccessGivesACountBack() {
        LoginAttempts attempts = attempts(BUDGET);
        admitted(attempts, 3);

        attempts.succeeded();

        assertThat(attempts.admit()).as("the count that was given back is used again").isEqualTo(Duration.ZERO);
        assertThat(attempts.admit()).isEqualTo(DELAY);
        LoginAttempts fresh = attempts(BUDGET);
        fresh.succeeded();
        fresh.succeeded();
        assertThat(admitted(fresh, 4)).as("a success without a count does not make credit").containsExactly(Duration.ZERO,
                Duration.ZERO, Duration.ZERO, DELAY);
    }

    @Test
    @DisplayName("LGN-010: a negative ticker, a window that ends exactly at 0 and a ticker that wraps around are windows like any other")
    void lgn010_negativeAndWrappingTickers() {
        for (long start : new long[] {-1_000 * SECOND, -60 * SECOND, Long.MAX_VALUE - 10 * SECOND}) {
            nanos.set(start);
            LoginAttempts attempts = attempts(BUDGET);

            assertThat(admitted(attempts, 4)).as("start " + start).containsExactly(Duration.ZERO, Duration.ZERO,
                    Duration.ZERO, DELAY);
            nanos.addAndGet(30 * SECOND);
            assertThat(attempts.admit()).as("30 s later, start " + start).isEqualTo(DELAY);
            nanos.addAndGet(31 * SECOND);
            assertThat(attempts.admit()).as("61 s later, start " + start).isEqualTo(Duration.ZERO);
        }
    }

    @Test
    @DisplayName("LGN-010: 64 checks at the same moment: exactly the budget of them are not delayed (the count and the decision are one atomic step)")
    void lgn010_concurrentChecksCannotOvershootTheBudget() throws Exception {
        int threads = 64;
        LoginAttempts attempts = attempts(BUDGET);
        CyclicBarrier together = new CyclicBarrier(threads);
        List<Duration> delays = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<Duration>> pending = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                pending.add(pool.submit(() -> {
                    together.await();
                    return attempts.admit();
                }));
            }
            for (Future<Duration> one : pending) {
                delays.add(one.get());
            }
        }

        assertThat(delays.stream().filter(Duration.ZERO::equals).count()).isEqualTo(BUDGET);
        assertThat(delays.stream().filter(DELAY::equals).count()).isEqualTo(threads - BUDGET);
    }
}
