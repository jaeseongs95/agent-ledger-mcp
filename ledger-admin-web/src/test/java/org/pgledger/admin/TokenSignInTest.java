package org.pgledger.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.admin.support.AdminTest;
import org.pgledger.admin.support.AdminTestSettings;
import org.pgledger.admin.support.Browser;
import org.pgledger.admin.support.Browser.Form;
import org.pgledger.admin.support.TestPasskeys;
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
    @DisplayName("PSK-TOK-02: the right token signs in (a new session id, the session of the owner); a wrong token and the agent token do not")
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
        String before = owner.session().getId();
        owner.perform(owner.form("/login", Form.of("username", "owner", "token", AdminTestSettings.TOKEN), ownerCsrf))
                .andExpect(status().is3xxRedirection());

        assertThat(owner.session().getId()).isNotEqualTo(before);
        owner.perform(owner.get("/api/session").accept(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.authenticated").value(true)).andExpect(jsonPath("$.signIn").value("TOKEN"));
    }

    @Test
    @DisplayName("PSK-TOK-03: five wrong tokens lock the client address, and the right token does not help during the lock")
    void tok03_lockPerAddress() throws Exception {
        Browser attacker = new Browser(mvc);
        for (int i = 0; i < 5; i++) {
            attacker.perform(attacker.form("/login", Form.of("username", "owner", "token", "wrong-" + i + AdminTestSettings.TOKEN),
                    attacker.csrfOf("/login"))).andExpect(status().is3xxRedirection());
        }

        attacker.perform(attacker.form("/login", Form.of("username", "owner", "token", AdminTestSettings.TOKEN),
                attacker.csrfOf("/login")).accept(MediaType.APPLICATION_JSON)).andExpect(status().is4xxClientError());
        attacker.perform(attacker.get("/api/session").accept(MediaType.APPLICATION_JSON)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("PSK-TOK-04: a token session writes only with the approval of a passkey: 403 PASSKEY_APPROVAL_REQUIRED without it, stored with it")
    void tok04_aWriteStillNeedsAPasskey() throws Exception {
        Browser owner = new Browser(mvc).withPasskey(TestPasskeys.OWNER);
        owner.perform(owner.form("/login", Form.of("username", "owner", "token", AdminTestSettings.TOKEN),
                owner.csrfOf("/login"))).andExpect(status().is3xxRedirection());
        String id = "t" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        var project = Form.of("id", id, "code", id.toUpperCase(java.util.Locale.ROOT), "title", "t");

        owner.perform(owner.formWithoutApproval("/projects", project, owner.csrfOf("/projects"))
                .accept(MediaType.APPLICATION_JSON)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PASSKEY_APPROVAL_REQUIRED"));
        assertThat(count(id)).isZero();

        owner.perform(owner.form("/projects", project, owner.csrfOf("/projects"))).andExpect(status().is3xxRedirection());
        assertThat(count(id)).isEqualTo(1);
    }

    private int count(String id) {
        return jdbc.sql("SELECT COUNT(*) FROM project WHERE id = :id").param("id", id).query(Integer.class).single();
    }
}
