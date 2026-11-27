// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.hookclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.veridger.files.ProcessAccount;

/**
 * Real-process and loopback-HTTP tests for {@link ResidentLauncher}, {@link HookClient}, and {@link ResidentProcess}
 * covering startup lock contention, dead/fast-exiting resident detection, and live loopback calls.
 */
class ResidentLauncherAndHookClientTest {

    private static final String SECRET = ResidentAddress.newSecret();

    private static Path currentJavaExecutable() {
        return Path.of(ProcessHandle.current().info().command().orElseThrow());
    }

    private static Path createClientAndResidentJars(Path dir, String residentBody) throws IOException {
        Path clientJar = dir.resolve("ledger-hook-client.jar");
        Path residentJar = dir.resolve(ResidentJar.FILE_NAME);
        Files.writeString(clientJar, "client-jar-placeholder", StandardCharsets.UTF_8);
        Files.writeString(residentJar, residentBody, StandardCharsets.UTF_8);
        return clientJar;
    }

    private static ClientEnvironment env(Path home, Path tmp, Path clientJar, Path keyDir) {
        return new ClientEnvironment(home.toString(), ProcessAccount.name().orElse(""),
                keyDir.toString(), currentJavaExecutable(), tmp, clientJar);
    }

    @Test
    @DisplayName("HCL-LN-01 ensureRunning returns existing usable resident and handles lock contention timeout")
    void ensureRunningUsableAndLockContention(@TempDir Path dir) throws Exception {
        Path home = Files.createDirectories(dir.resolve("home"));
        Path tmp = Files.createDirectories(dir.resolve("tmp"));
        Path keyDir = Files.createDirectories(dir.resolve("keys"));
        Path clientJar = createClientAndResidentJars(dir, "resident-build-a");

        ClientEnvironment environment = env(home, tmp, clientJar, keyDir);
        StateFolder state = StateFolder.locate(environment.userHome(), environment.keyFolderText());
        ResidentJar jar = ResidentJar.besideClient(clientJar);
        ResidentProcess processes = new ResidentProcess();
        AddressFile addressFile = new AddressFile(state.addressFile());
        ResidentFinder finder = new ResidentFinder(addressFile, processes);
        ResidentClient client = new ResidentClient(new Wire(), 2000);
        ResidentLauncher launcher = new ResidentLauncher(state, environment, jar, finder, client, processes, addressFile);

        ResidentAddress live = new ResidentAddress(12345, ProcessHandle.current().pid(),
                processes.startOfCurrent(), jar.build(), SECRET);
        OwnerOnlyFiles files = OwnerOnlyFiles.forFileSystem(FileSystems.getDefault(), environment.userName());
        files.createDirectories(state.directory());
        addressFile.write(live, files);

        assertThat(launcher.ensureRunning(1000)).isEqualTo(live);

        // Startup lock held in same JVM -> tryLock catches OverlappingFileLockException and times out cleanly
        addressFile.remove();
        try (FileChannel held = FileChannel.open(state.startupLock(),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock lock = held.lock()) {
            assertThat(lock.isValid()).isTrue();
            assertThatThrownBy(() -> launcher.ensureRunning(80))
                    .isInstanceOf(ResidentException.class)
                    .hasMessageContaining("another call");
        }

        // awaitGone & destroy on a dead pid return immediately without blocking
        ResidentAddress dead = new ResidentAddress(12345, 999_999_991L, 1L, "dead", SECRET);
        launcher.awaitGone(dead);
        processes.destroy(dead);
        assertThat(processes.isLive(dead)).isFalse();
    }

    @Test
    @DisplayName("HCL-LN-02 ensureRunning detects fast-exiting resident process and HookClient calls loopback /call")
    void ensureRunningFastExitAndHookClientCall(@TempDir Path dir) throws Exception {
        Path home = Files.createDirectories(dir.resolve("home"));
        Path tmp = Files.createDirectories(dir.resolve("tmp"));
        Path keyDir = Files.createDirectories(dir.resolve("keys"));
        Path clientJar = createClientAndResidentJars(dir, "not-a-valid-jar");

        ClientEnvironment environment = env(home, tmp, clientJar, keyDir);
        StateFolder state = StateFolder.locate(environment.userHome(), environment.keyFolderText());
        ResidentJar jar = ResidentJar.besideClient(clientJar);
        ResidentProcess processes = new ResidentProcess();
        AddressFile addressFile = new AddressFile(state.addressFile());
        ResidentFinder finder = new ResidentFinder(addressFile, processes);
        Wire wire = new Wire();
        ResidentClient client = new ResidentClient(wire, 2000);
        ResidentLauncher launcher = new ResidentLauncher(state, environment, jar, finder, client, processes, addressFile);

        // Invalid jar causes java -jar to exit immediately with non-zero code
        assertThatThrownBy(() -> launcher.ensureRunning(5000))
                .isInstanceOf(ResidentException.class)
                .hasMessageContaining("ended while starting");

        Process finished = new ProcessBuilder(currentJavaExecutable().toString(), "-version").start();
        finished.waitFor();
        ResidentLauncher.StartedResident handle = ResidentLauncher.StartedResident.ofProcess(finished);
        assertThat(handle.isAlive()).isFalse();
        assertThat(handle.exitValue()).isZero();
        handle.close();

        HttpServer callServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        callServer.createContext("/call", ex -> {
            byte[] resp = wire.encode(new HookAnswer(0, "{\"ok\":true}".getBytes(StandardCharsets.UTF_8), new byte[0]));
            ex.getResponseHeaders().set("Content-Type", "application/octet-stream");
            ex.sendResponseHeaders(200, resp.length);
            ex.getResponseBody().write(resp);
            ex.close();
        });
        callServer.start();
        try {
            HookClient hc = HookClient.of(environment);
            // Nothing running -> stop() is a clean no-op
            hc.stop();

            OwnerOnlyFiles files = OwnerOnlyFiles.forFileSystem(FileSystems.getDefault(), environment.userName());
            ResidentAddress usable = new ResidentAddress(callServer.getAddress().getPort(),
                    ProcessHandle.current().pid(), processes.startOfCurrent(), jar.build(), SECRET);
            addressFile.write(usable, files);

            HookAnswer answer = hc.call(new HookCall(List.of("pre-tool"), "{}".getBytes(StandardCharsets.UTF_8)));
            assertThat(answer.exit()).isZero();
            assertThat(answer.stdout()).isEqualTo("{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
            addressFile.remove();
        } finally {
            callServer.stop(0);
        }
    }
}
