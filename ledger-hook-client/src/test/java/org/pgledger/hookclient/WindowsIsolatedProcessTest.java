// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.hookclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit and live Windows isolated-spawn boundary tests for {@link WindowsIsolatedProcess}. */
class WindowsIsolatedProcessTest {

    @Test
    @DisplayName("Windows command line formatter quotes whitespace, empty strings, embedded quotes and trailing backslashes")
    void formatsCommandLineAccordingToWindowsRules() {
        assertThat(WindowsIsolatedProcess.formatCommandLine(List.of("java", "-jar", "resident.jar")))
                .isEqualTo("java -jar resident.jar");
        assertThat(WindowsIsolatedProcess.formatCommandLine(List.of(
                "C:\\Program Files\\Java\\bin\\java.exe",
                "-Dnote=two\twords",
                "",
                "a\"b",
                "C:\\Program Files\\",
                "x\\\"y")))
                .isEqualTo("\"C:\\Program Files\\Java\\bin\\java.exe\" \"-Dnote=two\twords\" \"\" \"a\\\"b\" "
                        + "\"C:\\Program Files\\\\\" \"x\\\\\\\"y\"");
    }

    @Test
    @DisplayName("Windows command line formatter refuses null, empty and NUL-bearing arguments")
    void refusesInvalidCommandArguments() {
        assertThatThrownBy(() -> WindowsIsolatedProcess.formatCommandLine(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WindowsIsolatedProcess.formatCommandLine(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WindowsIsolatedProcess.formatCommandLine(java.util.Collections.singletonList(null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WindowsIsolatedProcess.formatCommandLine(List.of("ok", "bad\0arg")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Win32 struct layouts match ABI sizes without loading kernel32")
    void win32StructLayoutsHaveExactAbiSizes() {
        boolean is64Bit = ValueLayout.ADDRESS.byteSize() == Long.BYTES;
        assertThat(WindowsIsolatedProcess.SECURITY_ATTRIBUTES_LAYOUT.byteSize()).isEqualTo(is64Bit ? 24L : 12L);
        assertThat(WindowsIsolatedProcess.STARTUPINFOW_LAYOUT.byteSize()).isEqualTo(is64Bit ? 104L : 68L);
        assertThat(WindowsIsolatedProcess.STARTUPINFOEXW_LAYOUT.byteSize()).isEqualTo(is64Bit ? 112L : 72L);
        assertThat(WindowsIsolatedProcess.PROCESS_INFORMATION_LAYOUT.byteSize()).isEqualTo(is64Bit ? 24L : 16L);
    }

    @Test
    @DisplayName("Isolated Windows spawn captures stdout and stderr in logFile, reports exact exit codes including 259, and supports idempotent close")
    void spawnsIsolatedProcessCapturesLogsAndReadsExitCode(@TempDir Path work) throws Exception {
        Assumptions.assumeTrue(WindowsIsolatedProcess.isSupported(), "requires an ACL host");
        Path logFile = work.resolve("resident.log");

        ResidentLauncher.StartedResident secondClose;
        // Exit code 259 matches Win32 STILL_ACTIVE: WaitForSingleObject distinguishes running vs exited with 259.
        try (ResidentLauncher.StartedResident started = WindowsIsolatedProcess.spawn(
                List.of("cmd.exe", "/c", "echo out-isolated & echo err-isolated 1>&2 & exit /b 259"), work, logFile)) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (started.isAlive() && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertThat(started.isAlive()).as("child finished within 10s").isFalse();
            assertThat(started.exitValue()).isEqualTo(259);
            secondClose = started;
        }
        // Second try-with-resources exercises idempotent handle closing without explicit close() lint warnings.
        try (ResidentLauncher.StartedResident duplicate = secondClose) {
            assertThat(duplicate.isAlive()).isFalse();
            assertThatThrownBy(duplicate::exitValue).isInstanceOf(IllegalStateException.class);
        }

        String logged = Files.readString(logFile, StandardCharsets.UTF_8);
        assertThat(logged).contains("out-isolated").contains("err-isolated");
    }

    @Test
    @DisplayName("Live isolated Windows process reports alive and refuses exitValue until termination")
    void liveIsolatedProcessReportsAliveAndRefusesExitValueWhileRunning(@TempDir Path work) throws Exception {
        Assumptions.assumeTrue(WindowsIsolatedProcess.isSupported(), "requires an ACL host");
        Path logFile = work.resolve("live.log");

        try (ResidentLauncher.StartedResident started = WindowsIsolatedProcess.spawn(
                List.of("powershell.exe", "-NoProfile", "-Command", "Start-Sleep -Milliseconds 300; exit 7"),
                work, logFile)) {
            if (started.isAlive()) {
                assertThatThrownBy(started::exitValue).isInstanceOf(IllegalThreadStateException.class);
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (started.isAlive() && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertThat(started.isAlive()).isFalse();
            assertThat(started.exitValue()).isEqualTo(7);
        }
    }

    @Test
    @DisplayName("Missing executable surfaces IOException naming CreateProcessW and the Win32 error")
    void missingExecutableSurfacesCreateProcessError(@TempDir Path work) {
        Assumptions.assumeTrue(WindowsIsolatedProcess.isSupported(), "requires an ACL host");
        Path logFile = work.resolve("missing.log");

        assertThatThrownBy(() -> WindowsIsolatedProcess.spawn(
                List.of("nonexistent-binary-ledger-isolated-spawn-test.exe"), work, logFile))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("CreateProcessW")
                .hasMessageContaining("error");
    }
}
