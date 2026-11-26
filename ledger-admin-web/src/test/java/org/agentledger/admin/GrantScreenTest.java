// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.agentledger.admin.support.AdminTest;
import org.agentledger.admin.support.Browser;
import org.agentledger.admin.support.Browser.Form;
import org.agentledger.admin.support.Seeds;
import org.agentledger.admin.support.Seeds.Seeded;
import org.agentledger.application.admin.GrantService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.util.MultiValueMap;

/**
 * ADM-064 .. ADM-076 (v4.0.0 direct session grants): the grant screen issues, syncs, and revokes scopes directly for
 * {@code <vendor>:<session_id>} authors after the owner signs in, enforcing CSRF and recording every operation in
 * {@code owner_audit} without per-session keys or Bearer tokens.
 */
@AdminTest
class GrantScreenTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private Seeds seeds;

    @Autowired
    private GrantService grants;

    @Autowired
    private JdbcClient jdbc;

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static String newAuthor() {
        return "gemini:grant-screen-" + SEQUENCE.incrementAndGet();
    }

    private ResultActions post(Browser browser, String uri, MultiValueMap<String, String> fields) throws Exception {
        return browser.perform(browser.form(uri, fields, browser.csrfOf("/grants")).accept(MediaType.APPLICATION_JSON));
    }

    private static MultiValueMap<String, String> issue(String author, Seeded project) {
        return Form.of("author", author, "project", project.project(), "scopes", "issue", "scopes", "task");
    }

    @Test
    @DisplayName("ADM-065: granting scopes to a session succeeds in one step without session-key or token registration")
    void adm065_directGrantSucceedsInOneStep() throws Exception {
        Seeded project = seeds.project();
        String author = newAuthor();
        Browser browser = Browser.signedIn(mvc);

        browser.perform(browser.form("/grants/author", issue(author, project), browser.csrfOf("/grants")))
                .andExpect(status().is3xxRedirection());

        assertThat(grants.live(author)).extracting(g -> g.scope().token()).containsExactlyInAnyOrder("issue", "task");
        assertThat(grants.live(author)).allMatch(g -> "user:owner".equals(g.grantedBy().value()));
    }

    @Test
    @DisplayName("ADM-069: a refused grant for an unknown project leaves zero grants and zero audit rows; retrying with a real project succeeds")
    void adm069_retryAfterARefusedGrant() throws Exception {
        Seeded project = seeds.project();
        String author = newAuthor();
        Browser browser = Browser.signedIn(mvc);

        post(browser, "/grants/author", Form.of("author", author, "project", "no-such-project", "scopes", "issue"))
                .andExpect(status().isNotFound());
        assertThat(grants.live(author)).isEmpty();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM owner_audit WHERE subject LIKE :author")
                .param("author", author + "%").query(Integer.class).single()).isZero();

        browser.perform(browser.form("/grants/author",
                Form.of("author", author, "project", project.project(), "scopes", "issue"),
                browser.csrfOf("/grants"))).andExpect(status().is3xxRedirection());
        assertThat(grants.live(author)).extracting(g -> g.scope().token()).containsExactly("issue");
    }

    @Test
    @DisplayName("ADM-067: grant, revoke and a common code change are recorded with the signed-in owner (token:owner), not with the fixed role")
    void adm067_auditActorOfTheToken() throws Exception {
        Seeded project = seeds.project();
        String author = newAuthor();
        Browser browser = Browser.signedIn(mvc);

        browser.perform(browser.form("/grants/author", issue(author, project), browser.csrfOf("/grants")))
                .andExpect(status().is3xxRedirection());
        browser.perform(browser.form("/grants/revoke", Form.of("author", author, "project", project.project(), "scope",
                "task"), browser.csrfOf("/grants"))).andExpect(status().is3xxRedirection());
        browser.perform(browser.form("/codes", Form.of("group", "ISSUE_CATEGORY", "code", "AUDITED", "nameKo", "감사",
                "nameEn", "Audited", "state", "ACTIVE"), browser.csrfOf("/codes")))
                .andExpect(status().is3xxRedirection());

        List<String> rows = jdbc.sql("SELECT operation || ' ' || actor FROM owner_audit WHERE subject IN (:subjects) "
                + "ORDER BY id").param("subjects", List.of(author + "|" + project.project(), author,
                        "ISSUE_CATEGORY/AUDITED")).query(String.class).list();
        assertThat(rows).containsExactly("GRANT_ISSUED token:owner", "GRANT_REVOKED token:owner", "CODE_SET token:owner");
        assertThat(grants.live(author)).allMatch(g -> "user:owner".equals(g.grantedBy().value()));
    }

    @Test
    @DisplayName("ADM-076: adding a project is recorded in the owner audit trail with the signed-in owner")
    void adm076_aNewProjectIsAudited() throws Exception {
        int number = SEQUENCE.incrementAndGet();
        String id = "audit-project-" + number;
        Browser browser = Browser.signedIn(mvc);

        browser.perform(browser.form("/projects", Form.of("id", id, "code", "AUDITP" + number, "title", "Audited"),
                browser.csrfOf("/projects"))).andExpect(status().is3xxRedirection());

        assertThat(jdbc.sql("SELECT operation || ' ' || actor || ' ' || detail FROM owner_audit WHERE subject = :id")
                .param("id", id).query(String.class).list())
                .containsExactly("PROJECT_ADDED token:owner code=AUDITP" + number);
    }

    @Test
    @DisplayName("ADM-077: mutating /grants endpoints enforce CSRF (403 without token) and retired key/token/preview routes are gone")
    void adm077_csrfEnforcedAndRetiredRoutesGone() throws Exception {
        Seeded project = seeds.project();
        String author = newAuthor();
        Browser browser = Browser.signedIn(mvc);

        browser.perform(browser.form("/grants/author", issue(author, project), null).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
        browser.perform(browser.form("/grants/sync", issue(author, project), null).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
        browser.perform(browser.form("/grants/revoke", Form.of("author", author), null).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
        assertThat(grants.live(author)).isEmpty();

        for (String retired : List.of("/grants/preview", "/grants/keys/revoke", "/grants/tokens",
                "/grants/tokens/revoke")) {
            post(browser, retired, Form.of("author", author)).andExpect(status().is4xxClientError());
        }
    }
}
