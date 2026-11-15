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
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.ExitStatus;
import org.pgledger.migrate1x.support.EnglishMessages;
import org.pgledger.migrate1x.support.RecordingOutput;
import org.pgledger.migrate1x.support.ScriptedServiceControl;
import org.pgledger.migrate1x.support.ToolExtension;
import org.pgledger.migrate1x.support.ToolExtension.Outcome;
import org.pgledger.migrate1x.support.ToolExtension.Tool;
import org.pgledger.migrate1x.tool.MigrationError;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * REH4-HOST-01, 02, 04 to 07: the public host names of a 1.x environment file are carried by the options or {@code stop-1x} is
 * refused before anything is stopped or moved ({@code --env}, {@code --mcp-host}, {@code --admin-host}). The service manager is
 * a recording double: a refusal is a run without a single call, and a run that goes on has its calls recorded.
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
    @DisplayName("REH4-HOST-01 a public name of DBHUB_ALLOWED_HOSTS that no --mcp-host carries refuses stop-1x, naming the host and the option, before any call and with nothing moved and no other value of the file in a message")
    void host01_aDroppedNameRefusesBeforeAnyChange(Tool tool) throws IOException {
        Path file = environment("DBHUB_ALLOWED_HOSTS=" + PUBLIC_NAME + ",localhost,127.0.0.1");
        Path units = units(false);
        ScriptedServiceControl control = new ScriptedServiceControl();
        RecordingOutput output = new RecordingOutput();

        Outcome result = run(tool, control, output, units, file);

        assertThat(result.exitCode()).isEqualTo(ExitStatus.USAGE.code());
        assertThat(result.code()).contains(MigrationError.HOSTS_DROPPED);
        assertThat(result.refusal().orElseThrow().arguments()).containsExactly(PUBLIC_NAME, "mcp-host");
        assertNoSecret(result, output);
        assertThat(control.calls()).as("no call of the service manager").isEmpty();
        assertThat(units.resolve("pg-ledger-mcp.service")).as("the unit stays").exists();
        assertThat(units.resolve("pg-ledger-mcp.service.1x-backup")).as("nothing is kept").doesNotExist();
    }

    @Test
    @DisplayName("REH4-HOST-02 the name given with --mcp-host is accepted and the run goes on: the calls of the service manager are recorded and the unit is kept as its backup")
    void host02_theOptionCarriesTheName(Tool tool) throws IOException {
        Path file = environment("DBHUB_ALLOWED_HOSTS=" + PUBLIC_NAME + ",localhost,127.0.0.1");
        Path units = units(false);
        ScriptedServiceControl control = new ScriptedServiceControl();

        Outcome result = run(tool, control, new RecordingOutput(), units, file, "--mcp-host=" + PUBLIC_NAME);

        assertThat(result.failure()).isNull();
        assertThat(result.exitCode()).isZero();
        assertThat(control.calls()).containsExactly("systemctl stop pg-ledger-mcp", "systemctl disable pg-ledger-mcp",
                "systemctl daemon-reload");
        assertThat(units.resolve("pg-ledger-mcp.service")).doesNotExist();
        assertThat(units.resolve("pg-ledger-mcp.service.1x-backup")).exists();
    }

    @Test
    @DisplayName("REH4-HOST-02 with both services installed the refusal comes before the first of them is stopped: both units stay and no call is made")
    void host02_theCheckComesBeforeBothServices(Tool tool) throws IOException {
        Path file = environment("DBHUB_ALLOWED_HOSTS=" + PUBLIC_NAME);
        Path units = units(true);
        ScriptedServiceControl control = new ScriptedServiceControl();

        Outcome result = run(tool, control, new RecordingOutput(), units, file);

        assertThat(result.code()).contains(MigrationError.HOSTS_DROPPED);
        assertThat(result.refusal().orElseThrow().arguments()).containsExactly(PUBLIC_NAME, "mcp-host");
        assertThat(control.calls()).isEmpty();
        assertThat(units.resolve("pg-ledger-mcp.service")).exists();
        assertThat(units.resolve("pg-ledger-mcp-admin.service")).exists();
    }

    @Test
    @DisplayName("REH4-HOST-04 the admin web: PGLEDGER_ADMIN_ALLOWED_HOSTS of a 1.1.0 file is compared with --admin-host (one name or a list); the MCP names are named first")
    void host04_theAdminNames(Tool tool) throws IOException {
        Path file = environment("DBHUB_ALLOWED_HOSTS=127.0.0.1", "PGLEDGER_ADMIN_ALLOWED_HOSTS=admin.example.org,127.0.0.1");

        for (String option : List.of("--admin-host=other.example.org", "--mcp-host=mcp.example.org")) {
            ScriptedServiceControl control = new ScriptedServiceControl();
            Outcome refused = run(tool, control, new RecordingOutput(), units(false), file, option);
            assertThat(refused.exitCode()).as(option).isEqualTo(ExitStatus.USAGE.code());
            assertThat(refused.refusal().orElseThrow().code()).isEqualTo(MigrationError.HOSTS_DROPPED);
            assertThat(refused.refusal().orElseThrow().arguments()).as(option).containsExactly("admin.example.org",
                    "admin-host");
            assertThat(control.calls()).as(option).isEmpty();
        }
        for (String option : List.of("--admin-host=admin.example.org", "--admin-host=other.example.org,admin.example.org")) {
            ScriptedServiceControl control = new ScriptedServiceControl();
            Outcome accepted = run(tool, control, new RecordingOutput(), units(false), file, option);
            assertThat(accepted.failure()).as(option).isNull();
            assertThat(control.calls()).as(option).isNotEmpty();
        }

        Path both = environment("DBHUB_ALLOWED_HOSTS=mcp.example.org", "PGLEDGER_ADMIN_ALLOWED_HOSTS=admin.example.org");
        Outcome first = run(tool, new ScriptedServiceControl(), new RecordingOutput(), units(false), both);
        assertThat(first.refusal().orElseThrow().arguments()).containsExactly("mcp.example.org", "mcp-host");
    }

    @Test
    @DisplayName("REH4-HOST-05 a file that is missing, a directory, not UTF-8 or larger than 64 KiB is ENV_FILE_FAILED (exit 1) with the kind of the failure only, before any call and with nothing moved")
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
    @DisplayName("REH4-HOST-06 loopback names only, a file without the key, and no file: no option is needed and the run goes on")
    void host06_noPublicNameNeedsNoOption(Tool tool) throws IOException {
        Path loopback = environment("DBHUB_ALLOWED_HOSTS=localhost,127.0.0.1");
        ScriptedServiceControl first = new ScriptedServiceControl();
        assertThat(run(tool, first, new RecordingOutput(), units(false), loopback).failure()).isNull();
        assertThat(first.calls()).isNotEmpty();

        Path without = environment();
        ScriptedServiceControl second = new ScriptedServiceControl();
        assertThat(run(tool, second, new RecordingOutput(), units(false), without).failure()).isNull();
        assertThat(second.calls()).isNotEmpty();

        ScriptedServiceControl third = new ScriptedServiceControl();
        assertThat(run(tool, third, new RecordingOutput(), units(false), null).failure()).isNull();
        assertThat(third.calls()).isNotEmpty();
    }

    @Test
    @DisplayName("REH4-HOST-07 two public names: both names in one option are accepted, in any case; one of them is named when the other is missing; an option without a value, or without --env, is refused as the form of the command line")
    void host07_severalNames(Tool tool) throws IOException {
        Path file = environment("DBHUB_ALLOWED_HOSTS=a.example.org,b.example.org,localhost");

        Outcome both = run(tool, new ScriptedServiceControl(), new RecordingOutput(), units(false), file,
                "--mcp-host=a.example.org,b.example.org");
        Outcome upper = run(tool, new ScriptedServiceControl(), new RecordingOutput(), units(false), file,
                "--mcp-host=A.example.org,b.example.org");
        ScriptedServiceControl refusedControl = new ScriptedServiceControl();
        Outcome one = run(tool, refusedControl, new RecordingOutput(), units(false), file, "--mcp-host=b.example.org");
        Outcome blank = run(tool, new ScriptedServiceControl(), new RecordingOutput(), units(false), file, "--mcp-host=");
        Outcome noEnv = run(tool, new ScriptedServiceControl(), new RecordingOutput(), units(false), null,
                "--mcp-host=a.example.org");

        assertThat(both.failure()).isNull();
        assertThat(upper.failure()).isNull();
        assertThat(one.code()).contains(MigrationError.HOSTS_DROPPED);
        assertThat(one.refusal().orElseThrow().arguments()).containsExactly("a.example.org", "mcp-host");
        assertThat(refusedControl.calls()).isEmpty();
        assertThat(blank.exitCode()).isEqualTo(ExitStatus.USAGE.code());
        assertThat(blank.code()).contains(ErrorCode.MISSING_OPTION_VALUE);
        assertThat(noEnv.exitCode()).isEqualTo(ExitStatus.USAGE.code());
        assertThat(noEnv.code()).contains(ErrorCode.INVALID_OPTION_VALUE);
    }
}
