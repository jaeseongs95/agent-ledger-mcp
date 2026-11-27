// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import org.veridger.mcp.config.BoundedStreamableServerTransportProvider;
import org.veridger.mcp.support.McpServerTest;
import org.veridger.mcp.support.McpTestSettings;
import org.veridger.mcp.support.RawHttp;
import org.veridger.mcp.support.RawHttp.Header;
import org.veridger.mcp.support.RawHttp.Reply;

/**
 * Regression tests for v4.3.1 MCP server security fixes:
 * MCP-02 (/healthz excluded from RequestSizeFilter), MCP-03 (BoundedStreamableServerTransportProvider atomic
 * maxSessions + POST-only session survival across keep-alive ticks), MCP-04 (413 application/problem+json even with
 * Accept: text/event-stream), and MCP-05 (/healthz Origin enforcement + duplicate Origin header rejection).
 */
@McpServerTest
@TestPropertySource(properties = {
    "ledger.test.context=security-hardening",
    "ledger.mcp.max-request-size=512B",
    "ledger.mcp.max-sessions=3",
    "ledger.mcp.session-idle-timeout=5s",
    "ledger.mcp.keep-alive-interval=100ms",
    "ledger.mcp.allowed-origins=https://allowed.example"
})
class SecurityHardeningTest {

    private static final Header BEARER = Header.bearer(McpTestSettings.TOKEN);

    @LocalServerPort
    private int port;

    @Autowired
    private BoundedStreamableServerTransportProvider transport;

    @Test
    @DisplayName("SEC-MCP-02: /healthz is outside RequestSizeFilter so an unauthenticated oversized request does not return 413")
    void mcp02_healthzDoesNotBufferChunkedBodies() throws IOException {
        String raw = sendRawHttp("GET /healthz HTTP/1.1\r\nHost: 127.0.0.1:" + port
                + "\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n0\r\n\r\n");
        assertThat(raw).startsWith("HTTP/1.1 200 ");
    }

    @Test
    @DisplayName("SEC-MCP-04: oversized or invalid-charset POST /mcp with Accept: text/event-stream returns 413/415 application/problem+json (never 406)")
    void mcp04_oversizedRequestWithSseAcceptReturns413ProblemJson() throws IOException {
        String bigBody = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"pad\":\""
                + "x".repeat(700) + "\"}}";
        String raw = sendRawHttp("POST /mcp HTTP/1.1\r\nHost: 127.0.0.1:" + port
                + "\r\nAuthorization: Bearer " + McpTestSettings.TOKEN
                + "\r\nAccept: text/event-stream\r\nContent-Type: application/json\r\nContent-Length: "
                + bigBody.length() + "\r\nConnection: close\r\n\r\n" + bigBody);
        assertThat(raw).startsWith("HTTP/1.1 413 ");
        assertThat(raw).containsIgnoringCase("application/problem+json").contains("PAYLOAD_TOO_LARGE");

        String smallBody = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}";
        String badCharset = sendRawHttp("POST /mcp HTTP/1.1\r\nHost: 127.0.0.1:" + port
                + "\r\nAuthorization: Bearer " + McpTestSettings.TOKEN
                + "\r\nAccept: text/event-stream\r\nContent-Type: application/json; charset=no-such-charset-xyz\r\nContent-Length: "
                + smallBody.length() + "\r\nConnection: close\r\n\r\n" + smallBody);
        assertThat(badCharset).startsWith("HTTP/1.1 415 ");
        assertThat(badCharset).containsIgnoringCase("application/problem+json");
    }

    @Test
    @DisplayName("SEC-MCP-05: /healthz rejects disallowed browser Origin and duplicate Origin headers (401 anonymous, 403 authenticated)")
    void mcp05_originValidationCoversHealthzAndDuplicateHeaders(RawHttp http) throws IOException {
        // Non-browser caller without Origin still reaches /healthz
        Reply noOrigin = http.get("/healthz");
        assertThat(noOrigin.status()).isEqualTo(200);

        // Disallowed Origin on /healthz is rejected (401 anonymous, 403 authenticated)
        Reply evilHealthzAnon = http.get("/healthz", new Header("Origin", "https://evil.example"));
        assertThat(evilHealthzAnon.status()).isEqualTo(401);
        Reply evilHealthzAuth = http.get("/healthz", BEARER, new Header("Origin", "https://evil.example"));
        assertThat(evilHealthzAuth.status()).isEqualTo(403);

        // Allowed single Origin on /healthz is permitted
        Reply okHealthz = http.get("/healthz", new Header("Origin", "https://allowed.example"));
        assertThat(okHealthz.status()).isEqualTo(200);

        // Duplicate Origin headers (first allowed, second evil) must be rejected (401 anonymous, 403 authenticated)
        String dupRawAnon = sendRawHttp("GET /healthz HTTP/1.1\r\nHost: 127.0.0.1:" + port
                + "\r\nOrigin: https://allowed.example\r\nOrigin: https://evil.example\r\nConnection: close\r\n\r\n");
        assertThat(dupRawAnon).startsWith("HTTP/1.1 401 ");
        String dupRawAuth = sendRawHttp("GET /healthz HTTP/1.1\r\nHost: 127.0.0.1:" + port
                + "\r\nAuthorization: Bearer " + McpTestSettings.TOKEN
                + "\r\nOrigin: https://allowed.example\r\nOrigin: https://evil.example\r\nConnection: close\r\n\r\n");
        assertThat(dupRawAuth).startsWith("HTTP/1.1 403 ");
    }

    @Test
    @DisplayName("SEC-MCP-03: atomic maxSessions admission under concurrency never exceeds cap, and POST-only sessions survive keep-alive ticks")
    void mcp03_atomicMaxSessionsAndPostOnlySurvival(RawHttp http) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(6);
        List<String> opened = new ArrayList<>();
        try {
            List<Callable<Reply>> tasks = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                tasks.add(() -> http.initialize(BEARER));
            }
            List<Future<Reply>> futures = pool.invokeAll(tasks);
            int ok = 0;
            int rejected = 0;
            for (Future<Reply> f : futures) {
                Reply r = f.get();
                if (r.status() == 200) {
                    ok++;
                    opened.add(r.header("Mcp-Session-Id").orElseThrow());
                } else if (r.status() == 503) {
                    rejected++;
                }
            }
            assertThat(ok).isEqualTo(3);
            assertThat(rejected).isEqualTo(3);
            assertThat(transport.activeSessionCount()).isEqualTo(3);

            // Wait past multiple keep-alive intervals (100ms): POST-only sessions without SSE streams must remain alive
            Thread.sleep(350L);
            for (String session : opened) {
                assertThat(http.listTools(session, BEARER).status()).isEqualTo(200);
            }
        } finally {
            pool.shutdownNow();
            for (String session : opened) {
                if (session != null) {
                    http.delete("/mcp", BEARER, Header.session(session));
                }
            }
        }
    }

    private String sendRawHttp(String request) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.UTF_8));
            out.flush();
            InputStream in = socket.getInputStream();
            return StandardCharsets.UTF_8.decode(java.nio.ByteBuffer.wrap(in.readAllBytes())).toString();
        }
    }
}
