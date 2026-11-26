// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.veridger.admin.console.McpStatus;
import org.veridger.admin.support.AdminTest;
import org.veridger.admin.support.Browser;
import org.veridger.admin.update.UpdateService;
import org.veridger.admin.update.UpdateStatus;
import org.veridger.domain.rule.AttentionKind;
import org.veridger.domain.rule.CheckResult;
import org.veridger.domain.rule.CodeDirection;
import org.veridger.domain.rule.CodeGroup;
import org.veridger.domain.rule.CodeState;
import org.veridger.domain.rule.CriterionState;
import org.veridger.domain.rule.HistoryEntity;
import org.veridger.domain.rule.HistoryOp;
import org.veridger.domain.rule.IssueStatus;
import org.veridger.domain.rule.RelationEnd;
import org.veridger.domain.rule.Scope;
import org.veridger.domain.rule.SpecStatus;
import org.veridger.domain.rule.TaskState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * ADM-047: every sentence the owner sees comes from the message bundles, in Korean or English by the one locale resolver
 * of the application; a language that is not supported shows the English text.
 */
@AdminTest
class MessagesTest {

    private static final Path TEMPLATES = Path.of("src", "main", "resources", "templates");
    private static final Pattern KEY = Pattern.compile("#\\{([A-Za-z][A-Za-z0-9.]*)[}(]");

    @Autowired
    private MockMvc mvc;

    private static Properties bundle(String name) throws IOException {
        Properties bundle = new Properties();
        bundle.load(Files.newBufferedReader(Path.of("src", "main", "resources", "messages", name), StandardCharsets.UTF_8));
        return bundle;
    }

    @Test
    @DisplayName("ADM-047: the Korean and the English bundle have the same keys")
    void bundlesHaveTheSameKeys() throws IOException {
        Set<String> english = new TreeSet<>(bundle("admin.properties").stringPropertyNames());
        Set<String> korean = new TreeSet<>(bundle("admin_ko.properties").stringPropertyNames());

        assertThat(korean).isEqualTo(english);
    }

    @Test
    @DisplayName("ADM-047: every message key a template names, also the ones made from an enum, is in both bundles; "
            + "no template has a sentence that is not a key")
    void everyKeyOfTheTemplatesExists() throws IOException {
        Set<String> names = new TreeSet<>();
        try (Stream<Path> files = Files.list(TEMPLATES)) {
            for (Path file : files.toList()) {
                Matcher matcher = KEY.matcher(Files.readString(file));
                while (matcher.find()) {
                    names.add(matcher.group(1));
                }
            }
        }
        for (McpStatus.State state : McpStatus.State.values()) {
            names.add("mcp.state." + state);
        }
        for (McpStatus.Reason reason : McpStatus.Reason.values()) {
            names.add("mcp.reason." + reason);
        }
        for (AttentionKind kind : AttentionKind.values()) {
            names.add("attention." + kind);
        }
        // D-0016: the end of a relation (issue page) and the direction of a relation kind (code page)
        for (RelationEnd end : RelationEnd.values()) {
            names.add("issue.relationEnd." + end.token());
        }
        for (CodeDirection direction : CodeDirection.values()) {
            names.add("codes.direction." + direction.token());
        }
        for (UpdateService.Availability availability : UpdateService.Availability.values()) {
            names.add("updates.availability." + availability);
        }
        for (UpdateStatus.State state : UpdateStatus.State.values()) {
            names.add("updates.queue." + state);
        }
        for (IssueStatus status : IssueStatus.values()) {
            names.add("issue.status." + status);
        }
        for (SpecStatus status : SpecStatus.values()) {
            names.add("spec.status." + status);
        }
        for (TaskState state : TaskState.values()) {
            names.add("task.state." + state);
        }
        for (CriterionState state : CriterionState.values()) {
            names.add("criterion.state." + state);
        }
        for (HistoryEntity entity : HistoryEntity.values()) {
            names.add("entity." + entity.token());
        }
        for (HistoryOp op : HistoryOp.values()) {
            names.add("history.op." + op);
        }
        for (CodeGroup group : CodeGroup.values()) {
            names.add("codes.group." + group);
        }
        for (CodeState state : CodeState.values()) {
            names.add("codes.state." + state);
        }
        for (CheckResult result : CheckResult.values()) {
            names.add("deliverable.checkResult." + result.token());
        }
        for (Scope scope : Scope.values()) {
            names.add("grants.scope." + scope.token());
        }
        List<String> missing = new ArrayList<>();
        for (String file : List.of("admin.properties", "admin_ko.properties")) {
            Properties bundle = bundle(file);
            names.stream().filter(key -> !bundle.containsKey(key) && !key.startsWith("error.") && !key.startsWith("$"))
                    .forEach(key -> missing.add(file + ": " + key));
        }
        assertThat(missing).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ko", "ko-KR,ko;q=0.9,en;q=0.5", "en-US,en;q=0.9", "fr-FR,fr;q=0.9", "de"})
    @DisplayName("ADM-047: the language of the page and of the problem follows Accept-Language; any other language is English")
    void theLanguageFollowsTheHeader(String acceptLanguage) throws Exception {
        boolean korean = acceptLanguage.startsWith("ko");
        Browser browser = new Browser(mvc);

        String page = browser.perform(browser.get("/login").header(HttpHeaders.ACCEPT_LANGUAGE, acceptLanguage))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        browser.perform(browser.get("/api/overview").accept(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.ACCEPT_LANGUAGE, acceptLanguage)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value(korean ? "인증이 필요합니다." : "Authentication is required."));

        // the login page has no token form any more: it offers the passkey and the one-time code (the sign-in text per language)
        assertThat(page).contains(korean ? "패스키로 로그인" : "Sign in with a passkey")
                .contains(korean ? "<html lang=\"ko\">" : "<html lang=\"en\">");
        assertThat(page).doesNotContain(korean ? "Sign in with a passkey" : "패스키로 로그인");
    }
}
