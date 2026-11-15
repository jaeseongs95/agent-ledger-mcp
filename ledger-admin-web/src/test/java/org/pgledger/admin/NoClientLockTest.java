package org.pgledger.admin;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.admin.support.AdminTest;
import org.pgledger.admin.support.AdminTestSettings;
import org.pgledger.admin.support.Browser;
import org.pgledger.application.admin.PasskeyService;
import org.pgledger.testing.TestOwners;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * LGN-001 and LGN-002 (defect D-0013, replacing ADM-028 and PSK-LCK-01): there is no lock by client address. Behind the
 * operator's proxy on the same host the remote address is the proxy for everybody, so a lock per address would lock the
 * owner out whenever anybody sent wrong credentials. Whatever number of wrong credentials came before, the right one
 * signs in.
 */
@AdminTest
class NoClientLockTest {

    private static final String WRONG = "wrong-wrong-wrong-wrong";
    private static final String WRONG_CODE = "AAAA-AAAA-AAAA-AAAA-AAAA";
    private static final int MANY = 20;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private PasskeyService passkeys;

    private ResultActions bearer(Browser browser, String token) throws Exception {
        return browser.perform(browser.get("/api/session").accept(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private void fail(Browser browser, int times) throws Exception {
        for (int i = 0; i < times; i++) {
            bearer(browser, WRONG).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        }
    }

    @Test
    @DisplayName("LGN-001: after 20 wrong bearer tokens of one address every one was 401 UNAUTHORIZED (never 429) and the right token is 200; again after 20 more")
    void lgn001_theRightTokenAlwaysPasses() throws Exception {
        Browser browser = new Browser(mvc);

        fail(browser, MANY);
        bearer(browser, AdminTestSettings.TOKEN).andExpect(status().isOk());
        fail(browser, MANY);
        bearer(browser, AdminTestSettings.TOKEN).andExpect(status().isOk());
    }

    @Test
    @DisplayName("LGN-001: the wrong tokens of one address do not touch another address, and a wrong token after the right one is 401 again")
    void lgn001_noAddressIsInvolved() throws Exception {
        Browser attacker = new Browser(mvc);
        fail(attacker, MANY);

        bearer(new Browser(mvc), AdminTestSettings.TOKEN).andExpect(status().isOk());
        bearer(attacker, WRONG).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("LGN-002: 20 wrong one-time codes lock nobody: the bearer is 200, and a code that was made signs in (the enrolment session)")
    void lgn002_wrongCodesDoNotLock() throws Exception {
        Browser browser = new Browser(mvc);
        for (int i = 0; i < MANY; i++) {
            browser.signInWithCode(WRONG_CODE).andExpect(status().is3xxRedirection())
                    .andExpect(header().string(HttpHeaders.LOCATION, containsString("/login?error=PASSKEY_CODE_INVALID")));
        }

        bearer(browser, AdminTestSettings.TOKEN).andExpect(status().isOk());
        browser.signInWithCode(passkeys.issueCode(TestOwners.OWNER, false).code()).andExpect(status().is3xxRedirection())
                .andExpect(header().string(HttpHeaders.LOCATION, endsWith("/passkeys")));
        browser.perform(browser.get("/passkeys")).andExpect(status().isOk());
    }
}
