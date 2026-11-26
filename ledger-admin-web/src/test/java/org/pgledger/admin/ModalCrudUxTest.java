// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.admin.support.AdminTest;
import org.pgledger.admin.support.AuthorKeys;
import org.pgledger.admin.support.Browser;
import org.pgledger.admin.support.Browser.Form;
import org.pgledger.admin.support.Seeds;
import org.pgledger.admin.support.Seeds.Seeded;
import org.pgledger.application.admin.CommonCodeService;
import org.pgledger.application.admin.SessionTokenService;
import org.pgledger.application.admin.SessionTokenService.IssueCommand;
import org.pgledger.testing.TestOwners;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.MultiValueMap;

/**
 * Verifies the unified button-triggered modal dialog CRUD and per-screen search UI across Projects ({@code /projects}),
 * Grants ({@code /grants}), and Common Codes ({@code /codes}), including HTML 4xx modal auto-reopen recovery and zero
 * mutating forms outside native {@code <dialog>} elements.
 */
@AdminTest
class ModalCrudUxTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private Seeds seeds;

    @Autowired
    private CommonCodeService codes;

    @Autowired
    private SessionTokenService tokens;

    private static String stripDialogs(String html) {
        return html.replaceAll("(?s)<dialog\\b[^>]*>.*?</dialog>", "");
    }

    @Test
    @DisplayName("MUX-01: /projects renders search UI and moves project creation into a native <dialog> that auto-reopens on HTML 4xx")
    void mux01_projectsSearchAndModal() throws Exception {
        Seeded project = seeds.project();
        Browser browser = Browser.signedIn(mvc);

        String enHtml = browser.perform(browser.get("/projects").header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(enHtml)
                .contains("id=\"project-search\"", "data-table-search=\"project-rows\"")
                .contains("data-modal-open=\"modal-project-add\"")
                .contains("<dialog id=\"modal-project-add\"")
                .contains(project.project(), project.code());
        assertThat(stripDialogs(enHtml))
                .as("no project mutation form should remain outside <dialog>")
                .doesNotContain("action=\"/projects\"");

        String koHtml = browser.perform(browser.get("/projects").header(HttpHeaders.ACCEPT_LANGUAGE, "ko"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(koHtml).contains("+ 프로젝트 추가", "프로젝트 검색");

        MultiValueMap<String, String> invalid = Form.of("id", "bad id", "code", "lowercase", "title", "Keep Title");
        String recoveryHtml = browser.perform(browser.form("/projects", invalid, browser.csrfOf("/projects")))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertThat(recoveryHtml)
                .contains("id=\"modal-project-add\"")
                .contains("data-open-on-load=\"true\"")
                .contains("value=\"bad id\"", "value=\"Keep Title\"");
    }

    @Test
    @DisplayName("MUX-02: /codes renders search+filter toolbar and moves add/edit/deactivate/reactivate into native <dialog> modals")
    void mux02_codesSearchAndModals() throws Exception {
        codes.set(new CommonCodeService.CodeCommand("ISSUE_CATEGORY", "MUX_OFF", "비활성시험", "Inactive Test",
                null, null, 99, "INACTIVE"), TestOwners.OWNER);
        Browser browser = Browser.signedIn(mvc);

        String enHtml = browser.perform(browser.get("/codes").header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(enHtml)
                .contains("id=\"code-search\"", "data-code-toolbar", "data-filter-group=\"ALL\"", "data-filter-state=\"ALL\"")
                .contains("data-modal-open=\"modal-code-editor\"", "data-code-action=\"add\"", "data-code-action=\"edit\"")
                .contains("data-modal-open=\"modal-code-state\"", "data-code-state-action=\"deactivate\"",
                        "data-code-state-action=\"reactivate\"")
                .contains("<dialog id=\"modal-code-editor\"", "<dialog id=\"modal-code-state\"");
        assertThat(stripDialogs(enHtml))
                .as("no common code mutation form should remain outside <dialog>")
                .doesNotContain("action=\"/codes\"");

        String koHtml = browser.perform(browser.get("/codes").header(HttpHeaders.ACCEPT_LANGUAGE, "ko"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(koHtml).contains("+ 공통코드 추가", "공통코드 검색", "비활성화", "재활성화");

        MultiValueMap<String, String> invalid = Form.of("group", "ISSUE_CATEGORY", "code", "bad code", "nameKo", "이름");
        String recoveryHtml = browser.perform(browser.form("/codes", invalid, browser.csrfOf("/codes")))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertThat(recoveryHtml)
                .contains("id=\"modal-code-editor\"")
                .contains("data-open-on-load=\"true\"")
                .contains("value=\"bad code\"");
    }

    @Test
    @DisplayName("MUX-03: /grants renders search UI and moves all add/edit/revoke forms into native <dialog> modals with auto-reopen on preview and 4xx")
    void mux03_grantsSearchAndModals() throws Exception {
        Seeded project = seeds.project();
        String session = "agent:mux-" + SEQUENCE.incrementAndGet();
        Browser browser = Browser.signedIn(mvc);

        AuthorKeys.Pair keyPair = AuthorKeys.newPair();
        browser.perform(browser.form("/grants", keyPair.confirmed(Form.of("author", session, "project",
                project.project(), "scopes", "issue", "scopes", "task")), browser.csrfOf("/grants")))
                .andExpect(status().is3xxRedirection());
        tokens.issue(new IssueCommand(session, null, "mux-token"), TestOwners.OWNER);

        String enHtml = browser.perform(browser.get("/grants").header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(enHtml)
                .contains("id=\"session-search\"", "data-session-toolbar")
                .contains("data-modal-open=\"modal-connect-session\"")
                .contains("data-modal-open=\"modal-edit-scopes\"")
                .contains("data-modal-open=\"modal-add-project\"")
                .contains("data-modal-open=\"modal-revoke-grant\"")
                .contains("data-modal-open=\"modal-revoke-key\"")
                .contains("data-modal-open=\"modal-revoke-token\"")
                .contains("<dialog id=\"modal-connect-session\"", "<dialog id=\"modal-edit-scopes\"",
                        "<dialog id=\"modal-add-project\"", "<dialog id=\"modal-revoke-grant\"",
                        "<dialog id=\"modal-revoke-key\"", "<dialog id=\"modal-revoke-token\"");

        String outsideDialogs = stripDialogs(enHtml);
        assertThat(outsideDialogs)
                .as("no grant/key/token mutation form should remain outside <dialog>")
                .doesNotContain("action=\"/grants\"")
                .doesNotContain("action=\"/grants/sync\"")
                .doesNotContain("action=\"/grants/author\"")
                .doesNotContain("action=\"/grants/revoke\"")
                .doesNotContain("action=\"/grants/keys/revoke\"")
                .doesNotContain("action=\"/grants/tokens\"")
                .doesNotContain("action=\"/grants/tokens/revoke\"");

        // Preview opens the fingerprint confirmation modal automatically
        AuthorKeys.Pair nextKey = AuthorKeys.newPair();
        String previewHtml = browser.perform(browser.form("/grants/preview",
                nextKey.pasted(Form.of("author", "agent:mux-prev-" + SEQUENCE.incrementAndGet(),
                        "project", project.project(), "scopes", "issue")), browser.csrfOf("/grants")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(previewHtml)
                .contains("<dialog id=\"modal-key-confirm\"")
                .contains("data-open-on-load=\"true\"")
                .contains(nextKey.fingerprint());

        // HTML 4xx on token issuance auto-reopens modal-connect-session with preserved author
        String badSession = "agent:mux-err-" + SEQUENCE.incrementAndGet();
        String errHtml = browser.perform(browser.form("/grants/tokens",
                Form.of("author", badSession, "project", "no-such-proj-mux", "scopes", "issue"),
                browser.csrfOf("/grants")))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        assertThat(errHtml)
                .contains("id=\"modal-connect-session\"")
                .contains("data-open-on-load=\"true\"")
                .contains("value=\"" + badSession + "\"");
    }
}
