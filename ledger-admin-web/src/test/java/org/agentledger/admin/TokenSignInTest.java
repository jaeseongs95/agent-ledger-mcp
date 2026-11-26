// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.agentledger.admin.support.AdminTest;
import org.agentledger.admin.support.AdminTestSettings;
import org.agentledger.admin.support.Browser;
import org.agentledger.admin.support.Browser.Form;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * PSK-TOK-01 .. PSK-TOK-04: {@code ledger.admin.sign-in=token}, the personal edition: the fixed admin token signs a browser in
 * through the application's own login page, and a write of that session still needs the approval of a passkey.
 */
@AdminTest
class TokenSignInTest {

    @DynamicPropertySource
    static void token(DynamicPropertyRegistry registry) {
        registry.add("ledger.admin.sign-in", () -> "token");
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Test
    @DisplayName("PSK-TOK-01: with sign-in=token the login page has the token form, no passkey button and no provider link")
    void tok01_pageHasTheTokenForm() throws Exception {
        Browser browser = new Browser(mvc);

        String page = browser.perform(browser.get("/login")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();

        assertThat(page).contains("type=\"password\"").contains("name=\"token\"").doesNotContain("passkey-signin")
                .doesNotContain("/oauth2/authorization/");
    }

    @Test
    @DisplayName("PSK-TOK-02: the right token signs in (the session of the owner; the new session id after a sign-in is ADM-009 of RealServerTest, since MockMvc does not show it); a wrong token and the agent token do not")
    void tok02_theTokenSignsIn() throws Exception {
        Browser wrong = new Browser(mvc);
        String csrf = wrong.csrfOf("/login");
        wrong.perform(wrong.form("/login", Form.of("username", "owner", "token", "wrong-" + AdminTestSettings.TOKEN), csrf))
                .andExpect(status().is3xxRedirection());
        wrong.perform(wrong.form("/login", Form.of("username", "owner", "token", AdminTestSettings.AGENT_TOKEN),
                wrong.csrfOf("/login"))).andExpect(status().is3xxRedirection());
        wrong.perform(wrong.get("/api/session").accept(MediaType.APPLICATION_JSON)).andExpect(status().isUnauthorized());

        Browser owner = new Browser(mvc);
        String ownerCsrf = owner.csrfOf("/login");
        owner.perform(owner.form("/login", Form.of("username", "owner", "token", AdminTestSettings.TOKEN), ownerCsrf))
                .andExpect(status().is3xxRedirection());

        owner.perform(owner.get("/api/session").accept(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.authenticated").value(true)).andExpect(jsonPath("$.signIn").value("TOKEN"));
    }

    @Test
    @DisplayName("PSK-TOK-03 (changed by PSEC, LGN-001): five wrong tokens lock nobody: the right token signs in afterwards, from the same address")
    void tok03_noLock() throws Exception {
        Browser browser = new Browser(mvc);
        for (int i = 0; i < 5; i++) {
            browser.perform(browser.form("/login", Form.of("username", "owner", "token", "wrong-" + i + AdminTestSettings.TOKEN),
                    browser.csrfOf("/login"))).andExpect(status().is3xxRedirection());
        }
        browser.perform(browser.get("/api/session").accept(MediaType.APPLICATION_JSON)).andExpect(status().isUnauthorized());

        browser.perform(browser.form("/login", Form.of("username", "owner", "token", AdminTestSettings.TOKEN),
                browser.csrfOf("/login"))).andExpect(status().is3xxRedirection());

        browser.perform(browser.get("/api/session").accept(MediaType.APPLICATION_JSON)).andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true)).andExpect(jsonPath("$.signIn").value("TOKEN"));
    }

    @Test
    @DisplayName("PSK-TOK-04: a token session writes directly with valid CSRF without passkey re-approval, and is refused 403 without CSRF")
    void tok04_aTokenSessionWritesWithCsrf() throws Exception {
        Browser owner = new Browser(mvc);
        owner.perform(owner.form("/login", Form.of("username", "owner", "token", AdminTestSettings.TOKEN),
                owner.csrfOf("/login"))).andExpect(status().is3xxRedirection());
        String id = "t" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        var project = Form.of("id", id, "code", id.toUpperCase(java.util.Locale.ROOT), "title", "t");

        owner.perform(owner.form("/projects", project, null).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
        assertThat(count(id)).isZero();

        owner.perform(owner.form("/projects", project, owner.csrfOf("/projects"))).andExpect(status().is3xxRedirection());
        assertThat(count(id)).isEqualTo(1);
    }

    @Test
    @DisplayName("PSK-ENR-05 (D-0057): a session opened with the token cannot register a passkey without the one-time code: the registration endpoints are 403 for it")
    void enr05_aTokenSessionCannotRegister() throws Exception {
        Browser owner = new Browser(mvc);
        owner.perform(owner.form("/login", Form.of("username", "owner", "token", AdminTestSettings.TOKEN),
                owner.csrfOf("/login"))).andExpect(status().is3xxRedirection());
        String csrf = owner.csrfOf("/passkeys");

        owner.perform(owner.json("/webauthn/register/options", csrf, null)).andExpect(status().isForbidden());
        owner.perform(owner.json("/webauthn/register", csrf, "{}")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PSK-SIN-03 (D-0058): with sign-in=token the passkey is for approvals only: the options of a passkey sign-in and the sign-in itself are refused before the framework's filters answer, and no session follows")
    void sin03_noPasskeySignIn() throws Exception {
        Browser browser = new Browser(mvc);
        String csrf = browser.csrfOf("/login");

        browser.perform(browser.json("/webauthn/authenticate/options", csrf, null)).andExpect(status().is4xxClientError());
        browser.perform(browser.json("/login/webauthn", csrf, "{}")).andExpect(status().is4xxClientError());

        browser.perform(browser.get("/api/session").accept(MediaType.APPLICATION_JSON)).andExpect(status().isUnauthorized());
    }

    private int count(String id) {
        return jdbc.sql("SELECT COUNT(*) FROM project WHERE id = :id").param("id", id).query(Integer.class).single();
    }
}
