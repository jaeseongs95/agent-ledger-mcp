package org.pgledger.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.admin.support.AdminTestInfrastructure;
import org.pgledger.admin.support.AdminTestSettings;
import org.pgledger.admin.support.Browser;
import org.pgledger.admin.support.RecordingLoginDelay;
import org.pgledger.testing.database.TestDatabaseScanFilter;
import org.pgledger.testing.database.TestDriverRegistration;
import org.pgledger.testing.database.TestSchemaInitializer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.filter.annotation.TypeExcludeFilters;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * ADM-071 (audit2 S4; changed by LGN-004): the window of the budget compares times of the monotonic ticker by their
 * difference. {@code System.nanoTime} may be negative (its origin is arbitrary) and may wrap around, and 0 is a value like any
 * other, so no ticker value may stand for "no window". The ticker of this context is one that the test sets: a negative
 * value, a value where the end of the window is exactly 0, and a value that wraps around. The delay is recorded, not slept.
 */
@ContextConfiguration(initializers = {TestSchemaInitializer.class, TestDriverRegistration.class})
@SpringBootTest(classes = LedgerAdminApplication.class, properties = {AdminTestSettings.TOKEN_PROPERTY,
    AdminTestSettings.AGENT_TOKEN_PROPERTY, "ledger.admin.lockout.global-max-failures=3",
    "ledger.admin.lockout.global-delay=300ms", "admin.test.moving-ticker=false", AdminTestSettings.QUIET_BANNER,
    AdminTestSettings.QUIET_LOG, AdminTestSettings.NO_JMX})
@AutoConfigureMockMvc
@TypeExcludeFilters(TestDatabaseScanFilter.class)
@Import({AdminTestInfrastructure.class, RecordingLoginDelay.Configuration.class,
    NegativeTickerThrottleTest.SettableTickerConfiguration.class})
class NegativeTickerThrottleTest {

    private static final String WRONG = "wrong-wrong-wrong-wrong";
    private static final long SECOND = 1_000_000_000L;
    private static final Duration DELAY = Duration.ofMillis(300);

    /** A ticker that the test sets. */
    static final class SettableTicker implements Ticker {

        private final AtomicLong nanos = new AtomicLong();

        void set(long value) {
            nanos.set(value);
        }

        void advance(Duration by) {
            nanos.addAndGet(by.toNanos());
        }

        @Override
        public long read() {
            return nanos.get();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SettableTickerConfiguration {

        @Bean
        @Primary
        SettableTicker settableTicker() {
            return new SettableTicker();
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SettableTicker ticker;

    @Autowired
    private RecordingLoginDelay delays;

    private ResultActions bearer(Browser browser, String token) throws Exception {
        return browser.perform(browser.get("/api/session").accept(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    /** Three wrong checks use the budget up (none is delayed); the fourth check, the right token, is delayed and logs in. */
    private void useTheBudgetUp(Browser browser) throws Exception {
        int before = delays.waits().size();
        for (int i = 0; i < 3; i++) {
            bearer(browser, WRONG).andExpect(status().isUnauthorized());
        }
        bearer(browser, AdminTestSettings.TOKEN).andExpect(status().isOk());
        List<Duration> mine = delays.waits().subList(before, delays.waits().size());
        assertThat(mine).containsExactly(Duration.ZERO, Duration.ZERO, Duration.ZERO, DELAY);
    }

    private Duration lastDelay() {
        return delays.waits().getLast();
    }

    @Test
    @DisplayName("ADM-071: a negative ticker value: the three checks are inside the budget (no delay), the fourth is delayed, and the window ends after the window length (no delay again)")
    void adm071_aNegativeTicker() throws Exception {
        ticker.set(-1_000 * SECOND);
        Browser browser = new Browser(mvc);

        useTheBudgetUp(browser);
        ticker.advance(Duration.ofSeconds(59));
        bearer(browser, AdminTestSettings.TOKEN).andExpect(status().isOk());
        assertThat(lastDelay()).as("59 s: still the same window").isEqualTo(DELAY);
        ticker.advance(Duration.ofSeconds(2));
        bearer(browser, AdminTestSettings.TOKEN).andExpect(status().isOk());
        assertThat(lastDelay()).as("61 s: a new window").isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("ADM-071: the window that ends exactly at ticker value 0 is a window")
    void adm071_aWindowThatEndsAtZero() throws Exception {
        ticker.set(-60 * SECOND);
        Browser browser = new Browser(mvc);

        useTheBudgetUp(browser);
        ticker.advance(Duration.ofSeconds(61));
        bearer(browser, AdminTestSettings.TOKEN).andExpect(status().isOk());
        assertThat(lastDelay()).isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("ADM-071: the ticker wraps around during the window: the delay holds for the window length and ends after it")
    void adm071_theTickerWrapsAround() throws Exception {
        ticker.set(Long.MAX_VALUE - 10 * SECOND);
        Browser browser = new Browser(mvc);

        useTheBudgetUp(browser);
        ticker.advance(Duration.ofSeconds(30));
        bearer(browser, AdminTestSettings.TOKEN).andExpect(status().isOk());
        assertThat(lastDelay()).isEqualTo(DELAY);
        ticker.advance(Duration.ofSeconds(31));
        bearer(browser, AdminTestSettings.TOKEN).andExpect(status().isOk());
        assertThat(lastDelay()).isEqualTo(Duration.ZERO);
    }
}
