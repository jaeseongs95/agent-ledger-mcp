// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.hookclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Real loopback and filesystem tests for {@link ResidentRuntime}, {@link ResidentClient}, {@link ClientCommand}, and
 * {@link HookClient}.
 */
class ResidentClientAndRuntimeTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    @Test
    @DisplayName("HCL-RT-01 ResidentRuntime installs resident jar once per build and prunes stale builds")
    void residentRuntimeInstallAndPrune(@TempDir Path home) throws IOException {
        OwnerOnlyFiles files = OwnerOnlyFiles.forFileSystem(FileSystems.getDefault(),
                org.veridger.files.ProcessAccount.name().orElse(""));
        ResidentRuntime runtime = new ResidentRuntime(home, files);
        assertThat(runtime.pruneOtherBuilds("build-a")).isZero();

        Path sourceDir = home.resolve("plugin");
        Files.createDirectories(sourceDir);
        Path jarFile = sourceDir.resolve(ResidentJar.FILE_NAME);
        Files.writeString(jarFile, "fake-jar-bytes-v1", StandardCharsets.UTF_8);
        ResidentJar jarA = new ResidentJar(jarFile, "build-a");

        Path installedFirst = runtime.install(jarA);
        assertThat(installedFirst).isRegularFile();
        assertThat(runtime.install(jarA)).isEqualTo(installedFirst);

        ResidentJar jarB = new ResidentJar(jarFile, "build-b");
        Path installedB = runtime.install(jarB);
        assertThat(installedB).isRegularFile();

        assertThat(runtime.pruneOtherBuilds("build-b")).isZero();
        assertThat(installedFirst.getParent()).doesNotExist();
        assertThat(installedB).isRegularFile();
    }

    @Test
    @DisplayName("HCL-RC-01 ResidentClient round-trips /call and /stop, maps 401/500/malformed/connect/timeout errors")
    void residentClientLoopbackAndFailurePaths() throws IOException, ResidentException {
        Wire wire = new Wire();
        String secret = ResidentAddress.newSecret();
        String expectedAuth = "Basic " + Base64.getEncoder()
                .encodeToString(("hook:" + secret).getBytes(StandardCharsets.UTF_8));
        AtomicInteger mode = new AtomicInteger(200);
        AtomicBoolean stopped = new AtomicBoolean(false);

        server = HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[] { 127, 0, 0, 1 }), 0), 0);
        server.createContext("/call", exchange -> {
            try (exchange) {
                String auth = exchange.getRequestHeaders().getFirst("Authorization");
                if (!expectedAuth.equals(auth)) {
                    exchange.sendResponseHeaders(401, -1);
                    return;
                }
                int current = mode.get();
                if (current == 500) {
                    exchange.sendResponseHeaders(500, -1);
                    return;
                }
                if (current == 999) {
                    byte[] junk = "not-wire".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, junk.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(junk);
                    }
                    return;
                }
                if (current == 408) {
                    try {
                        Thread.sleep(600L);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    exchange.sendResponseHeaders(200, -1);
                    return;
                }
                HookCall received = wire.decodeCall(exchange.getRequestBody());
                HookAnswer answer = new HookAnswer(0,
                        ("echo:" + String.join(",", received.arguments())).getBytes(StandardCharsets.UTF_8),
                        "warn".getBytes(StandardCharsets.UTF_8));
                byte[] body = wire.encode(answer);
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
        });
        server.createContext("/stop", exchange -> {
            try (exchange) {
                stopped.set(true);
                exchange.sendResponseHeaders(200, -1);
            }
        });
        server.start();

        int port = server.getAddress().getPort();
        ResidentAddress okAddress = new ResidentAddress(port, 1L, 2L, "b1", secret);
        ResidentClient client = new ResidentClient(wire, 250);

        HookAnswer answer = client.call(okAddress, new HookCall(List.of("claude"), "{}".getBytes(StandardCharsets.UTF_8)));
        assertThat(answer.exit()).isZero();
        assertThat(answer.stdout()).asString(StandardCharsets.UTF_8).isEqualTo("echo:claude");
        assertThat(answer.stderr()).asString(StandardCharsets.UTF_8).isEqualTo("warn");

        client.stop(okAddress);
        assertThat(stopped).isTrue();

        ResidentAddress badSecret = new ResidentAddress(port, 1L, 2L, "b1", "wrong-secret");
        assertThatThrownBy(() -> client.call(badSecret, new HookCall(List.of("claude"), new byte[0])))
                .isInstanceOf(ResidentUnreachableException.class);

        mode.set(500);
        assertThatThrownBy(() -> client.call(okAddress, new HookCall(List.of("claude"), new byte[0])))
                .isInstanceOf(ResidentException.class).hasMessageContaining("500");

        mode.set(999);
        assertThatThrownBy(() -> client.call(okAddress, new HookCall(List.of("claude"), new byte[0])))
                .isInstanceOf(ResidentException.class).hasMessageContaining("not understood");

        mode.set(408);
        assertThatThrownBy(() -> client.call(okAddress, new HookCall(List.of("claude"), new byte[0])))
                .isInstanceOf(ResidentException.class).hasMessageContaining("in time");

        int unusedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByAddress(new byte[] { 127, 0, 0, 1 }))) {
            unusedPort = socket.getLocalPort();
        }
        ResidentAddress closedAddress = new ResidentAddress(unusedPort, 1L, 2L, "b1", secret);
        assertThatThrownBy(() -> client.call(closedAddress, new HookCall(List.of("claude"), new byte[0])))
                .isInstanceOf(ResidentUnreachableException.class);
    }

    @Test
    @DisplayName("HCL-CC-01 ClientCommand & HookClient forward stdout/stderr/exit, handle stop, and report closed stdout or unavailable resident")
    void clientCommandAndHookClientEndToEnd(@TempDir Path dir) throws IOException {
        Wire wire = new Wire();
        String secret = ResidentAddress.newSecret();
        AtomicBoolean stopped = new AtomicBoolean(false);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[] { 127, 0, 0, 1 }), 0), 0);
        server.createContext("/call", exchange -> {
            try (exchange) {
                wire.decodeCall(exchange.getRequestBody());
                byte[] body = wire.encode(new HookAnswer(0, "ok-out".getBytes(StandardCharsets.UTF_8),
                        "ok-err".getBytes(StandardCharsets.UTF_8)));
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
        });
        server.createContext("/stop", exchange -> {
            try (exchange) {
                stopped.set(true);
                exchange.sendResponseHeaders(200, -1);
            }
        });
        server.start();

        Path stateDir = dir.resolve("state");
        OwnerOnlyFiles files = OwnerOnlyFiles.forFileSystem(FileSystems.getDefault(),
                org.veridger.files.ProcessAccount.name().orElse(""));
        files.createDirectories(stateDir);
        AddressFile addressFile = new AddressFile(stateDir.resolve("resident.address"));
        ResidentProcess processes = new ResidentProcess();
        long pid = ProcessHandle.current().pid();
        addressFile.write(new ResidentAddress(server.getAddress().getPort(), pid, processes.startOfCurrent(), "build-1", secret),
                files);

        ResidentFinder finder = new ResidentFinder(addressFile, processes);
        ResidentClient client = new ResidentClient(wire, 1000);
        HookClient hookClient = new HookClient(new ResidentJar(dir.resolve(ResidentJar.FILE_NAME), "build-1"),
                finder, client, null, processes);

        ClientCommand command = new ClientCommand(hookClient, 1024);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = command.run(new String[] { "claude" }, new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)),
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        assertThat(exit).isZero();
        assertThat(stdout.toByteArray()).asString(StandardCharsets.UTF_8).isEqualTo("ok-out");
        assertThat(stderr.toByteArray()).asString(StandardCharsets.UTF_8).isEqualTo("ok-err");

        // Failing stdout triggers OUTPUT_FAILED and exit 2
        ByteArrayOutputStream errOnBrokenOut = new ByteArrayOutputStream();
        PrintStream brokenOut = new PrintStream(new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("broken pipe");
            }
        }, true, StandardCharsets.UTF_8);
        int brokenExit = command.run(new String[] { "claude" },
                new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)),
                brokenOut, new PrintStream(errOnBrokenOut, true, StandardCharsets.UTF_8));
        assertThat(brokenExit).isEqualTo(2);
        assertThat(errOnBrokenOut.toByteArray()).asString(StandardCharsets.UTF_8).contains("could not write");

        // stop command when nothing is running returns 0 cleanly
        addressFile.removeIfOwnedBy(pid);
        int stopNoOp = command.run(new String[] { "stop" }, new ByteArrayInputStream(new byte[0]),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        assertThat(stopNoOp).isZero();
        assertThat(stopped).isFalse();
    }
}
