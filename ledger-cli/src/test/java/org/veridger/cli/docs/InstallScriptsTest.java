// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.cli.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
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
import org.springframework.core.io.ClassPathResource;
import org.veridger.cli.CliCommand;
import org.veridger.cli.deploy.DeployLayout;
import org.veridger.cli.deploy.DeployLayout.Platform;
import org.veridger.cli.deploy.DeployOptions;
import org.veridger.cli.seed.InstallDefaults;
import org.veridger.cli.seed.PackagedSeed;

/**
 * Verifies security, backward-compatibility, checksum-verification, and least-privilege updater invariants across the
 * six deployment/installer scripts in {@code deploy/}: server installers ({@code install.sh}, {@code install.cmd}),
 * client hook installers ({@code install-hook.sh}, {@code install-hook.cmd}), and host updaters
 * ({@code veridger-updater.sh}, {@code veridger-updater.cmd}).
 */
class InstallScriptsTest {

    private static final String SH = "deploy/install.sh";
    private static final String CMD = "deploy/install.cmd";
    private static final String HOOK_SH = "deploy/install-hook.sh";
    private static final String HOOK_CMD = "deploy/install-hook.cmd";
    private static final String UPD_SH = "deploy/veridger-updater.sh";
    private static final String UPD_CMD = "deploy/veridger-updater.cmd";

    /** An option that carries a secret value; {@code --ledger.secrets.home=} and {@code instance=} are not secrets. */
    private static final Pattern SECRET_OPTION = Pattern
            .compile("(?i)--(?!ledger\\.secrets\\.(home|instance)=)[a-z.-]*(password|token|secret)[a-z.-]*=");
    private static final Pattern FORBIDDEN_TOOLS_SH = Pattern.compile(
            "\\b(git|apt|apt-get|yum|dnf|zypper|pip|npm|psql|pg_dump|createuser|createdb|certbot|cloudflared|ssh|scp)\\b");

    private static String text(String script) {
        return Repository.read(script);
    }

    private static List<String> lines(String script) {
        return text(script).lines().toList();
    }

    private static boolean isComment(String line) {
        return line.strip().startsWith("#");
    }

    private static List<String> shCodeLines(String script) {
        return lines(script).stream().filter(line -> !line.isBlank() && !isComment(line)).toList();
    }

    private static List<String> cmdCodeLines(String script) {
        return lines(script).stream()
                .filter(line -> !line.isBlank() && !line.strip().toLowerCase(Locale.ROOT).startsWith("rem ")
                        && !"@echo off".equalsIgnoreCase(line.strip()))
                .toList();
    }

    @Test
    @DisplayName("INS-027 deploy/ holds exactly the server installers, client hook installers, and host updaters")
    void ins027_deployDirectoryContainsOnlyApprovedScripts() {
        assertThat(Repository.filesDirectlyIn("deploy")).containsExactly(
                "install-hook.cmd", "install-hook.sh", "install.cmd", "install.sh",
                "veridger-updater.cmd", "veridger-updater.sh");
        assertThat(Repository.subdirectories("deploy")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = { SH, CMD })
    @DisplayName("INS-021 both server installers cite Spring Boot deployment docs and explain thin delegation")
    void ins021_theHeaderCitesTheDocumentation(String script) {
        String header = String.join("\n", lines(script).stream().limit(25).toList());
        assertThat(header).contains("https://docs.spring.io/spring-boot/how-to/deployment/installing.html")
                .containsIgnoringCase("thin");
    }

    @Test
    @DisplayName("INS-022 install.sh starts with #!/bin/sh, set -eu, umask 022, carries no secret on command lines, and verifies SHA256SUMS on download")
    void ins022_safeShellAndChecksumVerification() {
        List<String> all = lines(SH);
        List<String> code = shCodeLines(SH);

        assertThat(all.getFirst()).isEqualTo("#!/bin/sh");
        assertThat(all).contains("set -eu", "umask 022");
        for (String line : code) {
            assertThat(line).as(line).doesNotContainPattern(SECRET_OPTION);
            assertThat(FORBIDDEN_TOOLS_SH.matcher(line).find()).as(line).isFalse();
            if (line.contains("secret-set") || line.contains("token-generate")) {
                assertThat(line).matches(
                        ".*(secret-set|token-generate) ledger\\.[a-z.-]+( --print| --if-absent| \\$STDIN| --copy-to=ledger\\.[a-z.-]+)*");
            }
        }
        assertThat(text(SH)).contains("SHA256SUMS", "sha256sum", "verify_sha256_entry")
                .doesNotContain("--ignore-missing");
        assertThat(text(SH).toLowerCase(Locale.ROOT)).doesNotContain("cloudflare", "certbot", "nginx");
    }

    @Test
    @DisplayName("INS-025/026 install.sh preserves re-run safety, Java command contract, and option validation before OS changes")
    void ins025_installShContractAndRerunSafety() throws IOException {
        String script = text(SH);
        List<String> code = shCodeLines(SH);
        DeployLayout layout = DeployLayout.linux("x");

        assertThat(script).contains("\nNAME=\n", "\nSVC=$NAME\n", "\nADMIN=$NAME-admin\n",
                "\nLIB=/opt/$NAME\n", "\nETC=/etc/$NAME\n");
        assertThat(layout.serviceUser()).isEqualTo("x");
        assertThat(layout.adminName()).isEqualTo("x-admin");
        assertThat(script).contains(DeployLayout.CLI_JAR);
        assertThat(firstIndex(code, "render-deploy --check")).isLessThan(firstIndex(code, "useradd"));
        assertThat(firstIndex(code, "useradd")).isLessThan(firstIndex(code, "runuser -u \"$SVC\" -- \"$JAVA\" -version"));
        assertThat(firstIndex(code, "runuser -u \"$SVC\" -- \"$JAVA\" -version")).isLessThan(firstIndex(code, "prepare-host"));
        assertThat(code).anySatisfy(l -> assertThat(l).endsWith("|| [ $? = 9 ]"));
        assertThat(script).contains("secret-set ledger.jdbc.password --if-absent $STDIN",
                "token-generate ledger.admin.token --print",
                "token-generate ledger.mcp.token --print --copy-to=ledger.admin.agent-token",
                "setup --owner-account=\"$OWNER\" --app-account=\"$APP_ACCOUNT\" $STDIN",
                "veridger-updater.sh", "PathExists=$ETC/update/request.properties");

        Properties usage = usageTexts();
        Set<String> known = new TreeSet<>(Stream.of(CliCommand.values()).map(CliCommand::cliName).toList());
        Pattern call = Pattern.compile("(?<![\\w-])as_svc\\s+(?:--\\S+\\s+)*([a-z][a-z0-9-]+)(.*)");
        for (String line : code) {
            Matcher matcher = call.matcher(line);
            if (matcher.find() && !line.contains("() {")) {
                assertThat(known).as("command in: %s", line).contains(matcher.group(1));
                assertOptionsAreDeclared(line, matcher.group(1), usage);
            }
        }
    }

    @Test
    @DisplayName("INS-034/036/037 install.cmd preserves --stdin, LocalService ACLs, re-run safety, SHA256SUMS verification, and updater registration")
    void ins034_installCmdContractAndChecksumVerification() {
        String script = text(CMD);
        List<String> code = cmdCodeLines(CMD);

        for (String line : code) {
            assertThat(line).as(line).doesNotContainPattern(SECRET_OPTION);
            if (line.contains("secret-set")) {
                assertThat(line).contains(" --if-absent");
            }
        }
        assertThat(script).contains("set \"STDIN=\"",
                "if /i \"%~1\"==\"--stdin\" set \"STDIN=--stdin\" & shift",
                "set \"ACCOUNT=" + DeployLayout.WINDOWS_SERVICE_ACCOUNT + "\"",
                "--platform=" + Platform.WINDOWS.name().toLowerCase(Locale.ROOT),
                "SHA256SUMS", "Get-FileHash",
                "prepare-host", "driver-register", "setup ", "sc.exe config %%S obj= \"%ACCOUNT%\"",
                "veridger-updater.cmd", "VeridgerUpdater-%NAME%",
                "mcp-check --url=http://127.0.0.1:%MCPPORT%/mcp");
        assertThat(firstIndex(code, "render-deploy --check")).isLessThan(firstIndex(code, "stop /stopwait"));
        assertThat(firstIndex(code, "driver-register")).isLessThan(firstIndex(code, "setup "));
        assertThat(firstIndex(code, "setup ")).isLessThan(firstIndex(code, "sc.exe config"));
    }

    @ParameterizedTest
    @ValueSource(strings = { SH, CMD })
    @DisplayName("SET-052 server installers query install-default for default instance name and ports instead of hardcoding them")
    void set052_defaultsComeFromJava(String script) {
        InstallDefaults defaults = new InstallDefaults(new PackagedSeed());
        String content = text(script);
        assertThat(content).contains("install-default", "instance-name", "mcp-port");
        assertThat(content).doesNotContain("127.0.0.1:" + defaults.mcpPort());
    }

    @Test
    @DisplayName("INS-050 client hook installers verify SHA256SUMS, merge Codex/Claude/Antigravity safely, and run a smoke test")
    void ins050_hookInstallersVerifyChecksumsAndMergeSafely() {
        String sh = text(HOOK_SH);
        String cmd = text(HOOK_CMD);

        assertThat(lines(HOOK_SH).getFirst()).isEqualTo("#!/bin/sh");
        assertThat(sh).contains("set -eu", "SHA256SUMS", "verify_sha256_entry", "ledger-client-hook.jar",
                "ledger-hook-resident.jar", ".codex", ".claude", ".gemini", "veridger-host-attestation",
                "mcp__veridger__ledger_ledger_whoami", "/hooks").doesNotContain("--ignore-missing");
        assertThat(cmd).contains("SHA256SUMS", "Get-FileHash", "UTF8Encoding", "ledger-client-hook.jar",
                "ledger-hook-resident.jar", ".codex", ".claude", ".gemini", "veridger-host-attestation",
                "mcp__veridger__ledger_ledger_whoami", "/hooks").doesNotContain("-AsHashtable");
    }

    @Test
    @DisplayName("INS-051 host updaters isolate staged JARs in private temp dirs, fetch pinned HTTPS SHA256SUMS, reject symlinks, check /healthz, and roll back automatically")
    void ins051_hostUpdatersEnforceVerificationAndRollback() {
        String sh = text(UPD_SH);
        String cmd = text(UPD_CMD);

        assertThat(lines(UPD_SH).getFirst()).isEqualTo("#!/bin/sh");
        assertThat(sh).contains("set -eu", "! -L", "mktemp -d", "chmod 0700", "--proto '=https'", "sha256sum",
                "SHA256SUMS", "backup-", "rollback", "/healthz", "ROLLED_BACK", "SUCCEEDED");
        assertThat(String.join("\n", shCodeLines(UPD_SH))).doesNotContain("chown", "--ignore-missing");
        assertThat(cmd).contains("ReparsePoint", "Get-FileHash", "SHA256SUMS", "UTF8Encoding", "backup-",
                "https://github.com/", "/healthz", "ROLLED_BACK", "SUCCEEDED");
    }

    @Test
    @DisplayName("INS-052 install-hook.cmd executes under real cmd.exe + PowerShell 5.1 with --flag=value, --hosts=a,b, spaces/&/() in --jars, and merges JSON without UTF-8 BOM")
    void ins052_installHookCmdLiveExecution(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        java.nio.file.FileSystem fs = tmp.getFileSystem();
        org.junit.jupiter.api.Assumptions.assumeTrue(fs.supportedFileAttributeViews().contains("acl")
                && !fs.supportedFileAttributeViews().contains("posix"), "needs Windows cmd.exe and PowerShell");

        java.nio.file.Path root = java.nio.file.Path.of(java.util.Objects.requireNonNull(
                System.getProperty("ledger.docs.root"), "ledger.docs.root is set by the test task"));
        java.nio.file.Path script = root.resolve("deploy/install-hook.cmd");
        java.nio.file.Path jarsDir = tmp.resolve("my jars & (1)");
        java.nio.file.Path homeDir = tmp.resolve("home");
        java.nio.file.Path localDir = tmp.resolve("local");
        java.nio.file.Files.createDirectories(jarsDir);
        java.nio.file.Files.createDirectories(homeDir.resolve(".codex"));
        java.nio.file.Files.createDirectories(homeDir.resolve(".claude"));
        java.nio.file.Files.createDirectories(homeDir.resolve(".gemini/config"));
        java.nio.file.Files.createDirectories(localDir);
        java.nio.file.Files.writeString(jarsDir.resolve("ledger-client-hook.jar"), "client-jar",
                StandardCharsets.UTF_8);
        java.nio.file.Files.writeString(jarsDir.resolve("ledger-hook-resident.jar"), "resident-jar",
                StandardCharsets.UTF_8);
        java.nio.file.Files.writeString(homeDir.resolve(".codex/hooks.json"),
                "{\"hooks\":{\"PreToolUse\":[{\"matcher\":\"keep\",\"hooks\":[{\"type\":\"command\",\"command\":\"echo keep\"}]}]}}",
                StandardCharsets.UTF_8);
        java.nio.file.Files.writeString(homeDir.resolve(".claude/settings.json"),
                "{\"theme\":\"dark\",\"hooks\":{\"PreToolUse\":null}}", StandardCharsets.UTF_8);
        java.nio.file.Files.writeString(homeDir.resolve(".gemini/config/hooks.json"),
                "{\"keep-me\":{\"enabled\":true}}", StandardCharsets.UTF_8);
        java.nio.file.Path fakeJava = tmp.resolve("fake-java.cmd");
        java.nio.file.Files.writeString(fakeJava,
                "@echo off\r\necho {\"hookSpecificOutput\":{\"updatedInput\":{\"_hostAttestation\":\"codex:install-smoke\"}}}\r\n",
                StandardCharsets.US_ASCII);

        // Case 1: unquoted --flag=value and comma-separated --hosts=codex,claude,antigravity with spaces/&/() in --jars
        java.nio.file.Path runner1 = tmp.resolve("run1.cmd");
        java.nio.file.Files.writeString(runner1, "@echo off\r\ncall \"" + script
                + "\" --version=v4.1.0 --hosts=codex,claude,antigravity --jars=\"" + jarsDir
                + "\" --java=\"" + fakeJava + "\"\r\n", StandardCharsets.US_ASCII);
        ProcessBuilder pb = new ProcessBuilder("cmd.exe", "/d", "/c", runner1.toString()).redirectErrorStream(true);
        pb.environment().put("USERPROFILE", homeDir.toString());
        pb.environment().put("LOCALAPPDATA", localDir.toString());
        Process proc = pb.start();
        String out = proc.inputReader(StandardCharsets.UTF_8).lines()
                .collect(java.util.stream.Collectors.joining("\n"));
        assertThat(proc.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)).as(out).isTrue();
        assertThat(proc.exitValue()).as(out).isZero();

        for (String rel : List.of(".codex/hooks.json", ".claude/settings.json", ".gemini/config/hooks.json")) {
            byte[] raw = java.nio.file.Files.readAllBytes(homeDir.resolve(rel));
            assertThat(raw.length >= 3 && (raw[0] & 0xFF) == 0xEF && (raw[1] & 0xFF) == 0xBB && (raw[2] & 0xFF) == 0xBF)
                    .as("no UTF-8 BOM in %s", rel).isFalse();
        }
        String codex = java.nio.file.Files.readString(homeDir.resolve(".codex/hooks.json"), StandardCharsets.UTF_8);
        assertThat(codex).contains("echo keep", "ledger-client-hook.jar\\\" codex");
        String claude = java.nio.file.Files.readString(homeDir.resolve(".claude/settings.json"), StandardCharsets.UTF_8);
        assertThat(claude).contains("\"theme\":", "ledger-client-hook.jar\\\" claude");
        String agy = java.nio.file.Files.readString(homeDir.resolve(".gemini/config/hooks.json"), StandardCharsets.UTF_8);
        assertThat(agy).contains("keep-me", "veridger-host-attestation", "ledger-client-hook.jar\\\" antigravity");

        // Case 2: outer-quoted "--flag=value" form also succeeds
        java.nio.file.Path runner2 = tmp.resolve("run2.cmd");
        java.nio.file.Files.writeString(runner2, "@echo off\r\ncall \"" + script
                + "\" \"--version=v4.1.0\" \"--hosts=codex\" \"--jars=" + jarsDir
                + "\" \"--java=" + fakeJava + "\"\r\n", StandardCharsets.US_ASCII);
        ProcessBuilder pb2 = new ProcessBuilder("cmd.exe", "/d", "/c", runner2.toString()).redirectErrorStream(true);
        pb2.environment().put("USERPROFILE", homeDir.toString());
        pb2.environment().put("LOCALAPPDATA", localDir.toString());
        Process proc2 = pb2.start();
        String out2 = proc2.inputReader(StandardCharsets.UTF_8).lines()
                .collect(java.util.stream.Collectors.joining("\n"));
        assertThat(proc2.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)).as(out2).isTrue();
        assertThat(proc2.exitValue()).as(out2).isZero();
    }

    @Test
    @DisplayName("R9-D0066 README documents --java check timing right after creating the service user")
    void r9d0066_readmeDocumentsJavaCheckTiming() {
        String english = Repository.read("README.md").lines().filter(line -> line.startsWith("| `--java=<path>`"))
                .findFirst().orElseThrow();
        assertThat(english).contains("right after it creates the service user and before it makes anything else");
        String korean = Repository.read("README.ko.md").lines().filter(line -> line.startsWith("| `--java=<path>`"))
                .findFirst().orElseThrow();
        assertThat(korean).contains("서비스 사용자를 만든 직후, 다른 것을 만들기 전에");
    }

    private static int firstIndex(List<String> lines, String fragment) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(fragment)) {
                return i;
            }
        }
        return -1;
    }

    private static Properties usageTexts() throws IOException {
        Properties bundle = new Properties();
        try (InputStream stream = new ClassPathResource("messages.properties").getInputStream()) {
            bundle.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
        return bundle;
    }

    private static void assertOptionsAreDeclared(String line, String command, Properties usage) {
        String text = usage.getProperty(CliCommand.parse(command).orElseThrow().usageKey());
        Matcher option = Pattern.compile("--([a-z][a-z-]*)(?![a-z.-])").matcher(line);
        while (option.find()) {
            assertThat(text).as("--%s in the usage of %s", option.group(1), command).contains("--" + option.group(1));
        }
    }
}
