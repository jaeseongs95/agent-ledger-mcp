// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.veridger.admin.support.AdminTest;
import org.veridger.admin.support.Browser;
import org.veridger.admin.support.Browser.Form;
import org.veridger.admin.support.Seeds;
import org.veridger.admin.support.Seeds.Seeded;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.MultiValueMap;

/**
 * Verifies the overhauled admin web UI/UX contracts: grouped sidebar shell, static design system assets,
 * overview drilldown links, list filter reset & empty states, history detail deep-links, flash feedback,
 * and HTML form error recovery with input preservation (while preserving JSON ProblemDetail responses).
 */
@AdminTest
class UiOverhaulTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private Seeds seeds;

    @Test
    @DisplayName("UI-01: static tokens.css and admin-ui.js are served and the console shell renders grouped sidebar, theme toggle, and top-of-page passkey-status")
    void ui01_shellAndStaticAssets() throws Exception {
        Browser browser = Browser.signedIn(mvc);

        browser.perform(browser.get("/css/tokens.css").accept(MediaType.ALL)).andExpect(status().isOk());
        browser.perform(browser.get("/js/admin-ui.js").accept(MediaType.ALL)).andExpect(status().isOk());

        String overview = browser.perform(browser.get("/")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(overview).contains("id=\"sidebar\"", "id=\"theme-toggle\"", "/css/tokens.css", "/js/admin-ui.js")
                .contains("Work &amp; Monitoring", "Governance &amp; Access", "System &amp; Security");
        assertThat(overview.indexOf("id=\"passkey-status\"")).isLessThan(overview.indexOf("<main id=\"main\""));
    }

    @Test
    @DisplayName("UI-02: overview renders clickable KPI, attention drilldown, and status/severity distribution links")
    void ui02_overviewDrilldownLinks() throws Exception {
        Seeded project = seeds.project();
        seeds.issue(project, "drilldown issue");
        seeds.spec(project, "drilldown spec");
        seeds.task(project, "drilldown task");
        seeds.criterion(project, "R1", "1.0", "C1");
        Browser browser = Browser.signedIn(mvc);

        String html = browser.perform(browser.get("/?project=" + project.project())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html)
                .contains("href=\"/issues?project=" + project.project() + "\"")
                .contains("href=\"/specs?project=" + project.project() + "\"")
                .contains("href=\"/tasks?project=" + project.project() + "\"")
                .contains("href=\"/criteria?project=" + project.project() + "\"")
                .contains("href=\"/issues?project=" + project.project() + "&amp;attention=OPEN_NO_OWNER\"")
                .contains("href=\"/issues?project=" + project.project() + "&amp;status=OPEN\"");
    }

    @Test
    @DisplayName("UI-03: list pages distinguish filtered empty state with reset link, and history deep-links issue/spec/task rows")
    void ui03_listFilterResetAndHistoryDeepLinks() throws Exception {
        Seeded project = seeds.project();
        String issueId = seeds.issue(project, "history linked issue");
        String taskId = seeds.task(project, "history linked task");
        Browser browser = Browser.signedIn(mvc);

        String filteredEmpty = browser.perform(browser.get("/issues?project=" + project.project() + "&owner=nobody-here"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(filteredEmpty).contains("No rows.")
                .contains("No items match the current filters.")
                .contains("href=\"/issues?project=" + project.project() + "\"");

        String history = browser.perform(browser.get("/history?project=" + project.project()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(history).contains("href=\"/issues/" + issueId + "\"")
                .contains("href=\"/tasks/detail?id=" + taskId + "\"")
                .contains("data-changes-json");
    }

    @Test
    @DisplayName("UI-04: HTML form validation error stays on the page with inline error banner and preserved input; JSON keeps ProblemDetail")
    void ui04_htmlFormRecoveryPreservesInputAndJsonKeepsProblemDetail() throws Exception {
        Browser browser = Browser.signedIn(mvc);
        MultiValueMap<String, String> invalidProject = Form.of("id", "keep-this-id", "code", "lowercase", "title", "Preserved Title");

        String html = browser.perform(browser.form("/projects", invalidProject, browser.csrfOf("/projects")))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("Could not save the form")
                .contains("value=\"keep-this-id\"")
                .contains("value=\"lowercase\"")
                .contains("value=\"Preserved Title\"");

        browser.perform(browser.form("/projects", invalidProject, browser.csrfOf("/projects"))
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].path").value("project_code"));
    }

    @Test
    @DisplayName("UI-05: successful project creation sets a flash message for the redirected page")
    void ui05_flashFeedbackOnRedirect() throws Exception {
        Browser browser = Browser.signedIn(mvc);
        MultiValueMap<String, String> valid = Form.of("id", "ui-flash-proj", "code", "UIFLASH", "title", "Flash Project");

        browser.perform(browser.form("/projects", valid, browser.csrfOf("/projects")))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("flashKey", "flash.projectAdded"));
    }

    @Test
    void projectVersionFormsKeepIssueLinksAcrossRenames() throws Exception {
        Seeded project = seeds.project();
        String issue = seeds.issue(project, "Issue linked to the review version");
        String unrelated = seeds.issue(project, "Issue without a version");
        Browser browser = Browser.signedIn(mvc);
        String versions = "/projects/versions";
        String versionsCsrf = browser.csrfOf(versions + "?project=" + project.project());
        var create = Form.of("project", project.project(), "name", "Review draft", "description", "First review");
        browser.perform(browser.form(versions, create, versionsCsrf)).andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("flashKey", "versions.saved"));
        browser.perform(browser.form(versions, create, versionsCsrf)).andExpect(status().isConflict());

        String detail = "/issues/" + issue;
        String detailCsrf = browser.csrfOf(detail);
        browser.perform(browser.form(detail + "/versions", Form.of("name", "Review draft"), detailCsrf))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attribute("flashKey", "versions.issueSaved"));

        var edit = Form.of("project", project.project(), "originalName", "Review draft", "name", "Review final",
                "description", "Ready for decision");
        browser.perform(browser.form(versions + "/edit", edit, versionsCsrf)).andExpect(status().is3xxRedirection());
        String filtered = browser.perform(browser.get("/issues").param("project", project.project())
                .param("projectVersion", "Review final")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(filtered).contains(issue, "Review final").doesNotContain(unrelated);

        browser.perform(browser.form(detail + "/versions", Form.of("name", "Review final", "remove", "true"), detailCsrf))
                .andExpect(status().is3xxRedirection());
        String unlinked = browser.perform(browser.get("/issues").param("project", project.project())
                .param("projectVersion", "Review final")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(unlinked).doesNotContain(issue);
    }
}
