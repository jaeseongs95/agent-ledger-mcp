// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.cli.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * WSEC-13: what {@code deploy/install.cmd} does when a step fails after it stopped the two services. The script is read as text
 * (it is a Windows script and the test runs everywhere); the order of its steps and its budget of lines are
 * {@code InstallScriptsTest}. The root of the repository is passed in by the Gradle test task of this module, never guessed.
 */
class InstallCmdFailureTest {

    private static List<String> code() throws IOException {
        Path root = Path.of(Objects.requireNonNull(System.getProperty("ledger.docs.root"),
                "ledger.docs.root is set by the test task of ledger-cli"));
        return Files.readAllLines(root.resolve("deploy/install.cmd"), StandardCharsets.UTF_8).stream()
                .filter(line -> !line.isBlank() && !line.strip().toLowerCase(Locale.ROOT).startsWith("rem ")
                        && !"@echo off".equalsIgnoreCase(line.strip()))
                .toList();
    }

    private static int index(List<String> lines, String fragment) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(fragment)) {
                return i;
            }
        }
        return -1;
    }

    /** The lines of a routine: after its label, up to the next label. */
    private static List<String> routine(List<String> lines, String label) {
        int from = lines.indexOf(label);
        int to = from + 1;
        while (to < lines.size() && !lines.get(to).startsWith(":")) {
            to++;
        }
        return lines.subList(from + 1, to);
    }

    @Test
    @DisplayName("WSEC-13 install.cmd notes which of the two services run before it stops them (STATE 4 of sc.exe query, whatever the language), runs every step after the stop in one routine whose failure goes to :fail, and :fail gives the service account its access again and starts only the services that ran, then exits 1")
    void wsec13_aFailedUpgradeStartsTheServicesThatRan() throws IOException {
        List<String> code = code();
        int note = index(code, "sc.exe query");
        int stop = index(code, "stop /stopwait");
        int steps = code.indexOf(":steps");
        int fail = code.indexOf(":fail");

        assertThat(note).as("the state is read before the stop").isNotNegative().isLessThan(stop);
        assertThat(code.get(note)).contains("\"skip=2 tokens=3\"", "\"%%A\"==\"4\"", "set \"UP_%%S=1\"")
                .as("no word of sc.exe is looked for: the words are in the language of the machine").doesNotContain("STATE");
        assertThat(code.get(index(code, "call :steps"))).as("a failed step of the routine goes to :fail").endsWith("|| goto :fail");
        assertThat(index(code, "call :steps")).isGreaterThan(stop).isLessThan(steps);

        // every command that can fail after the stop is a step of the routine (an exit of the script itself would skip :fail)
        for (String step : List.of("prepare-host", "secret-set", "token-generate ledger.admin.token", "token-generate ledger.mcp.token",
                "driver-register", " setup ", "sc.exe config", "\" start ")) {
            assertThat(index(code, step)).as(step).isGreaterThan(steps).isLessThan(fail);
        }
        assertThat(code.subList(stop + 1, steps)).as("no exit of the script between the stop and the routine, but its end")
                .noneMatch(line -> line.contains("exit /b 1"));

        List<String> onFailure = routine(code, ":fail");
        assertThat(onFailure).hasSize(2);
        assertThat(onFailure.get(0)).as("only a service that ran is touched, and started again (PCS-004: no seal-host)")
                .contains("if defined UP_%%S").doesNotContain("seal-host").contains("\"%WHOME%\\lib\\%%S.exe\" start");
        assertThat(onFailure.get(1)).isEqualTo("exit /b 1");
    }

    @Test
    @DisplayName("R9-D0067 a service whose step failed after its uninstall is registered again before it is started: :fail runs install and sc.exe config (the one service account) of each service that ran, then start, in that order; a service that was never removed answers the install with an error that is ignored")
    void r9D0067_aFailedUpgradeRegistersTheServicesAgain() throws IOException {
        List<String> code = code();
        String line = routine(code, ":fail").get(0);

        int install = line.indexOf("\"%WHOME%\\lib\\%%S.exe\" install");
        int config = line.indexOf("sc.exe config %%S obj= \"%ACCOUNT%\"");
        int start = line.indexOf("\"%WHOME%\\lib\\%%S.exe\" start");
        assertThat(install).as("registered again").isNotNegative();
        assertThat(config).as("with the one service account, as the main path does").isGreaterThan(install);
        assertThat(start).as("and then started").isGreaterThan(config);
        // the same two commands as in :steps, so that the account is the one of the main path
        assertThat(code.get(index(code, "\" install &&"))).contains("sc.exe config %%S obj= \"%ACCOUNT%\"");
        // an install of a service that exists fails and must not end the recovery: its answer is not shown and not tested
        assertThat(line.substring(install, config)).contains(">nul 2>&1").doesNotContain("||");
    }

    /**
     * R9-D0095 (e): the line of install.cmd that notes which services run, run by cmd.exe against real services. The words that
     * {@code sc.exe query} prints in front of the numbers are in the language of the machine ({@code STATE} is a Korean word on a
     * Korean Windows), so a line that looks for the word never saw a running service there, and a failed upgrade started none of the
     * services that ran. Only the numbers are the same in every language.
     */
    @Test
    @DisplayName("R9-D0095 e: the line that notes the running services finds a running service whatever the language of sc.exe is (run by cmd.exe against RpcSs, which always runs, and against a service that does not exist)")
    void r9D0095_theNoteOfRunningServicesIsLanguageFree(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        java.nio.file.FileSystem files = tmp.getFileSystem();
        org.junit.jupiter.api.Assumptions.assumeTrue(files.supportedFileAttributeViews().contains("acl")
                && !files.supportedFileAttributeViews().contains("posix"), "needs cmd.exe and the service manager of Windows");
        String note = code().get(index(code(), "sc.exe query"));
        Path script = tmp.resolve("note.cmd");
        Files.writeString(script, String.join("\r\n", "@echo off", "setlocal EnableExtensions",
                "set \"SVC=RpcSs\" & set \"ADMIN=NoSuchServiceOfR9D0095\"", note,
                "if defined UP_RpcSs (echo RUNNING-RpcSs) else (echo NOT-RUNNING-RpcSs)",
                "if defined UP_NoSuchServiceOfR9D0095 (echo RUNNING-none) else (echo NOT-RUNNING-none)", ""),
                StandardCharsets.US_ASCII);

        Process process = new ProcessBuilder("cmd.exe", "/d", "/c", script.toString()).redirectErrorStream(true).start();
        String output = process.inputReader(StandardCharsets.ISO_8859_1).lines().collect(java.util.stream.Collectors.joining("\n"));
        assertThat(process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        assertThat(output).contains("RUNNING-RpcSs").doesNotContain("NOT-RUNNING-RpcSs").contains("NOT-RUNNING-none");
    }
}
