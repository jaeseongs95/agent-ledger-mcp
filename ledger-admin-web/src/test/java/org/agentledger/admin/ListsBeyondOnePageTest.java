// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.agentledger.admin.console.Choices;
import org.agentledger.admin.support.AdminTest;
import org.agentledger.admin.support.Browser;
import org.agentledger.domain.rule.CodeGroup;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * ADM-078 (D-0063): the lists of the console that are not paged (the projects of the pages and of the filter forms, the
 * projects of the overview, the common codes) hold every row, not the first {@link Choices#ALL}. The test keeps more rows
 * than that, so it has a context (and so a schema) of its own: the default sign-in is named, which is what tells the test
 * context cache to build another one, and the rows do not reach the other test classes.
 */
@AdminTest
@TestPropertySource(properties = "ledger.admin.sign-in=passkey")
class ListsBeyondOnePageTest {

    /** More rows than one page of the lists ({@link Choices#ALL}). */
    private static final int ROWS = Choices.ALL + 5;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private Choices choices;

    private static boolean filled;

    private static String number(int index) {
        return String.format("%04d", index);
    }

    /** The context is shared by the tests of this class; its schema is filled once. */
    private synchronized void fill() {
        if (filled) {
            return;
        }
        for (int i = 1; i <= ROWS; i++) {
            jdbc.sql("INSERT INTO project (id, code, title, version, created_by) VALUES (:id, :code, :title, 0, 'user:owner')")
                    .param("id", "zl" + number(i)).param("code", "ZL" + number(i)).param("title", "Project " + i).update();
            jdbc.sql("INSERT INTO common_code (code_group, code, name_ko, name_en, sort_order, state, created_by) "
                    + "VALUES ('ISSUE_CATEGORY', :code, 'k', 'k', :sort, 'ACTIVE', 'user:owner')")
                    .param("code", "K" + number(i)).param("sort", 1000 + i).update();
        }
        filled = true;
    }

    @BeforeAll
    static void reset() {
        filled = false;
    }

    @Test
    @DisplayName("ADM-078 (D-0063): the project list of the console and the project choice of the filter forms hold every project, also the last one past the first page")
    void adm075_projectsAreAllListed() throws Exception {
        fill();
        String last = "ZL" + number(ROWS);

        assertThat(choices.project(null).projects()).extracting(project -> project.code()).contains(last)
                .hasSizeGreaterThanOrEqualTo(ROWS);
        Browser owner = Browser.signedIn(mvc);
        String page = owner.perform(owner.get("/projects")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        assertThat(page).contains(last);
    }

    @Test
    @DisplayName("ADM-078 (D-0063): the overview names every project, also the last one past the first page")
    void adm075_overviewHasAllProjects() throws Exception {
        fill();
        Browser owner = Browser.signedIn(mvc);

        String json = owner.perform(owner.get("/api/overview").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        List<String> codes = JsonPath.read(json, "$.projects[*].code");
        assertThat(codes).contains("ZL" + number(ROWS)).hasSizeGreaterThanOrEqualTo(ROWS);
    }

    @Test
    @DisplayName("ADM-078 (D-0063): the common code list of the console and the active codes of a group hold every code, also the last one past the first page")
    void adm075_codesAreAllListed() throws Exception {
        fill();
        String last = "K" + number(ROWS);

        assertThat(choices.codes(CodeGroup.ISSUE_CATEGORY)).extracting(code -> code.code()).contains(last)
                .hasSizeGreaterThanOrEqualTo(ROWS);
        Browser owner = Browser.signedIn(mvc);
        String page = owner.perform(owner.get("/codes")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        assertThat(page).contains(last);
    }
}
