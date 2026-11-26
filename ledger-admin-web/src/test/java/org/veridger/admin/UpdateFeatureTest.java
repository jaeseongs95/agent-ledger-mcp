// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClient;
import org.veridger.admin.app.LedgerAdminProperties;
import org.veridger.admin.support.AdminTest;
import org.veridger.admin.support.Browser;
import org.veridger.admin.support.Browser.Form;
import org.veridger.admin.update.ChecksumVerifier;
import org.veridger.admin.update.FileUpdateQueue;
import org.veridger.admin.update.GitHubReleaseClient;
import org.veridger.admin.update.SemVer;
import org.veridger.admin.update.UpdateService;
import org.veridger.admin.update.UpdateStatus;
import org.veridger.testing.TestOwners;

/**
 * Verifies the Admin Web self-update feature: SemVer comparison, SHA256SUMS verification, SSRF host-allowlist blocking,
 * schema-migration safety blocking, atomic staging of {@code request.properties}, rollback status rendering, and CSRF /
 * owner-session protection on {@code /updates} and {@code /updates/apply}.
 */
@AdminTest
class UpdateFeatureTest {

    @Autowired
    private MockMvc mvc;

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static LedgerAdminProperties props(String apiBaseUrl, Path updateDir, String currentVersion,
            int schemaVersion) {
        return new LedgerAdminProperties("admin-token-0123456789", "agent-token-0123456789",
                List.of("127.0.0.1", "localhost"),
                org.springframework.util.unit.DataSize.ofKilobytes(64),
                updateDir.resolve("jdbc-driver.yaml"), updateDir.resolve("drivers"),
                new LedgerAdminProperties.Lockout(Duration.ofSeconds(60), 50, Duration.ofSeconds(1)),
                new LedgerAdminProperties.Oidc(null, null),
                new LedgerAdminProperties.Mcp(null, Duration.ofSeconds(2)),
                org.veridger.admin.app.SignInMethod.TOKEN,
                new LedgerAdminProperties.Passkey(null, List.of(), "Ledger admin"),
                new LedgerAdminProperties.Update(true, "jaeseongs95/veridger", apiBaseUrl,
                        List.of("127.0.0.1", "localhost"), updateDir, Duration.ofSeconds(5),
                        currentVersion, schemaVersion));
    }

    private static RestClient testRestClient() {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                java.net.http.HttpClient.newBuilder()
                        .version(java.net.http.HttpClient.Version.HTTP_1_1)
                        .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                        .connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(3));
        return RestClient.builder().requestFactory(factory).build();
    }

    private HttpServer startReleaseServer(AtomicReference<String> releaseJson, Map<String, byte[]> files)
            throws IOException {
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/repos/jaeseongs95/veridger/releases/latest", exchange -> {
            byte[] body = releaseJson.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        http.createContext("/download/", exchange -> {
            String name = exchange.getRequestURI().getPath().substring("/download/".length());
            byte[] data = files.get(name);
            if (data == null) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(200, data.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(data);
            }
        });
        http.start();
        this.server = http;
        return http;
    }

    @Test
    @DisplayName("UPD-01: UpdateService verifies SHA256SUMS and atomically stages server JARs + request.properties")
    void upd01_verifyAndStageHappyPath(@TempDir Path tempDir) throws Exception {
        byte[] cliBytes = "cli-4.1.1-jar".getBytes(StandardCharsets.UTF_8);
        byte[] mcpBytes = "mcp-4.1.1-jar".getBytes(StandardCharsets.UTF_8);
        byte[] adminBytes = "admin-4.1.1-jar".getBytes(StandardCharsets.UTF_8);
        String sums = sha256Hex(cliBytes) + "  ledger-cli.jar\n"
                + sha256Hex(mcpBytes) + "  ledger-mcp-server.jar\n"
                + sha256Hex(adminBytes) + "  ledger-admin-web.jar\n";
        Map<String, byte[]> files = Map.of(
                "ledger-cli.jar", cliBytes,
                "ledger-mcp-server.jar", mcpBytes,
                "ledger-admin-web.jar", adminBytes,
                "SHA256SUMS", sums.getBytes(StandardCharsets.UTF_8));
        AtomicReference<String> json = new AtomicReference<>("");
        HttpServer http = startReleaseServer(json, files);
        String base = "http://127.0.0.1:" + http.getAddress().getPort();
        json.set("""
                {
                  "tag_name": "v4.1.1",
                  "html_url": "https://github.com/jaeseongs95/veridger/releases/tag/v4.1.1",
                  "body": "Patch release. Schema-Version: V11",
                  "draft": false,
                  "prerelease": false,
                  "assets": [
                    {"name":"ledger-cli.jar","browser_download_url":"%s/download/ledger-cli.jar","size":13},
                    {"name":"ledger-mcp-server.jar","browser_download_url":"%s/download/ledger-mcp-server.jar","size":13},
                    {"name":"ledger-admin-web.jar","browser_download_url":"%s/download/ledger-admin-web.jar","size":15},
                    {"name":"SHA256SUMS","browser_download_url":"%s/download/SHA256SUMS","size":260}
                  ]
                }
                """.formatted(base, base, base, base));

        Path updateDir = tempDir.resolve("update");
        LedgerAdminProperties p = props(base, updateDir, "4.1.0", 11);
        Clock fixed = Clock.fixed(Instant.parse("2026-11-26T12:00:00Z"), ZoneOffset.UTC);
        UpdateService service = new UpdateService(p, new GitHubReleaseClient(p, testRestClient()),
                new ChecksumVerifier(), new FileUpdateQueue(p), fixed);

        UpdateService.UpdateView before = service.inspect();
        assertThat(before.availability()).isEqualTo(UpdateService.Availability.UPDATE_AVAILABLE);
        assertThat(before.canApply()).isTrue();
        assertThat(before.latestVersion()).isEqualTo("4.1.1");

        UpdateService.ApplyResult result = service.applyLatest(TestOwners.OWNER);
        assertThat(result.accepted()).isTrue();
        assertThat(result.flashKey()).isEqualTo("updates.flash.queued");

        Path requestFile = updateDir.resolve("request.properties");
        assertThat(requestFile).isRegularFile();
        assertThat(updateDir.resolve("staged/ledger-cli.jar")).hasBinaryContent(cliBytes);
        assertThat(updateDir.resolve("staged/ledger-mcp-server.jar")).hasBinaryContent(mcpBytes);
        assertThat(updateDir.resolve("staged/ledger-admin-web.jar")).hasBinaryContent(adminBytes);
        assertThat(updateDir.resolve("staged/SHA256SUMS")).isRegularFile();

        Properties req = new Properties();
        try (var reader = Files.newBufferedReader(requestFile, StandardCharsets.UTF_8)) {
            req.load(reader);
        }
        assertThat(req.getProperty("targetVersion")).isEqualTo("4.1.1");
        assertThat(req.getProperty("sha256.ledger-cli.jar")).isEqualTo(sha256Hex(cliBytes));

        // Second apply while pending is rejected cleanly
        UpdateService.ApplyResult second = service.applyLatest(TestOwners.OWNER);
        assertThat(second.accepted()).isFalse();
        assertThat(second.flashKey()).isEqualTo("updates.error.alreadyPending");
    }

    @Test
    @DisplayName("UPD-02: Tampered JAR fails SHA-256 verification and stages nothing")
    void upd02_checksumMismatchFailsClosed(@TempDir Path tempDir) throws Exception {
        byte[] goodCli = "cli-good".getBytes(StandardCharsets.UTF_8);
        byte[] tamperedCli = "cli-TAMPERED".getBytes(StandardCharsets.UTF_8);
        byte[] mcpBytes = "mcp".getBytes(StandardCharsets.UTF_8);
        byte[] adminBytes = "admin".getBytes(StandardCharsets.UTF_8);
        String sums = sha256Hex(goodCli) + "  ledger-cli.jar\n"
                + sha256Hex(mcpBytes) + "  ledger-mcp-server.jar\n"
                + sha256Hex(adminBytes) + "  ledger-admin-web.jar\n";
        Map<String, byte[]> files = Map.of(
                "ledger-cli.jar", tamperedCli,
                "ledger-mcp-server.jar", mcpBytes,
                "ledger-admin-web.jar", adminBytes,
                "SHA256SUMS", sums.getBytes(StandardCharsets.UTF_8));
        AtomicReference<String> json = new AtomicReference<>("");
        HttpServer http = startReleaseServer(json, files);
        String base = "http://127.0.0.1:" + http.getAddress().getPort();
        json.set("""
                {
                  "tag_name": "v4.1.2",
                  "html_url": "https://github.com/jaeseongs95/veridger/releases/tag/v4.1.2",
                  "body": "Patch",
                  "draft": false,
                  "prerelease": false,
                  "assets": [
                    {"name":"ledger-cli.jar","browser_download_url":"%s/download/ledger-cli.jar","size":12},
                    {"name":"ledger-mcp-server.jar","browser_download_url":"%s/download/ledger-mcp-server.jar","size":3},
                    {"name":"ledger-admin-web.jar","browser_download_url":"%s/download/ledger-admin-web.jar","size":5},
                    {"name":"SHA256SUMS","browser_download_url":"%s/download/SHA256SUMS","size":260}
                  ]
                }
                """.formatted(base, base, base, base));

        Path updateDir = tempDir.resolve("update");
        LedgerAdminProperties p = props(base, updateDir, "4.1.0", 11);
        UpdateService service = new UpdateService(p, new GitHubReleaseClient(p, testRestClient()),
                new ChecksumVerifier(), new FileUpdateQueue(p), Clock.systemUTC());

        UpdateService.ApplyResult result = service.applyLatest(TestOwners.OWNER);
        assertThat(result.accepted()).isFalse();
        assertThat(result.flashKey()).isEqualTo("updates.error.checksumFailed");
        assertThat(updateDir.resolve("request.properties")).doesNotExist();
    }

    @Test
    @DisplayName("UPD-03: Major version upgrade or higher Schema-Version blocks one-click update")
    void upd03_schemaMigrationBlocksOneClickUpdate(@TempDir Path tempDir) throws Exception {
        AtomicReference<String> json = new AtomicReference<>("""
                {
                  "tag_name": "v4.2.0",
                  "html_url": "https://github.com/jaeseongs95/veridger/releases/tag/v4.2.0",
                  "body": "Requires Schema-Version: V12",
                  "draft": false,
                  "prerelease": false,
                  "assets": []
                }
                """);
        HttpServer http = startReleaseServer(json, Map.of());
        String base = "http://127.0.0.1:" + http.getAddress().getPort();
        Path updateDir = tempDir.resolve("update");
        LedgerAdminProperties p = props(base, updateDir, "4.1.0", 11);
        UpdateService service = new UpdateService(p, new GitHubReleaseClient(p, testRestClient()),
                new ChecksumVerifier(), new FileUpdateQueue(p), Clock.systemUTC());

        UpdateService.UpdateView view = service.inspect();
        assertThat(view.availability()).isEqualTo(UpdateService.Availability.SCHEMA_MIGRATION_REQUIRED);
        assertThat(view.canApply()).isFalse();

        UpdateService.ApplyResult apply = service.applyLatest(TestOwners.OWNER);
        assertThat(apply.accepted()).isFalse();
        assertThat(apply.flashKey()).isEqualTo("updates.error.schemaRequired");
        assertThat(updateDir.resolve("request.properties")).doesNotExist();
    }

    @Test
    @DisplayName("UPD-04: SSRF protection rejects external hosts, HTTPS-to-HTTP downgrade, schema overflow, and malformed asset URLs")
    void upd04_ssrfDownloadHostRejected(@TempDir Path tempDir) throws Exception {
        LedgerAdminProperties p = props("https://api.github.com", tempDir.resolve("update"), "4.1.0", 11);
        GitHubReleaseClient client = new GitHubReleaseClient(p, testRestClient());
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> client.downloadBytes(URI.create("https://169.254.169.254/latest/meta-data/"), 1024))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("allowlist");
        // HTTPS API base refuses plaintext HTTP download downgrade even for an allowed host
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> client.downloadBytes(URI.create("http://localhost/ledger-cli.jar"), 1024))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("scheme");
        assertThat(SemVer.parse("v4.1.0")).isPresent();
        assertThat(SemVer.parse("v4.1.0-rc1")).isEmpty();

        // Schema-Version integer overflow fails closed (blocks one-click update)
        AtomicReference<String> overflowJson = new AtomicReference<>("""
                {
                  "tag_name": "v4.1.9",
                  "html_url": "https://github.com/jaeseongs95/veridger/releases/tag/v4.1.9",
                  "body": "Schema-Version: V99999999999999999999",
                  "draft": false,
                  "prerelease": false,
                  "assets": []
                }
                """);
        HttpServer http = startReleaseServer(overflowJson, Map.of());
        String base = "http://127.0.0.1:" + http.getAddress().getPort();
        LedgerAdminProperties localProps = props(base, tempDir.resolve("overflow"), "4.1.0", 11);
        UpdateService service = new UpdateService(localProps, new GitHubReleaseClient(localProps, testRestClient()),
                new ChecksumVerifier(), new FileUpdateQueue(localProps), Clock.systemUTC());
        assertThat(service.inspect().availability()).isEqualTo(UpdateService.Availability.SCHEMA_MIGRATION_REQUIRED);

        // Malformed asset URL degrades gracefully to CHECK_FAILED instead of uncaught exception
        overflowJson.set("""
                {
                  "tag_name": "v4.1.9",
                  "html_url": "https://github.com/jaeseongs95/veridger/releases/tag/v4.1.9",
                  "body": "Schema-Version: V11",
                  "draft": false,
                  "prerelease": false,
                  "assets": [{"name":"ledger-cli.jar","browser_download_url":"http://[::1","size":10}]
                }
                """);
        assertThat(service.inspect().availability()).isEqualTo(UpdateService.Availability.CHECK_FAILED);
    }

    @Test
    @DisplayName("UPD-05: /updates and POST /updates/apply enforce owner authentication, CSRF, and render EN/KO")
    void upd05_webEndpointSecurityAndRendering() throws Exception {
        Browser anonymous = new Browser(mvc);
        anonymous.perform(anonymous.form("/updates/apply", Form.of(), anonymous.csrfOf("/login"))
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());

        Browser browser = Browser.signedIn(mvc);
        String enHtml = browser.perform(browser.get("/updates").header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(enHtml).contains("System updates", "Host updater status", "jaeseongs95/veridger");

        String koHtml = browser.perform(browser.get("/updates").header(HttpHeaders.ACCEPT_LANGUAGE, "ko"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(koHtml).contains("시스템 업데이트", "호스트 업데이터 상태");

        // POST without CSRF token is rejected with 403
        browser.perform(browser.form("/updates/apply", Form.of(), null).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());

        // FileUpdateQueue reads ROLLED_BACK status cleanly even with UTF-8 BOM, and survives malformed unicode escapes
        Path statusDir = Files.createTempDirectory("veridger-status-test-");
        try {
            Files.writeString(statusDir.resolve("status.properties"),
                    "\uFEFFstate=ROLLED_BACK\ntargetVersion=4.1.2\nupdatedAt=2026-11-26T12:05:00Z\ndetail=healthz timeout\n",
                    StandardCharsets.UTF_8);
            UpdateStatus read = new FileUpdateQueue(props("https://api.github.com", statusDir, "4.1.0", 11))
                    .readStatus();
            assertThat(read.state()).isEqualTo(UpdateStatus.State.ROLLED_BACK);
            assertThat(read.targetVersion()).isEqualTo("4.1.2");
            assertThat(read.detail()).isEqualTo("healthz timeout");

            Files.writeString(statusDir.resolve("status.properties"),
                    "state=FAILED\ndetail=C:\\ProgramData\\veridger\\etc\\update\\bad\n",
                    StandardCharsets.UTF_8);
            UpdateStatus malformed = new FileUpdateQueue(props("https://api.github.com", statusDir, "4.1.0", 11))
                    .readStatus();
            assertThat(malformed.state()).isEqualTo(UpdateStatus.State.IDLE);
        } finally {
            Files.deleteIfExists(statusDir.resolve("status.properties"));
            Files.deleteIfExists(statusDir);
        }
    }
}
