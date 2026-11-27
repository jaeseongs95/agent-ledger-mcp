// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.util.unit.DataSize;
import org.springframework.web.client.RestClient;
import org.veridger.admin.app.LedgerAdminProperties;
import org.veridger.admin.app.SignInMethod;
import org.veridger.admin.console.Paging;
import org.veridger.admin.support.AdminTest;
import org.veridger.admin.support.Browser;
import org.veridger.admin.update.GitHubReleaseClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Regression tests for v4.3.1 Admin Web security fixes: invalid request charsets (AW-02), malformed Accept headers
 * during error rendering (AW-03), bounded pagination back-trail, and strict HTTPS port validation in
 * {@link GitHubReleaseClient}.
 */
@AdminTest
class SecurityHardeningTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JsonMapper mapper;

    @Test
    @DisplayName("SEC-AW-02: chunked request with unknown charset returns 415 problem detail instead of 500")
    void aw02_invalidCharsetIsRefusedCleanly() throws Exception {
        Browser browser = Browser.signedIn(mvc);
        String csrf = browser.csrfOf("/projects");
        MvcResult result = browser.perform(post("/projects").session(browser.session())
                .header("X-CSRF-TOKEN", csrf)
                .header(HttpHeaders.CONTENT_TYPE, "application/x-www-form-urlencoded; charset=no-such-charset-xyz")
                .accept(MediaType.APPLICATION_JSON)
                .content("id=x".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isUnsupportedMediaType())
                .andReturn();
        assertThat(result.getResponse().getContentType()).contains("application/problem+json");
    }

    @Test
    @DisplayName("SEC-AW-03: malformed Accept header on error response returns 4xx problem detail instead of 500")
    void aw03_malformedAcceptHeaderDoesNotCause500() throws Exception {
        Browser browser = Browser.signedIn(mvc);
        MvcResult result = browser.perform(get("/issues/ZZZ-I-9999").session(browser.session())
                .header(HttpHeaders.ACCEPT, "invalid///media"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(result.getResponse().getContentType()).contains("application/problem+json");
    }

    @Test
    @DisplayName("SEC-AW-HARDEN: Paging caps back-trail length at Paging.MAX_TRAIL entries")
    void pagingCapsBackTrailLength() {
        String hugeBack = IntStream.range(0, 500).mapToObj(i -> "c" + i).collect(Collectors.joining(","));
        Paging paging = Paging.of("/issues", Map.of(), 50, "c500", hugeBack, "c501");

        assertThat(paging.number()).isEqualTo(Paging.MAX_TRAIL + 1);
        assertThat(paging.nextHref()).isNotNull();
        String backParam = paging.nextHref().substring(paging.nextHref().indexOf("back=") + 5);
        assertThat(backParam.split(",")).hasSizeLessThanOrEqualTo(Paging.MAX_TRAIL);
    }

    @Test
    @DisplayName("SEC-AW-HARDEN: GitHubReleaseClient rejects non-443 ports when HTTPS is required")
    void gitHubReleaseClientRejectsUnexpectedHttpsPort(@TempDir Path tempDir) {
        LedgerAdminProperties p = new LedgerAdminProperties("admin-token-0123456789", "agent-token-0123456789",
                List.of("127.0.0.1", "localhost"), DataSize.ofKilobytes(64),
                tempDir.resolve("jdbc-driver.yaml"), tempDir.resolve("drivers"),
                new LedgerAdminProperties.Lockout(Duration.ofSeconds(60), 50, Duration.ofSeconds(1)),
                new LedgerAdminProperties.Oidc(null, null),
                new LedgerAdminProperties.Mcp(null, Duration.ofSeconds(2)),
                SignInMethod.TOKEN,
                new LedgerAdminProperties.Passkey(null, List.of(), "Ledger admin"),
                new LedgerAdminProperties.Update(true, "jaeseongs95/veridger", "https://api.github.com",
                        List.of("github.com", "objects.githubusercontent.com"), tempDir, Duration.ofSeconds(5),
                        "4.3.1", 13));
        GitHubReleaseClient client = new GitHubReleaseClient(p, RestClient.builder().build(), mapper);

        assertThatThrownBy(() -> client.downloadBytes(URI.create("https://github.com:8443/a/b"), 1024))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("port");
    }
}
