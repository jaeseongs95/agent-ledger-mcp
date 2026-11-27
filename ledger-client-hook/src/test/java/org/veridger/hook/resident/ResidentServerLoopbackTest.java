// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.hook.resident;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.veridger.hook.HookCli;
import org.veridger.hookclient.AddressFile;
import org.veridger.hookclient.HookAnswer;
import org.veridger.hookclient.HookCall;
import org.veridger.hookclient.ResidentAddress;
import org.veridger.hookclient.ResidentClient;
import org.veridger.hookclient.StateFolder;
import org.veridger.hookclient.Wire;

/**
 * Direct loopback tests for {@link SecretAuthenticator}, {@link CallHandler}, {@link StopHandler}, and
 * {@link ResidentServer}.
 */
class ResidentServerLoopbackTest {

    @Test
    @DisplayName("HKS-AUTH-01 SecretAuthenticator enforces exact username and constant-time secret comparison")
    void secretAuthenticatorChecksUserAndSecret() {
        SecretAuthenticator auth = new SecretAuthenticator("top-secret-value");
        assertThat(auth.getRealm()).isEqualTo("ledger-client-hook");
        assertThat(auth.checkCredentials("hook", "top-secret-value")).isTrue();
        assertThat(auth.checkCredentials("other", "top-secret-value")).isFalse();
        assertThat(auth.checkCredentials("hook", "wrong-secret")).isFalse();
        assertThat(auth.checkCredentials("other", "wrong-secret")).isFalse();
    }

    @Test
    @DisplayName("HKS-SRV-01 ResidentServer enforces auth, 405/411/413/400/200 on /call, lock exclusivity, and clean /stop")
    void residentServerHandlesAllHttpBranchesAndCleanShutdown(@TempDir Path home) throws Exception {
        StateFolder state = StateFolder.locate(home.toString(), home.resolve("keys").toString());
        HookCli cli = new HookCli(null, null);
        HookService service = new HookService(cli);
        ResidentServer server = new ResidentServer(service, new ResidentSettings(Duration.ofSeconds(15)),
                new ResidentAccountProperties(""), Clock.systemUTC());

        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<?> running = pool.submit(() -> {
            try {
                server.run(state, "build-test");
            } catch (IOException | InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
        try {
            AddressFile addressFile = new AddressFile(state.addressFile());
            ResidentAddress address = waitForAddress(addressFile);
            assertThat(address.build()).isEqualTo("build-test");

            // A second ResidentServer on the same state folder exits immediately because InstanceLock is held
            server.run(state, "build-second");

            // Valid /call (empty args -> HookCli prints usage and returns exit 2)
            ResidentClient client = new ResidentClient(new Wire(), 2000);
            HookAnswer answer = client.call(address, new HookCall(List.of(), new byte[0]));
            assertThat(answer.exit()).isEqualTo(2);
            assertThat(answer.stderr()).asString(StandardCharsets.UTF_8).contains("usage:");

            String authHeader = "Basic " + Base64.getEncoder()
                    .encodeToString(("hook:" + address.secret()).getBytes(StandardCharsets.UTF_8));

            // 405 Method Not Allowed on GET /call
            assertThat(statusOf(address.port(), "GET", "/call", authHeader, null)).isEqualTo(405);

            // 400 Bad Request on malformed Wire body
            assertThat(statusOf(address.port(), "POST", "/call", authHeader, new byte[] { 1, 2, 3 })).isEqualTo(400);

            // 411 Length Required (chunked without Content-Length) & 413 Payload Too Large over raw loopback socket
            String chunkedReply = sendRaw(address.port(), "POST /call HTTP/1.1\r\nHost: 127.0.0.1:" + address.port()
                    + "\r\nAuthorization: " + authHeader
                    + "\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n0\r\n\r\n");
            assertThat(chunkedReply).startsWith("HTTP/1.1 411 ");

            String hugeReply = sendRaw(address.port(), "POST /call HTTP/1.1\r\nHost: 127.0.0.1:" + address.port()
                    + "\r\nAuthorization: " + authHeader
                    + "\r\nContent-Length: 999999999\r\nConnection: close\r\n\r\n");
            assertThat(hugeReply).startsWith("HTTP/1.1 413 ");

            String nonNumericLen = sendRaw(address.port(), "POST /call HTTP/1.1\r\nHost: 127.0.0.1:" + address.port()
                    + "\r\nAuthorization: " + authHeader
                    + "\r\nContent-Length: abc\r\nConnection: close\r\n\r\n");
            assertThat(nonNumericLen).startsWith("HTTP/1.1 400 ");

            // 401 Unauthorized without secret
            assertThat(statusOf(address.port(), "POST", "/call", null, new byte[0])).isEqualTo(401);

            // Clean shutdown via /stop removes the address file
            client.stop(address);
            running.get(5, TimeUnit.SECONDS);
            assertThat(state.addressFile()).doesNotExist();
        } finally {
            running.cancel(true);
            pool.shutdownNow();
        }
    }

    private static ResidentAddress waitForAddress(AddressFile file) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            var found = file.read();
            if (found.isPresent()) {
                return found.get();
            }
            Thread.sleep(25L);
        }
        throw new AssertionError("resident address file was not written in time");
    }

    private static int statusOf(int port, String method, String path, String auth, byte[] body) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + path).toURL().openConnection();
        try {
            conn.setRequestMethod(method);
            if (auth != null) {
                conn.setRequestProperty("Authorization", auth);
            }
            if (body != null) {
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(body.length);
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(body);
                }
            }
            return conn.getResponseCode();
        } finally {
            conn.disconnect();
        }
    }

    private static String sendRaw(int port, String request) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(3000);
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.UTF_8));
            out.flush();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                String line = reader.readLine();
                return line == null ? "" : line;
            }
        }
    }
}
