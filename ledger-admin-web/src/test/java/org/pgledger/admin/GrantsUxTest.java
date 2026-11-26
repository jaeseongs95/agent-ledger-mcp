// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.admin.support.AdminTest;
import org.pgledger.admin.support.AuthorKeys;
import org.pgledger.admin.support.Browser;
import org.pgledger.admin.support.Browser.Form;
import org.pgledger.admin.support.Seeds;
import org.pgledger.admin.support.Seeds.Seeded;
import org.pgledger.application.admin.GrantService;
import org.pgledger.application.admin.SessionKeyService;
import org.pgledger.application.admin.SessionTokenService;
import org.pgledger.application.admin.SessionTokenService.IssueCommand;
import org.pgledger.domain.model.SessionGrant;
import org.pgledger.testing.TestOwners;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.MultiValueMap;

/**
 * Verifies the redesigned session-centric /grants access console: unified session cards (key, token-only, orphan
 * grants), single-transaction /grants/sync (add + remove + no-op + clear + orphan revoke + passkey enforcement), and
 * optional 1-step bearer token + initial grants issuance with atomic rollback.
 */
@AdminTest
class GrantsUxTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private Seeds seeds;

    @Autowired
    private GrantService grants;

    @Autowired
    private SessionKeyService keys;

    @Autowired
    private SessionTokenService tokens;

    @Autowired
    private JdbcClient jdbc;

    private static String author(String prefix) {
        return "agent:" + prefix + "-" + SEQUENCE.incrementAndGet();
    }

    @Test
    @DisplayName("GUX-01: /grants groups keyed, token-only (0 grants), and credential-less sessions into session cards in EN and KO")
    void gux01_sessionCardsGroupAllSessionStates() throws Exception {
        Seeded project = seeds.project();
        String keyedAuthor = author("keyed");
        String tokenOnlyAuthor = author("token-only");
        String orphanAuthor = author("orphan");
        Browser browser = Browser.signedIn(mvc);

        AuthorKeys.Pair keyPair = AuthorKeys.newPair();
        browser.perform(browser.form("/grants", keyPair.confirmed(Form.of("author", keyedAuthor, "project",
                project.project(), "scopes", "issue", "scopes", "task")), browser.csrfOf("/grants")))
                .andExpect(status().is3xxRedirection());

        SessionTokenService.IssuedToken tokenOnly = tokens.issue(new IssueCommand(tokenOnlyAuthor, null, "no-grants"),
                TestOwners.OWNER);

        AuthorKeys.Pair orphanKey = AuthorKeys.newPair();
        browser.perform(browser.form("/grants", orphanKey.confirmed(Form.of("author", orphanAuthor, "project",
                project.project(), "scopes", "issue")), browser.csrfOf("/grants")))
                .andExpect(status().is3xxRedirection());
        keys.revoke(orphanAuthor, TestOwners.OWNER);

        String enHtml = browser.perform(browser.get("/grants").header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(enHtml).contains("data-session-card", "/grants/sync")
                .contains(keyedAuthor, tokenOnlyAuthor, tokenOnly.tokenPrefix(), orphanAuthor)
                .contains("data-cred=\"none\"", "No active credential");

        String koHtml = browser.perform(browser.get("/grants").header(HttpHeaders.ACCEPT_LANGUAGE, "ko"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(koHtml).contains("세션 권한 관리", "연결된 세션 목록", "변경 저장", "인증 수단 없음");
    }

    @Test
    @DisplayName("GUX-02: POST /grants/sync atomically adds new scopes, revokes removed scopes, keeps unchanged grant IDs, and records both audit entries")
    void gux02_syncAtomicallyAddsAndRevokesScopes() throws Exception {
        Seeded project = seeds.project();
        String session = author("sync");
        Browser browser = Browser.signedIn(mvc);

        browser.perform(browser.form("/grants", AuthorKeys.newPair().confirmed(Form.of("author", session, "project",
                project.project(), "scopes", "issue", "scopes", "spec", "scopes", "task", "note", "initial")),
                browser.csrfOf("/grants"))).andExpect(status().is3xxRedirection());

        Long originalIssueId = grants.live(session).stream()
                .filter(g -> "issue".equals(g.scope().token())).findFirst().orElseThrow().id();
        Long originalTaskId = grants.live(session).stream()
                .filter(g -> "task".equals(g.scope().token())).findFirst().orElseThrow().id();

        MultiValueMap<String, String> syncForm = Form.of("author", session, "project", project.project(),
                "scopes", "issue", "scopes", "task", "scopes", "deliverable", "scopes", "verify",
                "note", "synced");
        browser.perform(browser.form("/grants/sync", syncForm, browser.csrfOf("/grants")))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("flashKey", "flash.grantSynced"));

        List<SessionGrant> after = grants.live(session);
        assertThat(after).extracting(g -> g.scope().token())
                .containsExactlyInAnyOrder("issue", "task", "deliverable", "verify");
        assertThat(after.stream().filter(g -> "issue".equals(g.scope().token())).findFirst().orElseThrow().id())
                .isEqualTo(originalIssueId);
        assertThat(after.stream().filter(g -> "task".equals(g.scope().token())).findFirst().orElseThrow().id())
                .isEqualTo(originalTaskId);

        List<String> ops = jdbc.sql("SELECT operation FROM owner_audit WHERE subject = :sub ORDER BY id")
                .param("sub", session + "|" + project.project()).query(String.class).list();
        assertThat(ops).containsExactly("GRANT_ISSUED", "GRANT_REVOKED", "GRANT_ISSUED");
    }

    @Test
    @DisplayName("GUX-03: POST /grants/sync handles no-op without audit churn, blocks adding without credential, and allows clearing orphan grants")
    void gux03_syncNoOpAndOrphanClear() throws Exception {
        Seeded project = seeds.project();
        String session = author("sync-edge");
        Browser browser = Browser.signedIn(mvc);

        browser.perform(browser.form("/grants", AuthorKeys.newPair().confirmed(Form.of("author", session, "project",
                project.project(), "scopes", "issue", "scopes", "task")), browser.csrfOf("/grants")))
                .andExpect(status().is3xxRedirection());

        int auditBefore = jdbc.sql("SELECT COUNT(*) FROM owner_audit WHERE subject = :sub")
                .param("sub", session + "|" + project.project()).query(Integer.class).single();

        browser.perform(browser.form("/grants/sync", Form.of("author", session, "project", project.project(),
                "scopes", "issue", "scopes", "task"), browser.csrfOf("/grants")))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("flashKey", "flash.grantUnchanged"));

        int auditAfterNoOp = jdbc.sql("SELECT COUNT(*) FROM owner_audit WHERE subject = :sub")
                .param("sub", session + "|" + project.project()).query(Integer.class).single();
        assertThat(auditAfterNoOp).isEqualTo(auditBefore);

        keys.revoke(session, TestOwners.OWNER);

        // Adding a new scope without any live credential must still be refused (409 SESSION_KEY_REQUIRED)
        browser.perform(browser.form("/grants/sync", Form.of("author", session, "project", project.project(),
                "scopes", "issue", "scopes", "task", "scopes", "verify"), browser.csrfOf("/grants"))
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SESSION_KEY_REQUIRED"));
        assertThat(grants.live(session)).hasSize(2);

        // Clearing all scopes (empty scopes) must succeed even when credential is gone
        browser.perform(browser.form("/grants/sync", Form.of("author", session, "project", project.project()),
                browser.csrfOf("/grants")))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("flashKey", "flash.grantSynced"));
        assertThat(grants.live(session)).isEmpty();
    }

    @Test
    @DisplayName("GUX-04: POST /grants/sync requires WebAuthn passkey approval")
    void gux04_syncRequiresPasskeyApproval() throws Exception {
        Browser browser = Browser.signedIn(mvc);
        String csrf = browser.csrfOf("/grants");

        browser.perform(browser.formWithoutApproval("/grants/sync",
                Form.of("author", author("no-passkey"), "project", "p1", "scopes", "issue"), csrf)
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PASSKEY_APPROVAL_REQUIRED"));
    }

    @Test
    @DisplayName("GUX-05: POST /grants/tokens grants optional initial scopes atomically and rolls back token on invalid or missing project")
    void gux05_bearerTokenWithOptionalInitialGrantsAndRollback() throws Exception {
        Seeded project = seeds.project();
        String happySession = author("tok-grant");
        String missingProjSession = author("tok-noproj");
        String badProjSession = author("tok-badproj");
        Browser browser = Browser.signedIn(mvc);

        // 1. Happy path: 1-step token + initial project scopes
        String issuedHtml = browser.perform(browser.form("/grants/tokens",
                Form.of("author", happySession, "project", project.project(),
                        "scopes", "issue", "scopes", "task", "note", "one-step"),
                browser.csrfOf("/grants")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Matcher rawMatch = Pattern.compile("id=\"issued-raw-token\">(lgt_[A-Za-z0-9_-]+)</code>").matcher(issuedHtml);
        assertThat(rawMatch.find()).isTrue();
        assertThat(tokens.authenticate(rawMatch.group(1))).isPresent();
        assertThat(issuedHtml).contains(project.project(), "Initial permissions granted:");
        assertThat(grants.live(happySession)).extracting(g -> g.scope().token())
                .containsExactlyInAnyOrder("issue", "task");

        // 2. Scopes selected without a project -> 400 VALIDATION_FAILED and ZERO token created
        browser.perform(browser.form("/grants/tokens",
                Form.of("author", missingProjSession, "project", "", "scopes", "issue"),
                browser.csrfOf("/grants")).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].path").value("project"));
        assertThat(tokens.live(missingProjSession)).isEmpty();
        assertThat(grants.live(missingProjSession)).isEmpty();

        // 3. Unknown project with scopes -> 404 NOT_FOUND and ZERO token created (transaction rolled back),
        //    HTML request stays on /grants with preserved author input
        String recoveryHtml = browser.perform(browser.form("/grants/tokens",
                Form.of("author", badProjSession, "project", "no-such-project-gux", "scopes", "issue"),
                browser.csrfOf("/grants")))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        assertThat(recoveryHtml).contains("Could not save the form").contains("value=\"" + badProjSession + "\"");
        assertThat(tokens.live(badProjSession)).isEmpty();
        assertThat(grants.live(badProjSession)).isEmpty();
    }
}
