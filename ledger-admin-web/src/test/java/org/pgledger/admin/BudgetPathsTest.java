// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.admin.support.AdminTestInfrastructure;
import org.pgledger.admin.support.AdminTestSettings;
import org.pgledger.admin.support.Browser;
import org.pgledger.admin.support.MutableClock;
import org.pgledger.admin.support.RecordingLoginDelay;
import org.pgledger.admin.support.TestPasskeys;
import org.pgledger.application.admin.PasskeyService;
import org.pgledger.testing.SoftwarePasskey;
import org.pgledger.testing.TestOwners;
import org.pgledger.testing.database.TestDatabaseScanFilter;
import org.pgledger.testing.database.TestDriverRegistration;
import org.pgledger.testing.database.TestSchemaInitializer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.filter.annotation.TypeExcludeFilters;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * LGN-004 and LGN-013 (defect D-0013): the three ways of presenting a credential, the admin token (bearer and form), the
 * one-time code ({@code EnrollmentTokenService}) and the passkey sign-in ({@code PasskeyLoginGuard}), draw on the one global
 * budget and give a success back, through their real paths. The budget is two checks per window and the delay is recorded,
 * not slept ({@link RecordingLoginDelay}), so a check that does not ask the budget, or does not give a success back, changes
 * what is recorded. Every test starts a new window (the test clock moves the ticker of the application).
 */
@ContextConfiguration(initializers = {TestSchemaInitializer.class, TestDriverRegistration.class})
@SpringBootTest(classes = LedgerAdminApplication.class, properties = {AdminTestSettings.TOKEN_PROPERTY,
    AdminTestSettings.AGENT_TOKEN_PROPERTY, "ledger.admin.lockout.global-max-failures=2",
    "ledger.admin.lockout.global-delay=300ms", AdminTestSettings.QUIET_BANNER, AdminTestSettings.QUIET_LOG,
    AdminTestSettings.NO_JMX})
@AutoConfigureMockMvc
@TypeExcludeFilters(TestDatabaseScanFilter.class)
@Import({AdminTestInfrastructure.class, RecordingLoginDelay.Configuration.class})
class BudgetPathsTest {

    private static final String WRONG_CODE = "AAAA-AAAA-AAAA-AAAA-AAAA";
    private static final Duration DELAY = Duration.ofMillis(300);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private MutableClock clock;

    @Autowired
    private RecordingLoginDelay delays;

    @Autowired
    private PasskeyService passkeys;

    /** A new window, and the number of delays recorded before the test starts. */
    private int freshWindow() {
        clock.advance(Duration.ofSeconds(61));
        return delays.waits().size();
    }

    private List<Duration> since(int start) {
        return delays.waits().subList(start, delays.waits().size());
    }

    private ResultActions bearer(String token) throws Exception {
        Browser browser = new Browser(mvc);
        return browser.perform(browser.get("/api/session").accept(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private static MockHttpServletRequestBuilder post(Browser browser, String path, String csrf, String json) {
        var request = MockMvcRequestBuilders.post(path).session(browser.session()).accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON).header("X-CSRF-TOKEN", csrf).with(r -> {
                    r.setRemoteAddr(browser.address());
                    return r;
                });
        return json == null ? request : request.content(json);
    }

    private static String loginBody(SoftwarePasskey.Assertion assertion) {
        return "{\"id\":\"" + assertion.credentialId() + "\",\"rawId\":\"" + assertion.credentialId()
                + "\",\"response\":{\"authenticatorData\":\"" + assertion.authenticatorData() + "\",\"clientDataJSON\":\""
                + assertion.clientDataJson() + "\",\"signature\":\"" + assertion.signature()
                + "\"},\"credType\":\"public-key\",\"clientExtensionResults\":{}}";
    }

    /** One sign-in with a passkey through the endpoints, by the passkey given: the status of {@code /login/webauthn}. */
    private int signInWith(SoftwarePasskey passkey) throws Exception {
        Browser browser = new Browser(mvc);
        String csrf = browser.csrfOf("/login");
        MvcResult options = browser.perform(post(browser, "/webauthn/authenticate/options", csrf, null)).andReturn();
        String challenge = JsonPath.read(options.getResponse().getContentAsString(), "$.challenge");
        SoftwarePasskey.Assertion assertion = passkey.assertion(challenge, TestPasskeys.RP_ID, TestPasskeys.ORIGIN);
        return browser.perform(post(browser, "/login/webauthn", csrf, loginBody(assertion))).andReturn().getResponse().getStatus();
    }

    @Test
    @DisplayName("LGN-013 (a): two wrong one-time codes use the budget up (no delay for them), then the right token as a bearer is delayed and still logs in: the code path draws on the one budget")
    void lgn013_wrongCodesUseTheBudget() throws Exception {
        int start = freshWindow();
        Browser browser = new Browser(mvc);
        browser.signInWithCode(WRONG_CODE).andExpect(status().is3xxRedirection());
        browser.signInWithCode(WRONG_CODE).andExpect(status().is3xxRedirection());

        bearer(AdminTestSettings.TOKEN).andExpect(status().isOk());

        assertThat(since(start)).containsExactly(Duration.ZERO, Duration.ZERO, DELAY);
    }

    @Test
    @DisplayName("LGN-013 (b): two wrong passkey assertions use the budget up, then the right token as a bearer is delayed and still logs in: the passkey path draws on the one budget")
    void lgn013_wrongAssertionsUseTheBudget() throws Exception {
        int start = freshWindow();
        assertThat(signInWith(new SoftwarePasskey())).isGreaterThanOrEqualTo(400);
        assertThat(signInWith(new SoftwarePasskey())).isGreaterThanOrEqualTo(400);

        bearer(AdminTestSettings.TOKEN).andExpect(status().isOk());

        assertThat(since(start)).containsExactly(Duration.ZERO, Duration.ZERO, DELAY);
    }

    @Test
    @DisplayName("LGN-013 (c): a right passkey sign-in gives its count back: three of them in a row (the budget is two) are never delayed, and nothing is delayed after them")
    void lgn013_aPasskeySuccessGivesTheBudgetBack() throws Exception {
        int start = freshWindow();

        for (int i = 0; i < 3; i++) {
            assertThat(signInWith(TestPasskeys.OWNER)).isLessThan(400);
        }
        bearer(AdminTestSettings.TOKEN).andExpect(status().isOk());

        assertThat(since(start)).containsExactly(Duration.ZERO, Duration.ZERO, Duration.ZERO, Duration.ZERO);
    }

    @Test
    @DisplayName("LGN-013 (d): a right one-time code gives its count back: three of them in a row are never delayed")
    void lgn013_aCodeSuccessGivesTheBudgetBack() throws Exception {
        int start = freshWindow();

        for (int i = 0; i < 3; i++) {
            new Browser(mvc).signInWithCode(passkeys.issueCode(TestOwners.OWNER, false).code()).andExpect(status().is3xxRedirection());
        }
        bearer(AdminTestSettings.TOKEN).andExpect(status().isOk());

        assertThat(since(start)).containsExactly(Duration.ZERO, Duration.ZERO, Duration.ZERO, Duration.ZERO);
    }
}
