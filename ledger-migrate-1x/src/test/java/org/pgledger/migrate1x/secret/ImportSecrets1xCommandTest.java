package org.pgledger.migrate1x.secret;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.ErrorKey;
import org.pgledger.cli.ExitStatus;
import org.pgledger.migrate1x.support.EnglishMessages;
import org.pgledger.migrate1x.support.RecordingOutput;
import org.pgledger.migrate1x.support.RequiresOwnerOnlyStorage;
import org.pgledger.migrate1x.support.ToolExtension;
import org.pgledger.migrate1x.support.ToolExtension.Outcome;
import org.pgledger.migrate1x.support.ToolExtension.Tool;
import org.pgledger.migrate1x.tool.MigrationError;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * SEC-027 (changed by LGN-015): {@code import-secrets-1x (--env=<file> | --stdin) [--replace]} moves the token of the agents of a
 * 1.x installation into the secret store ({@code DBHUB_AUTH_TOKEN} to {@code ledger.mcp.token} and
 * {@code ledger.admin.agent-token}). {@code PGLEDGER_ADMIN_TOKEN} is not imported: the {@code ledger.admin.token} of the installer
 * stays, and a line says so when the 1.x environment has the setting. M1X-024: the secrets of the installer of 2.0 are stored
 * already; without {@code --replace} they are kept, with it the 1.x value replaces the two of the agents. No test registers a
 * driver or a database: the command does not use one. The values are made up.
 */
@ExtendWith({ OutputCaptureExtension.class, ToolExtension.class, EnglishMessages.class })
class ImportSecrets1xCommandTest {

    private static final String AGENT = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90";
    private static final String ADMIN = "0f9e8d7c6b5a49382716051a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e";
    private static final String DATABASE_PASSWORD = "db-password-of-the-1x-installation";
    /** What the installer of 2.0 generated (M1X-024): its tokens are stored before the 1.x one is imported. */
    private static final String GENERATED_MCP = "generated-by-the-installer-mcp-0123456789";
    private static final String GENERATED_AGENT = "generated-by-the-installer-agent-0123456789";
    private static final String GENERATED_ADMIN = "generated-by-the-installer-admin-0123456789";

    @TempDir
    Path tmp;

    private Path directory() {
        return tmp.resolve("secrets");
    }

    private static String environment(String agent, String admin) {
        return "# written by pg-ledger-mcp deploy/install.sh\nPGLEDGER_DB=ledger\nPGLEDGER_AUTHOR_PASSWORD=" + DATABASE_PASSWORD
                + "\n" + (agent == null ? "" : "DBHUB_AUTH_TOKEN=" + agent + "\n")
                + (admin == null ? "" : "PGLEDGER_ADMIN_TOKEN=" + admin + "\n");
    }

    private Path envFile(String text) throws IOException {
        Path file = tmp.resolve("env");
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return file;
    }

    /** The file of a secret: in the folder of its use (SPL-007: the token of the agents in {@code mcp}, the admin secrets in {@code admin}). */
    private Path file(String name) {
        return directory().resolve(name.startsWith("ledger.mcp.") ? "mcp" : "admin").resolve(name);
    }

    private String stored(String name) throws IOException {
        return Files.readString(file(name));
    }

    /** The names of every secret file below the secret directory, whatever its folder. */
    private List<String> storedNames() throws IOException {
        try (Stream<Path> files = Files.walk(directory())) {
            return files.filter(Files::isRegularFile).map(path -> path.getFileName().toString()).toList();
        }
    }

    /** A secret as the installer of 2.0 left it: the file is in the folder of its use before the tool runs. */
    private void preStore(String name, String value) throws IOException {
        Files.createDirectories(file(name).getParent());
        Files.writeString(file(name), value, StandardCharsets.UTF_8);
    }

    private void preStoreGenerated() throws IOException {
        preStore("ledger.mcp.token", GENERATED_MCP);
        preStore("ledger.admin.agent-token", GENERATED_AGENT);
        preStore("ledger.admin.token", GENERATED_ADMIN);
    }

    /** The lines of the output that name the 1.x admin token. */
    private static List<String> adminLines(RecordingOutput output) {
        return output.info().lines().filter(line -> line.contains("PGLEDGER_ADMIN_TOKEN")).toList();
    }

    @Test
    @RequiresOwnerOnlyStorage
    @DisplayName("SEC-027 / LGN-015 --env: the agent token becomes ledger.mcp.token and ledger.admin.agent-token; the admin token of 1.x is not imported and one line says so; the other settings are not carried over; only names are printed")
    void sec027_importsFromAFile(Tool tool, CapturedOutput captured) throws IOException {
        RecordingOutput output = new RecordingOutput();

        Outcome result = tool.run(directory(), null, output, "import-secrets-1x", "--env=" + envFile(environment(AGENT, ADMIN)));

        assertThat(result.exitCode()).isZero();
        assertThat(result.failure()).isNull();
        assertThat(stored("ledger.mcp.token")).isEqualTo(AGENT);
        assertThat(stored("ledger.admin.agent-token")).isEqualTo(AGENT);
        assertThat(storedNames()).containsExactlyInAnyOrder("ledger.mcp.token", "ledger.admin.agent-token");
        assertThat(directory().resolve("mcp").toFile().list()).as("the MCP server's folder holds its token only")
                .containsExactly("ledger.mcp.token");
        assertThat(directory().resolve("admin").toFile().list()).as("the admin web's folder holds the token of the agents only")
                .containsExactly("ledger.admin.agent-token");
        assertThat(output.info()).contains("ledger.mcp.token", "ledger.admin.agent-token", "DBHUB_AUTH_TOKEN");
        assertThat(adminLines(output)).singleElement().asString().contains("was not imported").contains("ledger.admin.token")
                .contains("installer generated");
        assertThat(output.info() + captured.getAll()).doesNotContain(AGENT).doesNotContain(ADMIN).doesNotContain(DATABASE_PASSWORD);
    }

    @Test
    @RequiresOwnerOnlyStorage
    @DisplayName("SEC-027 --stdin: the same, from the text on standard input (the file of 1.x belongs to root, so the installer opens it and pipes it in)")
    void sec027_importsFromStandardInput(Tool tool, CapturedOutput captured) throws IOException {
        RecordingOutput output = new RecordingOutput();

        Outcome result = tool.withStandardInput(environment(AGENT, ADMIN)).run(directory(), null, output, "import-secrets-1x",
                "--stdin");

        assertThat(result.exitCode()).isZero();
        assertThat(stored("ledger.mcp.token")).isEqualTo(AGENT);
        assertThat(stored("ledger.admin.agent-token")).isEqualTo(AGENT);
        assertThat(storedNames()).containsExactlyInAnyOrder("ledger.mcp.token", "ledger.admin.agent-token");
        assertThat(output.info() + captured.getAll()).doesNotContain(AGENT).doesNotContain(ADMIN).doesNotContain(DATABASE_PASSWORD);
    }

    @Test
    @RequiresOwnerOnlyStorage
    @DisplayName("SEC-027 a secret that is stored already is kept and never replaced (the run that stopped half way is completed); the output says so")
    void sec027_keepsWhatExists(Tool tool, CapturedOutput captured) throws IOException {
        String chosen = "chosen-by-the-owner-0123456789";
        preStore("ledger.mcp.token", chosen);
        RecordingOutput output = new RecordingOutput();

        Outcome result = tool.withStandardInput(environment(AGENT, ADMIN)).run(directory(), null, output, "import-secrets-1x",
                "--stdin");

        assertThat(result.exitCode()).isZero();
        assertThat(stored("ledger.mcp.token")).as("kept").isEqualTo(chosen);
        assertThat(stored("ledger.admin.agent-token")).isEqualTo(AGENT);
        assertThat(output.info()).contains("The secret ledger.mcp.token already exists and was kept");

        RecordingOutput again = new RecordingOutput();
        assertThat(tool.withStandardInput(environment("another-agent-token-0123456789", "another-admin-token-0123456789"))
                .run(directory(), null, again, "import-secrets-1x", "--stdin").exitCode()).isZero();
        assertThat(stored("ledger.admin.agent-token")).as("a second run replaces nothing").isEqualTo(AGENT);
        assertThat(again.info().lines().filter(line -> line.contains("already exists"))).hasSize(2);
        assertThat(captured.getAll()).doesNotContain(chosen).doesNotContain(AGENT).doesNotContain("another-");
    }

    @Test
    @RequiresOwnerOnlyStorage
    @DisplayName("LGN-015 an environment without PGLEDGER_ADMIN_TOKEN (a 1.0.0 installation) is no special case: the two secrets of the agents are stored, no line is about the admin token, exit 0, and a second run changes nothing")
    void lgn015_noAdminTokenNoLine(Tool tool, CapturedOutput captured) throws IOException {
        RecordingOutput output = new RecordingOutput();

        Outcome result = tool.withStandardInput(environment(AGENT, null)).run(directory(), null, output, "import-secrets-1x",
                "--stdin");

        assertThat(result.exitCode()).isZero();
        assertThat(result.failure()).isNull();
        assertThat(stored("ledger.mcp.token")).isEqualTo(AGENT);
        assertThat(stored("ledger.admin.agent-token")).isEqualTo(AGENT);
        assertThat(storedNames()).containsExactlyInAnyOrder("ledger.mcp.token", "ledger.admin.agent-token");
        assertThat(adminLines(output)).isEmpty();
        assertThat(output.info() + captured.getAll()).doesNotContain(AGENT).doesNotContain(DATABASE_PASSWORD);

        RecordingOutput again = new RecordingOutput();
        assertThat(tool.withStandardInput(environment(AGENT, null)).run(directory(), null, again, "import-secrets-1x", "--stdin")
                .exitCode()).isZero();
        assertThat(storedNames()).containsExactlyInAnyOrder("ledger.mcp.token", "ledger.admin.agent-token");
        assertThat(again.info()).contains("already exists");
    }

    @Test
    @RequiresOwnerOnlyStorage
    @DisplayName("M1X-024 / LGN-015 the secrets of the installer of 2.0 are stored: without --replace both secrets of the agents are kept; with --replace they hold the 1.x value, ledger.admin.token stays as the installer made it, and the output names the secrets as replaced, never a value")
    void m1x024_replaceOverwritesWhatTheInstallerStored(Tool tool, CapturedOutput captured) throws IOException {
        preStoreGenerated();
        RecordingOutput kept = new RecordingOutput();

        Outcome withoutReplace = tool.withStandardInput(environment(AGENT, ADMIN)).run(directory(), null, kept,
                "import-secrets-1x", "--stdin");

        assertThat(withoutReplace.exitCode()).isZero();
        assertThat(stored("ledger.mcp.token")).isEqualTo(GENERATED_MCP);
        assertThat(stored("ledger.admin.agent-token")).isEqualTo(GENERATED_AGENT);
        assertThat(stored("ledger.admin.token")).isEqualTo(GENERATED_ADMIN);
        assertThat(kept.info().lines().filter(line -> line.contains("already exists and was kept"))).hasSize(2);
        assertThat(kept.info()).doesNotContain("Replaced").doesNotContain("Stored");

        RecordingOutput replaced = new RecordingOutput();
        Outcome withReplace = tool.withStandardInput(environment(AGENT, ADMIN)).run(directory(), null, replaced,
                "import-secrets-1x", "--stdin", "--replace");

        assertThat(withReplace.exitCode()).isZero();
        assertThat(withReplace.failure()).isNull();
        assertThat(stored("ledger.mcp.token")).isEqualTo(AGENT);
        assertThat(stored("ledger.admin.agent-token")).isEqualTo(AGENT);
        assertThat(stored("ledger.admin.token")).as("never replaced, also with --replace").isEqualTo(GENERATED_ADMIN);
        assertThat(replaced.info().lines().filter(line -> line.startsWith("Replaced the secret"))).hasSize(2);
        assertThat(replaced.info()).contains("Replaced the secret ledger.mcp.token with the value of the 1.x setting DBHUB_AUTH_TOKEN",
                "Replaced the secret ledger.admin.agent-token with the value of the 1.x setting DBHUB_AUTH_TOKEN")
                .doesNotContain("already exists");
        assertThat(adminLines(replaced)).singleElement().asString().contains("was not imported");
        assertThat(storedNames()).containsExactlyInAnyOrder("ledger.mcp.token", "ledger.admin.agent-token", "ledger.admin.token");
        assertThat(kept.info() + replaced.info() + captured.getAll()).doesNotContain(AGENT).doesNotContain(ADMIN)
                .doesNotContain(GENERATED_MCP).doesNotContain(GENERATED_AGENT).doesNotContain(GENERATED_ADMIN)
                .doesNotContain(DATABASE_PASSWORD);
    }

    @Test
    @RequiresOwnerOnlyStorage
    @DisplayName("M1X-024 --replace with --env does the same; a secret that does not exist yet is stored (not replaced) by it, and no ledger.admin.token is made")
    void m1x024_replaceFromAFile(Tool tool, CapturedOutput captured) throws IOException {
        preStore("ledger.mcp.token", GENERATED_MCP);
        RecordingOutput output = new RecordingOutput();

        Outcome result = tool.run(directory(), null, output, "import-secrets-1x", "--env=" + envFile(environment(AGENT, ADMIN)),
                "--replace");

        assertThat(result.exitCode()).isZero();
        assertThat(stored("ledger.mcp.token")).isEqualTo(AGENT);
        assertThat(stored("ledger.admin.agent-token")).isEqualTo(AGENT);
        assertThat(Files.exists(file("ledger.admin.token"))).isFalse();
        assertThat(output.info()).contains("Replaced the secret ledger.mcp.token", "Stored the secret ledger.admin.agent-token")
                .doesNotContain("Stored the secret ledger.admin.token");
        assertThat(output.info() + captured.getAll()).doesNotContain(AGENT).doesNotContain(ADMIN).doesNotContain(GENERATED_MCP);
    }

    static Stream<Arguments> adminTokens() {
        return Stream.of(
                Arguments.of("a short admin token", "short-token"),
                Arguments.of("the agent token as the admin token", AGENT),
                Arguments.of("an empty admin token", ""),
                Arguments.of("an admin token of 1.x that is a proper one", ADMIN));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("adminTokens")
    @RequiresOwnerOnlyStorage
    @DisplayName("LGN-015 the admin token of 1.x is not looked at: whatever its value is, the import succeeds, imports nothing of it and says that it was not imported")
    void lgn015_theAdminTokenIsNotChecked(String label, String admin, Tool tool, CapturedOutput captured) throws IOException {
        preStoreGenerated();
        RecordingOutput output = new RecordingOutput();

        Outcome result = tool.withStandardInput(environment(AGENT, admin)).run(directory(), null, output, "import-secrets-1x",
                "--stdin", "--replace");

        assertThat(result.exitCode()).as(label).isZero();
        assertThat(stored("ledger.admin.token")).as(label).isEqualTo(GENERATED_ADMIN);
        assertThat(stored("ledger.mcp.token")).as(label).isEqualTo(AGENT);
        assertThat(adminLines(output)).as(label).singleElement().asString().contains("was not imported");
        assertThat(output.info() + captured.getAll()).doesNotContain(ADMIN).doesNotContain("short-token");
    }

    static Stream<Arguments> refusedValues() {
        return Stream.of(
                Arguments.of("no agent token", environment(null, ADMIN), MigrationError.ENV_KEYS_MISSING),
                Arguments.of("no token at all", environment(null, null), MigrationError.ENV_KEYS_MISSING),
                Arguments.of("an empty agent token", environment("", ADMIN), MigrationError.ENV_KEYS_MISSING),
                Arguments.of("a short agent token", environment("short-token", ADMIN), MigrationError.INVALID_1X_VALUE));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("refusedValues")
    @RequiresOwnerOnlyStorage
    @DisplayName("SEC-027 a missing or unusable token of the agents is refused (exit 1) naming the setting, and nothing is stored")
    void sec027_refusesBeforeWriting(String label, String text, ErrorKey code, Tool tool, CapturedOutput captured) {
        Outcome result = tool.withStandardInput(text).run(directory(), null, new RecordingOutput(), "import-secrets-1x", "--stdin");

        assertThat(result.exitCode()).as(label).isEqualTo(ExitStatus.FAILURE.code());
        assertThat(result.code()).as(label).contains(code);
        assertThat(Files.exists(directory())).as(label + ": nothing is stored").isFalse();
        assertThat(captured.getAll()).doesNotContain(AGENT).doesNotContain(ADMIN).doesNotContain(DATABASE_PASSWORD);
    }

    @Test
    @RequiresOwnerOnlyStorage
    @DisplayName("M1X-024 --replace does not weaken the checks: an unusable token of the agents refuses with nothing replaced")
    void m1x024_replaceStillRefusesBeforeWriting(Tool tool) throws IOException {
        preStoreGenerated();

        Outcome result = tool.withStandardInput(environment("short-token", ADMIN)).run(directory(), null, new RecordingOutput(),
                "import-secrets-1x", "--stdin", "--replace");

        assertThat(result.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
        assertThat(result.code()).contains(MigrationError.INVALID_1X_VALUE);
        assertThat(stored("ledger.mcp.token")).isEqualTo(GENERATED_MCP);
        assertThat(stored("ledger.admin.agent-token")).isEqualTo(GENERATED_AGENT);
        assertThat(stored("ledger.admin.token")).isEqualTo(GENERATED_ADMIN);
    }

    @Test
    @RequiresOwnerOnlyStorage
    @DisplayName("SEC-027 the refusal names the missing or the unusable setting, never a value: only DBHUB_AUTH_TOKEN can be named now")
    void sec027_refusalNamesTheSetting(Tool tool) {
        Outcome missing = tool.withStandardInput(environment(null, null)).run(directory(), null, new RecordingOutput(),
                "import-secrets-1x", "--stdin");
        Outcome unusable = tool.withStandardInput(environment("short-token", ADMIN)).run(directory(), null, new RecordingOutput(),
                "import-secrets-1x", "--stdin");

        assertThat(missing.refusal().orElseThrow().arguments()).containsExactly("DBHUB_AUTH_TOKEN");
        assertThat(unusable.refusal().orElseThrow().arguments()).containsExactly("DBHUB_AUTH_TOKEN");
    }

    @Test
    @RequiresOwnerOnlyStorage
    @DisplayName("SEC-027 a file that is missing, not UTF-8 or larger than 64 KiB is ENV_FILE_FAILED (exit 1) naming only the kind of failure; the same text on standard input is STDIN_FAILED")
    void sec027_unreadableInput(Tool tool, CapturedOutput captured) throws IOException {
        Path notUtf8 = tmp.resolve("not-utf8.env");
        Files.write(notUtf8, new byte[] { 'A', '=', (byte) 0xC3, (byte) 0x28, '\n' });
        Path tooLarge = envFile(environment(AGENT, ADMIN) + "# " + "x".repeat(64 * 1024) + "\n");

        for (String option : new String[] { "--env=" + tmp.resolve("missing.env"), "--env=" + notUtf8, "--env=" + tooLarge }) {
            Outcome result = tool.run(directory(), null, new RecordingOutput(), "import-secrets-1x", option);

            assertThat(result.exitCode()).as(option).isEqualTo(ExitStatus.FAILURE.code());
            assertThat(result.code()).as(option).contains(MigrationError.ENV_FILE_FAILED);
        }
        Outcome piped = tool.withStandardInput(environment(AGENT, ADMIN) + "# " + "x".repeat(64 * 1024) + "\n").run(directory(),
                null, new RecordingOutput(), "import-secrets-1x", "--stdin");
        assertThat(piped.code()).contains(ErrorCode.STDIN_FAILED);
        assertThat(Files.exists(directory())).isFalse();
        assertThat(captured.getAll()).doesNotContain(AGENT).doesNotContain(ADMIN);
    }

    static Stream<Arguments> usageRefusals() {
        return Stream.of(
                Arguments.of(new String[] { "import-secrets-1x" }, ErrorCode.MISSING_ARGUMENT),
                Arguments.of(new String[] { "import-secrets-1x", "--replace" }, ErrorCode.MISSING_ARGUMENT),
                Arguments.of(new String[] { "import-secrets-1x", "--env=/etc/x", "--stdin" }, ErrorCode.INVALID_OPTION_VALUE),
                Arguments.of(new String[] { "import-secrets-1x", "--env=/etc/x", "--stdin", "--replace" },
                        ErrorCode.INVALID_OPTION_VALUE),
                Arguments.of(new String[] { "import-secrets-1x", "--env" }, ErrorCode.MISSING_OPTION_VALUE),
                Arguments.of(new String[] { "import-secrets-1x", "--stdin=yes" }, ErrorCode.INVALID_OPTION_VALUE),
                Arguments.of(new String[] { "import-secrets-1x", "--stdin", "--replace=yes" }, ErrorCode.INVALID_OPTION_VALUE),
                Arguments.of(new String[] { "import-secrets-1x", "extra", "--stdin" }, ErrorCode.TOO_MANY_ARGUMENTS),
                Arguments.of(new String[] { "import-secrets-1x", "--stdin", "--force" }, ErrorCode.UNKNOWN_OPTION));
    }

    @ParameterizedTest
    @MethodSource("usageRefusals")
    @DisplayName("SEC-027 a wrong command line is refused (exit 2) and stores nothing; M1X-024 --replace without a source is MISSING_ARGUMENT")
    void sec027_usageRefusals(String[] line, ErrorKey code, Tool tool) {
        Outcome result = tool.withStandardInput(environment(AGENT, ADMIN)).run(directory(), null, new RecordingOutput(), line);

        assertThat(result.exitCode()).isEqualTo(ExitStatus.USAGE.code());
        assertThat(result.code()).contains(code);
        assertThat(Files.exists(directory())).isFalse();
    }
}
