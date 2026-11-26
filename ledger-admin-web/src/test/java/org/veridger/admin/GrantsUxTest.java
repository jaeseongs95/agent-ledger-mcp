// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.veridger.admin.support.AdminTest;
import org.veridger.admin.support.Browser;
import org.veridger.admin.support.Browser.Form;
import org.veridger.admin.support.Seeds;
import org.veridger.admin.support.Seeds.Seeded;
import org.veridger.application.admin.GrantService;
import org.veridger.domain.model.SessionGrant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.MultiValueMap;

/**
 * Verifies the v4.0.0 session-centric /grants access console: direct 1-step session grants without keys/tokens,
 * unified session cards in EN & KO, atomic single-transaction /grants/sync (add + remove + no-op + clear), CSRF
 * enforcement, and HTML 4xx recovery with preserved input.
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
    private JdbcClient jdbc;

    private static String author(String prefix) {
        return "agent:" + prefix + "-" + SEQUENCE.incrementAndGet();
    }

    @Test
    @DisplayName("GUX-01: /grants groups granted sessions into session cards in EN and KO without key or token ceremony")
    void gux01_sessionCardsRenderInEnAndKo() throws Exception {
        Seeded project = seeds.project();
        String firstAuthor = author("first");
        String secondAuthor = author("second");
        Browser browser = Browser.signedIn(mvc);

        browser.perform(browser.form("/grants/author", Form.of("author", firstAuthor, "project",
                project.project(), "scopes", "issue", "scopes", "task"), browser.csrfOf("/grants")))
                .andExpect(status().is3xxRedirection());
        browser.perform(browser.form("/grants/author", Form.of("author", secondAuthor, "project",
                project.project(), "scopes", "spec"), browser.csrfOf("/grants")))
                .andExpect(status().is3xxRedirection());

        String enHtml = browser.perform(browser.get("/grants").header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(enHtml).contains("data-session-card", "/grants/sync", "/grants/author")
                .contains(firstAuthor, secondAuthor)
                .doesNotContain("/grants/preview", "/grants/tokens", "/grants/keys/revoke");

        String koHtml = browser.perform(browser.get("/grants").header(HttpHeaders.ACCEPT_LANGUAGE, "ko"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(koHtml).contains("세션 권한 관리", "권한이 부여된 세션 목록", "변경 저장");
    }

    @Test
    @DisplayName("GUX-02: POST /grants/sync atomically adds new scopes, revokes removed scopes, keeps unchanged grant IDs, and records both audit entries")
    void gux02_syncAtomicallyAddsAndRevokesScopes() throws Exception {
        Seeded project = seeds.project();
        String session = author("sync");
        Browser browser = Browser.signedIn(mvc);

        browser.perform(browser.form("/grants/author", Form.of("author", session, "project",
                project.project(), "scopes", "issue", "scopes", "spec", "scopes", "task", "note", "initial"),
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
    @DisplayName("GUX-03: POST /grants/sync handles no-op without audit churn and allows clearing all scopes")
    void gux03_syncNoOpAndClearAll() throws Exception {
        Seeded project = seeds.project();
        String session = author("sync-edge");
        Browser browser = Browser.signedIn(mvc);

        browser.perform(browser.form("/grants/author", Form.of("author", session, "project",
                project.project(), "scopes", "issue", "scopes", "task"), browser.csrfOf("/grants")))
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

        browser.perform(browser.form("/grants/sync", Form.of("author", session, "project", project.project()),
                browser.csrfOf("/grants")))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("flashKey", "flash.grantSynced"));
        assertThat(grants.live(session)).isEmpty();
    }

    @Test
    @DisplayName("GUX-04: POST /grants/sync enforces CSRF (403 without token) and owner authentication")
    void gux04_syncRequiresCsrfAndOwnerSession() throws Exception {
        Seeded project = seeds.project();
        Browser browser = Browser.signedIn(mvc);

        browser.perform(browser.form("/grants/sync",
                Form.of("author", author("no-csrf"), "project", project.project(), "scopes", "issue"), null)
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());

        Browser anonymous = new Browser(mvc);
        anonymous.perform(anonymous.form("/grants/sync",
                Form.of("author", author("anon"), "project", project.project(), "scopes", "issue"),
                anonymous.csrfOf("/login")).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GUX-05: POST /grants/author returns HTML 404 on unknown project with preserved author input and zero grants")
    void gux05_grantAuthorHtmlRecoveryOnUnknownProject() throws Exception {
        String badProjSession = author("badproj");
        Browser browser = Browser.signedIn(mvc);

        String recoveryHtml = browser.perform(browser.form("/grants/author",
                Form.of("author", badProjSession, "project", "no-such-project-gux", "scopes", "issue"),
                browser.csrfOf("/grants")))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        assertThat(recoveryHtml).contains("Could not save the form").contains("value=\"" + badProjSession + "\"");
        assertThat(grants.live(badProjSession)).isEmpty();
    }
}
