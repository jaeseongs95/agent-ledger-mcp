// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.agentledger.admin.support.AdminTestInfrastructure;
import org.agentledger.admin.support.AdminTestSettings;
import org.agentledger.admin.support.Browser;
import org.agentledger.admin.support.MutableClock;
import org.agentledger.admin.support.RecordingLoginDelay;
import org.agentledger.testing.database.TestDatabaseScanFilter;
import org.agentledger.testing.database.TestDriverRegistration;
import org.agentledger.testing.database.TestSchemaInitializer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.filter.annotation.TypeExcludeFilters;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * ADM-059 (security audit U6; changed by LGN-004): the window of the budget of credential checks counts on the monotonic
 * ticker of the application, not on the UTC clock. This context has no test ticker, so the application uses its own ticker
 * ({@code System.nanoTime}); setting the wall clock forward by two hours must not end the window, so the check beyond the
 * budget is still delayed. The delay is recorded, not slept.
 */
@ContextConfiguration(initializers = {TestSchemaInitializer.class, TestDriverRegistration.class})
@SpringBootTest(classes = LedgerAdminApplication.class, properties = {AdminTestSettings.TOKEN_PROPERTY,
    AdminTestSettings.AGENT_TOKEN_PROPERTY, "ledger.admin.lockout.global-max-failures=2",
    "ledger.admin.lockout.global-delay=300ms", "admin.test.moving-ticker=false", AdminTestSettings.QUIET_BANNER,
    AdminTestSettings.QUIET_LOG, AdminTestSettings.NO_JMX})
@AutoConfigureMockMvc
@TypeExcludeFilters(TestDatabaseScanFilter.class)
@Import({AdminTestInfrastructure.class, RecordingLoginDelay.Configuration.class})
class MonotonicThrottleTest {

    private static final String WRONG = "wrong-wrong-wrong-wrong";
    private static final Duration DELAY = Duration.ofMillis(300);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private MutableClock clock;

    @Autowired
    private RecordingLoginDelay delays;

    private ResultActions bearer(Browser browser, String token) throws Exception {
        return browser.perform(browser.get("/api/session").accept(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    @Test
    @DisplayName("ADM-059: a jump of the wall clock does not end the window of the budget: the check beyond it is still delayed, and the right token still logs in")
    void adm059_theWallClockDoesNotEndTheWindow() throws Exception {
        Browser browser = new Browser(mvc);
        for (int i = 0; i < 2; i++) {
            bearer(browser, WRONG).andExpect(status().isUnauthorized());
        }
        bearer(browser, AdminTestSettings.TOKEN).andExpect(status().isOk());

        clock.jumpWallClock(Duration.ofHours(2));

        bearer(browser, AdminTestSettings.TOKEN).andExpect(status().isOk());
        assertThat(delays.waits()).containsExactly(Duration.ZERO, Duration.ZERO, DELAY, DELAY);
    }
}
