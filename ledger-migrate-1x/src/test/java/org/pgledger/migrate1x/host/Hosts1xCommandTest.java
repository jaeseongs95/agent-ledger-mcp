package org.pgledger.migrate1x.host;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.ExitStatus;
import org.pgledger.cli.deploy.DeployForms;
import org.pgledger.migrate1x.support.EnglishMessages;
import org.pgledger.migrate1x.support.RecordingOutput;
import org.pgledger.migrate1x.support.ScriptedServiceControl;
import org.pgledger.migrate1x.support.ToolExtension;
import org.pgledger.migrate1x.support.ToolExtension.Outcome;
import org.pgledger.migrate1x.support.ToolExtension.Tool;
import org.pgledger.migrate1x.tool.MigrationError;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * PCS-024 (changed from REH4-HOST-01, 02, 04 to 07): the public host names of a 1.x environment file against the one
 * {@code --mcp-host} and the one {@code --admin-host} (the form of the installer of 2.0). The names that the option does not carry are
 * told in the output as names that 2.0 no longer accepts, and no such name refuses the run; an option that is not one host name of
 * the installer's form is refused before anything is stopped. The service manager is a recording double.
 */
@ExtendWith({ OutputCaptureExtension.class, ToolExtension.class, EnglishMessages.class })
class Hosts1xCommandTest {

    private static final String AGENT_TOKEN = "a7c3e19b5d2f48a6b0c9d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2";
    private static final String ADMIN_TOKEN = "0f1e2d3c4b5a69788796a5b4c3d2e1f00112233445566778899aabbccddeeff0";
    private static final String DB_PASSWORD = "s3cr3t-database-password";
    private static final String PUBLIC_NAME = "ledger-test-mcp.jaeseong.dev";

    /** The unit of a 1.x service: it reads the environment file of 1.x. */
    private static final String UNIT_OF_1X = "[Service]\nEnvironmentFile=/etc/pg-ledger-mcp/env\nExecStart=/usr/bin/java -jar x.jar\n";

    @TempDir
    Path tmp;

    private int unitDirectories;

    /** The environment file of a 1.1.0 installation: the tokens and a password are in it, which no message may hold. */
    private Path environment(String... settings) throws IOException {
        String text = "# written by the 1.x installer\nDBHUB_AUTH_TOKEN=" + AGENT_TOKEN + "\nPGLEDGER_ADMIN_TOKEN=" + ADMIN_TOKEN
                + "\nPGLEDGER_AUTHOR_PASSWORD=" + DB_PASSWORD + "\n" + String.join("\n", settings) + "\n";
        Path file = tmp.resolve("env");
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return file;
    }

    /** A new unit directory with the unit of the MCP server of 1.x (and the unit of the admin web when asked for). */
    private Path units(boolean withAdmin) throws IOException {
        Path directory = Files.createDirectories(tmp.resolve("units-" + (++unitDirectories)));
        Files.writeString(directory.resolve("pg-ledger-mcp.service"), UNIT_OF_1X, StandardCharsets.UTF_8);
        if (withAdmin) {
            Files.writeString(directory.resolve("pg-ledger-mcp-admin.service"), UNIT_OF_1X, StandardCharsets.UTF_8);
        }
        return directory;
    }

    private Outcome run(Tool tool, ScriptedServiceControl control, RecordingOutput output, Path units, Path file,
            String... more) {
        List<String> args = new ArrayList<>(List.of("stop-1x", "--unit-dir=" + units));
        if (file != null) {
            args.add("--env=" + file);
        }
        args.addAll(List.of(more));
        return tool.withServiceControl(control).run(tmp.resolve("secrets"), null, output, args.toArray(String[]::new));
    }

    private static void assertNoSecret(Outcome result, RecordingOutput output) {
        String everything = output.info() + "\n" + result.failure() + "\n"
                + result.refusal().map(r -> String.join(" ", r.arguments())).orElse("");
        assertThat(everything).doesNotContain(AGENT_TOKEN).doesNotContain(ADMIN_TOKEN).doesNotContain(DB_PASSWORD);
    }

    @Test
    @DisplayName("PCS-024 (REH4-HOST-01) a public name of DBHUB_ALLOWED_HOSTS that no --mcp-host carries is told as a name that 2.0 no longer accepts, naming the host and the option, and the run goes on: the calls of the service manager are made, no other value of the file is in the output")
    void host01_aNameThatIsNotCarriedIsToldAndTheRunGoesOn(Tool tool) throws IOException {
        Path file = environment("DBHUB_ALLOWED_HOSTS=" + PUBLIC_NAME + ",localhost,127.0.0.1");
        Path units = units(false);
        ScriptedServiceControl control = new ScriptedServiceControl();
        RecordingOutput output = new RecordingOutput();

        Outcome result = run(tool, control, output, units, file);

        assertThat(result.failure()).isNull();
        assertThat(result.exitCode()).isZero();
        assertThat(output.info()).contains(PUBLIC_NAME).contains("--mcp-host").contains("2.0 does not accept");
        assertNoSecret(result, output);
        assertThat(control.calls()).containsExactly("systemctl stop pg-ledger-mcp", "systemctl disable pg-ledger-mcp",
                "systemctl daemon-reload");
        assertThat(units.resolve("pg-ledger-mcp.service.1x-backup")).exists();
    }

    @Test
    @DisplayName("PCS-024 (REH4-HOST-02) the name given with --mcp-host is carried and nothing is told for it; with the other names of the file only those are told")
    void host02_theOptionCarriesTheName(Tool tool) throws IOException {
        Path file = environment("DBHUB_ALLOWED_HOSTS=" + PUBLIC_NAME + ",localhost,127.0.0.1");
        RecordingOutput carried = new RecordingOutput();
        ScriptedServiceControl control = new ScriptedServiceControl();

        Outcome result = run(tool, control, carried, units(false), file, "--mcp-host=" + PUBLIC_NAME);

        assertThat(result.failure()).isNull();
        assertThat(carried.info()).doesNotContain("2.0 does not accept");
        assertThat(control.calls()).isNotEmpty();

        Path several = environment("DBHUB_ALLOWED_HOSTS=a.example.org,b.example.org,localhost");
        RecordingOutput told = new RecordingOutput();
        Outcome second = run(tool, new ScriptedServiceControl(), told, units(false), several, "--mcp-host=b.example.org");

        assertThat(second.failure()).isNull();
        assertThat(told.info()).contains("a.example.org").doesNotContain("b.example.org");
    }

    @Test
    @DisplayName("PCS-024 (REH4-HOST-04) the admin web: PGLEDGER_ADMIN_ALLOWED_HOSTS of a 1.1.0 file is told with --admin-host as its option; the MCP names come first; a name that is not in the file is accepted")
    void host04_theAdminNames(Tool tool) throws IOException {
        Path file = environment("DBHUB_ALLOWED_HOSTS=mcp.example.org", "PGLEDGER_ADMIN_ALLOWED_HOSTS=admin.example.org,127.0.0.1");

        RecordingOutput none = new RecordingOutput();
        Outcome noOption = run(tool, new ScriptedServiceControl(), none, units(false), file);
        assertThat(noOption.failure()).isNull();
        assertThat(none.info().indexOf("mcp.example.org")).isNotNegative().isLessThan(none.info().indexOf("admin.example.org"));
        assertThat(none.info()).contains("--mcp-host").contains("--admin-host");

        RecordingOutput carried = new RecordingOutput();
        Outcome both = run(tool, new ScriptedServiceControl(), carried, units(false), file, "--mcp-host=mcp.example.org",
                "--admin-host=admin.example.org");
        assertThat(both.failure()).isNull();
        assertThat(carried.info()).doesNotContain("2.0 does not accept");

        // a name that the file does not allow is no reason of a refusal (the rule of before)
        Outcome other = run(tool, new ScriptedServiceControl(), new RecordingOutput(), units(false), file,
                "--admin-host=other.example.org");
        assertThat(other.failure()).isNull();
    }

    @Test
    @DisplayName("PCS-024 (REH4-HOST-05) a file that is missing, a directory, not UTF-8 or larger than 64 KiB is ENV_FILE_FAILED (exit 1) with the kind of the failure only, before any call and with nothing moved")
    void host05_anUnusableFile(Tool tool) throws IOException {
        Path notUtf8 = tmp.resolve("not-utf8");
        Files.write(notUtf8, new byte[] { 'A', '=', (byte) 0xC3, (byte) 0x28, '\n' });
        Path large = tmp.resolve("large");
        Files.writeString(large, "DBHUB_AUTH_TOKEN=" + "x".repeat(65 * 1024) + "\n", StandardCharsets.UTF_8);
        Path units = units(false);

        for (Path file : List.of(tmp.resolve("missing"), tmp, notUtf8, large)) {
            ScriptedServiceControl control = new ScriptedServiceControl();
            RecordingOutput output = new RecordingOutput();
            Outcome result = run(tool, control, output, units, file);

            assertThat(result.exitCode()).as(file.toString()).isEqualTo(ExitStatus.FAILURE.code());
            assertThat(result.code()).as(file.toString()).contains(MigrationError.ENV_FILE_FAILED);
            assertThat(output.info()).doesNotContain("xxxxxxxx");
            assertThat(control.calls()).as(file.toString()).isEmpty();
        }
        assertThat(units.resolve("pg-ledger-mcp.service")).exists();
        assertThat(units.resolve("pg-ledger-mcp.service.1x-backup")).doesNotExist();
    }

    @Test
    @DisplayName("PCS-024 (REH4-HOST-06) loopback names only, a file without the key, and no file: nothing is told and the run goes on")
    void host06_noPublicNameNeedsNoOption(Tool tool) throws IOException {
        Path loopback = environment("DBHUB_ALLOWED_HOSTS=localhost,127.0.0.1");
        ScriptedServiceControl first = new ScriptedServiceControl();
        RecordingOutput told = new RecordingOutput();
        assertThat(run(tool, first, told, units(false), loopback).failure()).isNull();
        assertThat(first.calls()).isNotEmpty();
        assertThat(told.info()).doesNotContain("2.0 does not accept");

        Path without = environment();
        ScriptedServiceControl second = new ScriptedServiceControl();
        assertThat(run(tool, second, new RecordingOutput(), units(false), without).failure()).isNull();
        assertThat(second.calls()).isNotEmpty();

        ScriptedServiceControl third = new ScriptedServiceControl();
        assertThat(run(tool, third, new RecordingOutput(), units(false), null).failure()).isNull();
        assertThat(third.calls()).isNotEmpty();
    }

    @Test
    @DisplayName("PCS-024 (REH4-HOST-07) two public names in the file: one of them as the option carries it, the other is told, in any case; an option without a value, or without --env, is refused as the form of the command line")
    void host07_severalNames(Tool tool) throws IOException {
        Path file = environment("DBHUB_ALLOWED_HOSTS=A.example.org,b.example.org,localhost");

        RecordingOutput output = new RecordingOutput();
        ScriptedServiceControl control = new ScriptedServiceControl();
        Outcome one = run(tool, control, output, units(false), file, "--mcp-host=b.example.org");
        Outcome blank = run(tool, new ScriptedServiceControl(), new RecordingOutput(), units(false), file, "--mcp-host=");
        Outcome noEnv = run(tool, new ScriptedServiceControl(), new RecordingOutput(), units(false), null,
                "--mcp-host=a.example.org");

        assertThat(one.failure()).isNull();
        assertThat(output.info()).contains("a.example.org");
        assertThat(control.calls()).isNotEmpty();
        assertThat(blank.exitCode()).isEqualTo(ExitStatus.USAGE.code());
        assertThat(blank.code()).contains(ErrorCode.MISSING_OPTION_VALUE);
        assertThat(noEnv.exitCode()).isEqualTo(ExitStatus.USAGE.code());
        assertThat(noEnv.code()).contains(ErrorCode.INVALID_OPTION_VALUE);
    }

    @ParameterizedTest
    @ValueSource(strings = { "a.example.org,b.example.org", "a.example.org,", ",a.example.org", "A.example.org", "a b", "a.example.org:443",
        "a$b.example.org", "a%b.example.org", "-a.example.org", "a..example.org", "x" + "xxxxxxxxxx".repeat(26) })
    @DisplayName("PCS-024 an option that is no one host name of the form of the installer (a list, a capital letter, a port, a space, a name longer than 253 characters) is INVALID_OPTION_VALUE (exit 2) before any call, for both options")
    void pcs024_theFormIsTheRuleOfTheInstaller(String value, Tool tool) throws IOException {
        Path file = environment("DBHUB_ALLOWED_HOSTS=a.example.org");

        for (String option : List.of("--mcp-host=", "--admin-host=")) {
            ScriptedServiceControl control = new ScriptedServiceControl();
            Outcome refused = run(tool, control, new RecordingOutput(), units(false), file, option + value);

            assertThat(DeployForms.isHostName(value)).as("the rule of the installer for %s", value).isFalse();
            assertThat(refused.exitCode()).as(option + value).isEqualTo(ExitStatus.USAGE.code());
            assertThat(refused.code()).as(option + value).contains(ErrorCode.INVALID_OPTION_VALUE);
            assertThat(control.calls()).as(option + value).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "a.example.org", "ledger", "a-b.example.org", "a1.b2.example.org" })
    @DisplayName("PCS-024 contract: what the installer's rule accepts as a host name is accepted by stop-1x (the one rule, DeployForms.isHostName)")
    void pcs024_whatTheInstallerAcceptsIsAccepted(String value, Tool tool) throws IOException {
        Path file = environment("DBHUB_ALLOWED_HOSTS=" + value);
        ScriptedServiceControl control = new ScriptedServiceControl();

        Outcome accepted = run(tool, control, new RecordingOutput(), units(false), file, "--mcp-host=" + value);

        assertThat(DeployForms.isHostName(value)).isTrue();
        assertThat(accepted.failure()).isNull();
        assertThat(control.calls()).isNotEmpty();
    }
}
