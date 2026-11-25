// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
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
import org.pgledger.domain.model.SessionPublicKey;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.util.MultiValueMap;

/**
 * ADM-064 .. ADM-077: the grant screen registers Ed25519 session keys, issues and revokes standard HTTP Bearer session
 * tokens, grants scopes to enrolled authors, and records who did every owner operation.
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
    private SessionKeyService keys;

    @Autowired
    private SessionTokenService sessionTokens;

    @Autowired
    private JdbcClient jdbc;

    private static final java.util.concurrent.atomic.AtomicInteger SEQUENCE = new java.util.concurrent.atomic.AtomicInteger();

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
    @DisplayName("ADM-064: the preview shows the fingerprint of the pasted key, keeps the form and stores nothing")
    void adm064_previewShowsTheFingerprint() throws Exception {
        Seeded project = seeds.project();
        String author = newAuthor();
        AuthorKeys.Pair key = AuthorKeys.newPair();
        Browser browser = Browser.signedIn(mvc);

        String page = browser.perform(browser.form("/grants/preview", key.pasted(issue(author, project)),
                browser.csrfOf("/grants"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(page).contains(key.fingerprint()).contains(author).contains("name=\"confirmFingerprint\"");
        assertThat(keys.live(author)).isEmpty();
        assertThat(grants.live(author)).isEmpty();
    }

    @Test
    @DisplayName("ADM-064: a preview without a key, or with something that is no Ed25519 key, is 400 VALIDATION_FAILED")
    void adm064_previewNeedsAKey() throws Exception {
        Seeded project = seeds.project();
        Browser browser = Browser.signedIn(mvc);

        post(browser, "/grants/preview", issue(newAuthor(), project)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].path").value("public_key"));
        MultiValueMap<String, String> junk = issue(newAuthor(), project);
        junk.add("publicKey", "this is not a key");
        post(browser, "/grants/preview", junk).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].path").value("public_key"));
    }

    @Test
    @DisplayName("ADM-065: a grant without a key is refused with a clear 400 (path public_key) and issues nothing")
    void adm065_grantNeedsAKey() throws Exception {
        Seeded project = seeds.project();
        String author = newAuthor();
        Browser browser = Browser.signedIn(mvc);

        post(browser, "/grants", issue(author, project)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].path").value("public_key"));

        assertThat(grants.live(author)).isEmpty();
        assertThat(keys.live(author)).isEmpty();
    }

    @Test
    @DisplayName("ADM-065: the confirmation needs the fingerprint of the key and the comparison box; a wrong fingerprint is refused")
    void adm065_theComparisonIsEnforced() throws Exception {
        Seeded project = seeds.project();
        String author = newAuthor();
        AuthorKeys.Pair key = AuthorKeys.newPair();
        AuthorKeys.Pair other = AuthorKeys.newPair();
        Browser browser = Browser.signedIn(mvc);

        post(browser, "/grants", key.pasted(issue(author, project))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].path").value("fingerprint"));
        MultiValueMap<String, String> noBox = key.pasted(issue(author, project));
        noBox.add("fingerprint", key.fingerprint());
        post(browser, "/grants", noBox).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].path").value("fingerprint"));
        MultiValueMap<String, String> wrong = key.pasted(issue(author, project));
        wrong.add("fingerprint", other.fingerprint());
        wrong.add("confirmFingerprint", "yes");
        post(browser, "/grants", wrong).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].path").value("fingerprint"));

        assertThat(grants.live(author)).isEmpty();
        assertThat(keys.live(author)).isEmpty();
    }

    @Test
    @DisplayName("ADM-065 / ADM-PRN-11: the confirmation registers the key for the author and issues the grants; the fingerprint's letter case does not matter")
    void adm065_grantRegistersTheKey() throws Exception {
        Seeded project = seeds.project();
        String author = newAuthor();
        AuthorKeys.Pair key = AuthorKeys.newPair();
        Browser browser = Browser.signedIn(mvc);
        MultiValueMap<String, String> confirmed = key.pasted(issue(author, project));
        confirmed.add("fingerprint", key.fingerprint().toUpperCase(java.util.Locale.ROOT));
        confirmed.add("confirmFingerprint", "yes");

        browser.perform(browser.form("/grants", confirmed, browser.csrfOf("/grants")))
                .andExpect(status().is3xxRedirection());

        SessionPublicKey live = keys.live(author).orElseThrow();
        assertThat(live.fingerprint()).isEqualTo(key.fingerprint());
        assertThat(live.registeredBy().value()).isEqualTo("user:owner");
        assertThat(grants.live(author)).extracting(g -> g.scope().token()).containsExactlyInAnyOrder("issue", "task");
    }

    @Test
    @DisplayName("ADM-065: the key is the same one again for the same author: the grants are issued and the key stays")
    void adm065_sameKeyAgain() throws Exception {
        Seeded project = seeds.project();
        String author = newAuthor();
        AuthorKeys.Pair key = AuthorKeys.newPair();
        Browser browser = Browser.signedIn(mvc);

        browser.perform(browser.form("/grants", key.confirmed(issue(author, project)), browser.csrfOf("/grants")))
                .andExpect(status().is3xxRedirection());
        long id = keys.live(author).orElseThrow().id();
        browser.perform(browser.form("/grants", key.confirmed(Form.of("author", author, "project", project.project(),
                "scopes", "deliverable")), browser.csrfOf("/grants"))).andExpect(status().is3xxRedirection());

        assertThat(keys.live(author).orElseThrow().id()).isEqualTo(id);
        assertThat(grants.live(author)).extracting(g -> g.scope().token())
                .containsExactlyInAnyOrder("issue", "task", "deliverable");
    }

    @Test
    @DisplayName("ADM-069 (changed by TXA-02): the key and the grants of one submission are one transaction: a refused grant leaves no key registered (and no audit row), and the same submission with a known project registers the key once and issues the grants")
    void adm069_retryAfterARefusedGrant() throws Exception {
        Seeded project = seeds.project();
        String author = newAuthor();
        AuthorKeys.Pair key = AuthorKeys.newPair();
        Browser browser = Browser.signedIn(mvc);

        post(browser, "/grants", key.confirmed(Form.of("author", author, "project", "no-such-project", "scopes",
                "issue"))).andExpect(status().isNotFound());
        assertThat(keys.history(author)).as("the refused grant took the registration of the key back").isEmpty();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM owner_audit WHERE subject = :author").param("author", author)
                .query(Integer.class).single()).isZero();
        browser.perform(browser.form("/grants", key.confirmed(Form.of("author", author, "project", project.project(),
                "scopes", "issue")), browser.csrfOf("/grants"))).andExpect(status().is3xxRedirection());

        assertThat(keys.history(author)).hasSize(1);
        assertThat(grants.live(author)).extracting(g -> g.scope().token()).containsExactly("issue");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM owner_audit WHERE operation = 'KEY_REGISTERED' AND subject = :author")
                .param("author", author).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("ADM-066: another key for an author that has one is refused 409 KEY_REPLACEMENT_NOT_CONFIRMED naming the registered fingerprint and time; nothing changes")
    void adm066_noSilentReplacement() throws Exception {
        Seeded project = seeds.project();
        String author = newAuthor();
        AuthorKeys.Pair first = AuthorKeys.newPair();
        AuthorKeys.Pair intruder = AuthorKeys.newPair();
        Browser browser = Browser.signedIn(mvc);
        browser.perform(browser.form("/grants", first.confirmed(issue(author, project)), browser.csrfOf("/grants")))
                .andExpect(status().is3xxRedirection());

        post(browser, "/grants", intruder.confirmed(Form.of("author", author, "project", project.project(), "scopes",
                "deliverable"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("KEY_REPLACEMENT_NOT_CONFIRMED"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString(first.fingerprint())))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("2026-11-05T")));

        assertThat(keys.live(author).orElseThrow().fingerprint()).isEqualTo(first.fingerprint());
        assertThat(grants.live(author)).extracting(g -> g.scope().token()).containsExactlyInAnyOrder("issue", "task");
    }

    @Test
    @DisplayName("ADM-066: the preview of such a key shows the registered key's fingerprint and time and the replace control")
    void adm066_previewShowsWhatIsReplaced() throws Exception {
        Seeded project = seeds.project();
        String author = newAuthor();
        AuthorKeys.Pair first = AuthorKeys.newPair();
        AuthorKeys.Pair second = AuthorKeys.newPair();
        Browser browser = Browser.signedIn(mvc);
        browser.perform(browser.form("/grants", first.confirmed(issue(author, project)), browser.csrfOf("/grants")))
                .andExpect(status().is3xxRedirection());

        String page = browser.perform(browser.form("/grants/preview", second.pasted(issue(author, project)),
                browser.csrfOf("/grants"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(page).contains(first.fingerprint()).contains(second.fingerprint()).contains("name=\"replace\"")
                .contains("name=\"replaceFingerprint\"").contains("datetime=\"2026-11-05T");
    }

    @Test
    @DisplayName("ADM-066: with the replace confirmation naming the registered fingerprint, the key is replaced and the grants issued; with a wrong fingerprint it is refused again")
    void adm066_confirmedReplacement() throws Exception {
        Seeded project = seeds.project();
        String author = newAuthor();
        AuthorKeys.Pair first = AuthorKeys.newPair();
        AuthorKeys.Pair second = AuthorKeys.newPair();
        Browser browser = Browser.signedIn(mvc);
        browser.perform(browser.form("/grants", first.confirmed(issue(author, project)), browser.csrfOf("/grants")))
                .andExpect(status().is3xxRedirection());

        MultiValueMap<String, String> wrong = second.confirmed(Form.of("author", author, "project", project.project(),
                "scopes", "deliverable"));
        wrong.add("replace", "yes");
        wrong.add("replaceFingerprint", second.fingerprint());
        post(browser, "/grants", wrong).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("KEY_REPLACEMENT_NOT_CONFIRMED"));
        assertThat(keys.live(author).orElseThrow().fingerprint()).isEqualTo(first.fingerprint());

        MultiValueMap<String, String> right = second.confirmed(Form.of("author", author, "project", project.project(),
                "scopes", "deliverable"));
        right.add("replace", "yes");
        right.add("replaceFingerprint", first.fingerprint());
        browser.perform(browser.form("/grants", right, browser.csrfOf("/grants"))).andExpect(status().is3xxRedirection());

        assertThat(keys.live(author).orElseThrow().fingerprint()).isEqualTo(second.fingerprint());
        assertThat(keys.history(author)).hasSize(2);
        assertThat(jdbc.sql("SELECT operation FROM owner_audit WHERE subject = :author ORDER BY id")
                .param("author", author).query(String.class).list())
                .contains("KEY_REGISTERED", "KEY_REPLACED");
    }

    @Test
    @DisplayName("ADM-067: grant, revoke and a common code change are recorded with the signed-in owner (token:owner), not with the fixed role")
    void adm067_auditActorOfTheToken() throws Exception {
        Seeded project = seeds.project();
        String author = newAuthor();
        Browser browser = Browser.signedIn(mvc);

        browser.perform(browser.form("/grants", AuthorKeys.newPair().confirmed(issue(author, project)),
                browser.csrfOf("/grants"))).andExpect(status().is3xxRedirection());
        browser.perform(browser.form("/grants/revoke", Form.of("author", author, "project", project.project(), "scope",
                "task"), browser.csrfOf("/grants"))).andExpect(status().is3xxRedirection());
        browser.perform(browser.form("/codes", Form.of("group", "ISSUE_CATEGORY", "code", "AUDITED", "nameKo", "감사",
                "nameEn", "Audited", "state", "ACTIVE"), browser.csrfOf("/codes")))
                .andExpect(status().is3xxRedirection());

        List<String> rows = jdbc.sql("SELECT operation || ' ' || actor FROM owner_audit WHERE subject IN (:subjects) "
                + "ORDER BY id").param("subjects", List.of(author + "|" + project.project(), author,
                        "ISSUE_CATEGORY/AUDITED")).query(String.class).list();
        assertThat(rows).containsExactly("KEY_REGISTERED token:owner", "GRANT_ISSUED token:owner",
                "GRANT_REVOKED token:owner", "CODE_SET token:owner");
        assertThat(grants.live(author)).allMatch(g -> "user:owner".equals(g.grantedBy().value()));
    }

    @Test
    @DisplayName("ADM-073 (audit2 S1): the whole block that the hook command session-key prints can be pasted in place of the bare key: the preview shows the fingerprint and the server name, the confirmation carries the bare key, and it registers and grants")
    void adm073_theBlockOfTheHookIsAccepted() throws Exception {
        Seeded project = seeds.project();
        String author = newAuthor();
        AuthorKeys.Pair key = AuthorKeys.newPair();
        Browser browser = Browser.signedIn(mvc);

        String page = browser.perform(browser.form("/grants/preview",
                key.pastedBlock(issue(author, project), "ledger-test", author), browser.csrfOf("/grants")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(page).contains(key.fingerprint()).contains("ledger-test").contains(key.publicKey());
        assertThat(page).as("the confirmation carries the bare key, not the four lines").doesNotContain("public-key: ");
        assertThat(keys.live(author)).isEmpty();

        MultiValueMap<String, String> confirm = key.pastedBlock(issue(author, project), "ledger-test", author);
        confirm.add("fingerprint", key.fingerprint());
        confirm.add("confirmFingerprint", "yes");
        browser.perform(browser.form("/grants", confirm, browser.csrfOf("/grants"))).andExpect(status().is3xxRedirection());

        assertThat(keys.live(author).orElseThrow().publicKey()).isEqualTo(key.publicKey());
        assertThat(grants.live(author)).hasSize(2);
    }

    @Test
    @DisplayName("ADM-073 (audit2 S1): a block for another author than the author field is 400 VALIDATION_FAILED path author; a block whose fingerprint line is not the fingerprint of its key, or that lacks a line, is 400 path public_key; nothing is stored")
    void adm073_aBadBlockIsRefused() throws Exception {
        Seeded project = seeds.project();
        String author = newAuthor();
        AuthorKeys.Pair key = AuthorKeys.newPair();
        Browser browser = Browser.signedIn(mvc);

        post(browser, "/grants/preview", key.pastedBlock(issue(author, project), "ledger", newAuthor()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].path").value("author"));
        MultiValueMap<String, String> changed = issue(author, project);
        changed.add("publicKey", key.block("ledger", author).replace(key.fingerprint(), "0".repeat(64)));
        post(browser, "/grants/preview", changed).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].path").value("public_key"));
        MultiValueMap<String, String> incomplete = issue(author, project);
        incomplete.add("publicKey", "author: " + author + "\npublic-key: " + key.publicKey() + "\n");
        post(browser, "/grants/preview", incomplete).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].path").value("public_key"));

        assertThat(keys.live(author)).isEmpty();
        assertThat(grants.live(author)).isEmpty();
    }

    @Test
    @DisplayName("ADM-074 (audit2 S5): the owner role user:owner gets no key from the screen: 400 VALIDATION_FAILED path author")
    void adm074_theOwnerRoleHasNoKey() throws Exception {
        Seeded project = seeds.project();
        AuthorKeys.Pair key = AuthorKeys.newPair();
        Browser browser = Browser.signedIn(mvc);

        post(browser, "/grants", key.confirmed(issue("user:owner", project))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].path").value("author"));

        assertThat(keys.live("user:owner")).isEmpty();
    }

    @Test
    @DisplayName("ADM-075 (audit2 N3): the owner closes the key of an author on the grants page: the page lists the live key, the POST needs the CSRF token and the fingerprint of the live key (a wrong one is 400 path fingerprint, no key is 404), the key is closed with its row kept, the grants stay, and the audit trail has KEY_REVOKED with token:owner")
    void adm075_theOwnerClosesAKey() throws Exception {
        Seeded project = seeds.project();
        String author = newAuthor();
        AuthorKeys.Pair key = AuthorKeys.newPair();
        Browser browser = Browser.signedIn(mvc);
        browser.perform(browser.form("/grants", key.confirmed(issue(author, project)), browser.csrfOf("/grants")))
                .andExpect(status().is3xxRedirection());
        String page = browser.perform(browser.get("/grants")).andReturn().getResponse().getContentAsString();
        assertThat(page).contains(author).contains(key.fingerprint()).contains("/grants/keys/revoke");

        browser.perform(browser.form("/grants/keys/revoke", Form.of("author", author, "fingerprint", key.fingerprint()),
                null).accept(MediaType.APPLICATION_JSON)).andExpect(status().isForbidden());
        post(browser, "/grants/keys/revoke", Form.of("author", author, "fingerprint", AuthorKeys.newPair().fingerprint()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].path").value("fingerprint"));
        post(browser, "/grants/keys/revoke", Form.of("author", author)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].path").value("fingerprint"));
        assertThat(keys.live(author)).as("nothing was closed").isPresent();

        browser.perform(browser.form("/grants/keys/revoke", Form.of("author", author, "fingerprint",
                key.fingerprint().toUpperCase(java.util.Locale.ROOT)), browser.csrfOf("/grants")))
                .andExpect(status().is3xxRedirection());

        assertThat(keys.live(author)).isEmpty();
        assertThat(keys.history(author)).hasSize(1);
        assertThat(grants.live(author)).hasSize(2);
        assertThat(jdbc.sql("SELECT operation || ' ' || actor FROM owner_audit WHERE subject = :author ORDER BY id")
                .param("author", author).query(String.class).list())
                .containsExactly("KEY_REGISTERED token:owner", "KEY_REVOKED token:owner");
        post(browser, "/grants/keys/revoke", Form.of("author", author, "fingerprint", key.fingerprint()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("ADM-076 (audit2 N3): adding a project is recorded in the owner audit trail with the signed-in owner (PROJECT_ADDED, the project id as subject, the code in the detail)")
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
    @DisplayName("ADM-TOK-01: issuing a bearer session token shows the raw secret once, allows granting scopes to the enrolled author without an Ed25519 key, lists only the prefix later, and revokes cleanly")
    void admTok01_bearerSessionTokensAndEnrolledAuthorGrants() throws Exception {
        Seeded project = seeds.project();
        String author = "mcp:bearer-" + SEQUENCE.incrementAndGet();
        Browser browser = Browser.signedIn(mvc);

        String issuedHtml = browser.perform(browser.form("/grants/tokens",
                Form.of("author", author, "expiresAt", "2099-12-31T00:00:00Z", "note", "ci-agent"),
                browser.csrfOf("/grants"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        Matcher rawMatch = Pattern.compile("id=\"issued-raw-token\">(lgt_[A-Za-z0-9_-]+)</code>").matcher(issuedHtml);
        assertThat(rawMatch.find()).isTrue();
        String rawToken = rawMatch.group(1);
        assertThat(sessionTokens.authenticate(rawToken)).isPresent();

        String reloaded = browser.perform(browser.get("/grants")).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString();
        assertThat(reloaded).doesNotContain(rawToken).contains(rawToken.substring(0, 12));

        browser.perform(browser.form("/grants/author", Form.of("author", author, "project", project.project(),
                "scopes", "issue", "scopes", "spec"), browser.csrfOf("/grants")))
                .andExpect(status().is3xxRedirection());
        assertThat(grants.live(author)).extracting(g -> g.scope().token()).containsExactlyInAnyOrder("issue", "spec");

        long tokenId = sessionTokens.live(author).getFirst().id();
        browser.perform(browser.form("/grants/tokens/revoke", Form.of("id", Long.toString(tokenId)),
                browser.csrfOf("/grants"))).andExpect(status().is3xxRedirection());
        assertThat(sessionTokens.live(author)).isEmpty();
        assertThat(sessionTokens.authenticate(rawToken)).isEmpty();
    }
}
