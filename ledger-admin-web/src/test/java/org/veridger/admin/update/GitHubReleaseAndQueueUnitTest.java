// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.admin.update;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.unit.DataSize;
import org.springframework.web.client.RestClient;
import org.veridger.admin.app.LedgerAdminProperties;
import org.veridger.admin.app.SignInMethod;
import tools.jackson.databind.json.JsonMapper;

/**
 * Exhaustive unit tests for {@link GitHubReleaseClient} and {@link FileUpdateQueue} covering redirect hops, SSRF guards,
 * size caps, schema marker parsing, HTML URL sanitization, and queue state/error branches.
 */
class GitHubReleaseAndQueueUnitTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private static LedgerAdminProperties props(String apiBaseUrl, Path updateDir) {
        return new LedgerAdminProperties("admin-token-0123456789", "agent-token-0123456789",
                List.of("127.0.0.1", "localhost"), DataSize.ofKilobytes(64),
                updateDir.resolve("jdbc-driver.yaml"), updateDir.resolve("drivers"),
                new LedgerAdminProperties.Lockout(Duration.ofSeconds(60), 50, Duration.ofSeconds(1)),
                new LedgerAdminProperties.Oidc(null, null),
                new LedgerAdminProperties.Mcp(null, Duration.ofSeconds(2)),
                SignInMethod.TOKEN,
                new LedgerAdminProperties.Passkey(null, List.of(), "Ledger admin"),
                new LedgerAdminProperties.Update(true, "jaeseongs95/veridger", apiBaseUrl,
                        List.of("127.0.0.1", "localhost"), updateDir, Duration.ofSeconds(5), "4.3.0", 7));
    }

    private static RestClient restClient() {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                java.net.http.HttpClient.newBuilder()
                        .version(java.net.http.HttpClient.Version.HTTP_1_1)
                        .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                        .connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(3));
        return RestClient.builder().requestFactory(factory).build();
    }

    @Test
    @DisplayName("UPD-GH-01 GitHubReleaseClient handles trailing slash, redirects, SSRF/URI guards, non-2xx, draft/prerelease, and size caps")
    void gitHubReleaseClientFailureAndRedirectBranches(@TempDir Path dir) throws IOException {
        AtomicInteger latestStatus = new AtomicInteger(200);
        String[] latestBody = { "{\"tag_name\":\"v4.3.1\",\"html_url\":\"javascript:alert(1)\",\"body\":\"\",\"draft\":false,\"prerelease\":false,\"assets\":[]}" };
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/repos/jaeseongs95/veridger/releases/latest", exchange -> {
            try (exchange) {
                byte[] b = latestBody[0].getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(latestStatus.get(), b.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(b);
                }
            }
        });
        server.createContext("/redir-ok", exchange -> {
            try (exchange) {
                exchange.getResponseHeaders().add("Location", "/final");
                exchange.sendResponseHeaders(302, -1);
            }
        });
        server.createContext("/final", exchange -> {
            try (exchange) {
                byte[] b = "hello-bytes".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, b.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(b);
                }
            }
        });
        server.createContext("/redir-no-loc", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(302, -1);
            }
        });
        server.createContext("/redir-loop", exchange -> {
            try (exchange) {
                exchange.getResponseHeaders().add("Location", "/redir-loop");
                exchange.sendResponseHeaders(302, -1);
            }
        });
        server.createContext("/chunked-big", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, 0);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write("0123456789abcdef".getBytes(StandardCharsets.UTF_8));
                }
            }
        });
        server.createContext("/err500", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(500, -1);
            }
        });
        server.start();

        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        JsonMapper mapper = JsonMapper.builder().build();
        GitHubReleaseClient client = new GitHubReleaseClient(props(base + "/", dir), restClient(), mapper);

        // sanitizeHtmlUrl rejects javascript: and blank body falls back to currentSchemaVersion
        ReleaseInfo info = client.fetchLatest();
        assertThat(info.htmlUrl()).isEmpty();
        assertThat(info.requiredSchemaVersion()).isEqualTo(7);

        // Malformed html_url and valid https html_url
        latestBody[0] = "{\"tag_name\":\"v4.3.1\",\"html_url\":\"not a uri\",\"body\":\"Schema-Version: 99999999999999999\",\"draft\":false,\"prerelease\":false,\"assets\":[]}";
        ReleaseInfo overflow = client.fetchLatest();
        assertThat(overflow.htmlUrl()).isEmpty();
        assertThat(overflow.requiredSchemaVersion()).isEqualTo(Integer.MAX_VALUE);

        // Draft, prerelease, invalid JSON, non-2xx
        latestBody[0] = "{\"tag_name\":\"v4.3.1\",\"html_url\":\"\",\"body\":\"\",\"draft\":true,\"prerelease\":false,\"assets\":[]}";
        assertThatThrownBy(client::fetchLatest).isInstanceOf(IOException.class).hasMessageContaining("stable");

        latestBody[0] = "{\"tag_name\":\"v4.3.1\",\"html_url\":\"\",\"body\":\"\",\"draft\":false,\"prerelease\":true,\"assets\":[]}";
        assertThatThrownBy(client::fetchLatest).isInstanceOf(IOException.class).hasMessageContaining("stable");

        latestBody[0] = "{not-json";
        assertThatThrownBy(client::fetchLatest).isInstanceOf(IOException.class).hasStackTraceContaining("parse");

        latestStatus.set(503);
        assertThatThrownBy(client::fetchLatest).isInstanceOf(IOException.class).hasStackTraceContaining("503");

        // Redirect success, missing Location, redirect loop, HTTP 500, streaming cap
        assertThat(client.downloadBytes(URI.create(base + "/redir-ok"), 64))
                .asString(StandardCharsets.UTF_8).isEqualTo("hello-bytes");
        assertThatThrownBy(() -> client.downloadBytes(URI.create(base + "/redir-no-loc"), 64))
                .isInstanceOf(IOException.class).hasStackTraceContaining("Location");
        assertThatThrownBy(() -> client.downloadBytes(URI.create(base + "/redir-loop"), 64))
                .isInstanceOf(IOException.class).hasMessageContaining("too many redirects");
        assertThatThrownBy(() -> client.downloadBytes(URI.create(base + "/err500"), 64))
                .isInstanceOf(IOException.class).hasStackTraceContaining("500");
        assertThatThrownBy(() -> client.downloadBytes(URI.create(base + "/chunked-big"), 8))
                .isInstanceOf(IOException.class).hasStackTraceContaining("while streaming");

        // URI validation: relative, disallowed scheme, userinfo, non-443 HTTPS port
        assertThatThrownBy(() -> client.downloadBytes(URI.create("/relative"), 64))
                .isInstanceOf(IOException.class).hasMessageContaining("absolute");
        assertThatThrownBy(() -> client.downloadBytes(URI.create("ftp://127.0.0.1/file"), 64))
                .isInstanceOf(IOException.class).hasMessageContaining("scheme");
        assertThatThrownBy(() -> client.downloadBytes(URI.create("http://user:pass@127.0.0.1/file"), 64))
                .isInstanceOf(IOException.class).hasMessageContaining("user information");

        GitHubReleaseClient httpsClient = new GitHubReleaseClient(props("https://api.github.com", dir), restClient(), mapper);
        assertThatThrownBy(() -> httpsClient.downloadBytes(URI.create("https://api.github.com:8443/x"), 64))
                .isInstanceOf(IOException.class).hasMessageContaining("port");

        assertThat(GitHubReleaseClient.parseRequiredSchema(null, 5)).isEqualTo(5);
        assertThat(GitHubReleaseClient.parseRequiredSchema("no marker here", 5)).isEqualTo(5);
        assertThat(GitHubReleaseClient.parseRequiredSchema("schema-version: V12", 5)).isEqualTo(12);
    }

    @Test
    @DisplayName("UPD-FQ-01 FileUpdateQueue handles all status states, BOM/oversized status files, duplicate pending, and path escape")
    void fileUpdateQueueStateAndValidationBranches(@TempDir Path dir) throws IOException {
        Path updateDir = dir.resolve("update");
        FileUpdateQueue queue = new FileUpdateQueue(props("http://127.0.0.1:8080", updateDir));

        // Missing directory -> IDLE
        assertThat(queue.readStatus().state()).isEqualTo(UpdateStatus.State.IDLE);

        // Empty directory -> IDLE
        Files.createDirectories(updateDir);
        assertThat(queue.readStatus().state()).isEqualTo(UpdateStatus.State.IDLE);

        // Status states including BOM prefix and unknown state
        for (String[] pair : new String[][] {
            { "\uFEFFstate=PENDING\ntargetVersion=4.3.1\nupdatedAt=2026-11-27T00:00:00Z\n", "PENDING" },
            { "state=SUCCEEDED\ntargetVersion=4.3.1\n", "SUCCEEDED" },
            { "state=ROLLED_BACK\ntargetVersion=4.3.0\n", "ROLLED_BACK" },
            { "state=FAILED\ndetail=boom\n", "FAILED" },
            { "state=UNKNOWN_VALUE\n", "IDLE" }
        }) {
            Files.writeString(updateDir.resolve("status.properties"), pair[0], StandardCharsets.UTF_8);
            assertThat(queue.readStatus().state().name()).isEqualTo(pair[1]);
        }

        // Oversized status.properties is refused safely -> IDLE
        Files.writeString(updateDir.resolve("status.properties"), "x=" + "a".repeat(70_000), StandardCharsets.UTF_8);
        assertThat(queue.readStatus().state()).isEqualTo(UpdateStatus.State.IDLE);

        // Path traversal in verifiedJars is refused
        Path sample = dir.resolve("sample.jar");
        Files.writeString(sample, "jar", StandardCharsets.UTF_8);
        ReleaseInfo release = new ReleaseInfo("v4.3.1", SemVer.parse("4.3.1").orElseThrow(), "", List.of(), 7);
        assertThatThrownBy(() -> queue.stageAndRequest(release, Map.of("../escape.jar", sample),
                Map.of(), "", "owner", Instant.parse("2026-11-27T00:00:00Z")))
                .isInstanceOf(IOException.class).hasMessageContaining("escaped");

        // Duplicate request.properties is refused
        Files.writeString(updateDir.resolve("request.properties"), "targetVersion=4.3.1\n", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> queue.stageAndRequest(release, Map.of(), Map.of(), "", "owner",
                Instant.parse("2026-11-27T00:00:00Z")))
                .isInstanceOf(IOException.class).hasMessageContaining("already pending");
    }
}
