package org.pgledger.migrate1x.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.pgledger.migrate1x.host.ServiceControl.Result;
import org.pgledger.migrate1x.support.EnglishMessages;
import org.pgledger.migrate1x.support.RecordingOutput;
import org.pgledger.migrate1x.support.ScriptedServiceControl;
import org.pgledger.migrate1x.support.ToolExtension;
import org.pgledger.migrate1x.support.ToolExtension.Outcome;
import org.pgledger.migrate1x.support.ToolExtension.Tool;
import org.pgledger.migrate1x.tool.ErrorCode;
import org.pgledger.migrate1x.tool.ExitStatus;

/**
 * M1X-010 .. M1X-018 and M1X-040: {@code stop-1x} composes its calls to the service manager and keeps the definitions, against a
 * recorder that answers the calls (no real service is stopped here; the real stop is verified on the test server).
 */
@ExtendWith({ ToolExtension.class, EnglishMessages.class })
class Stop1xCommandTest {

    private static final String NAME = "pg-ledger-mcp";
    private static final String ADMIN = NAME + "-admin";
    private static final String ONE_X_UNIT = """
            [Unit]
            Description=PG ledger MCP 1.x
            [Service]
            EnvironmentFile=/etc/%s/env
            ExecStart=/usr/bin/node /opt/%s/server.mjs
            """;

    @TempDir
    Path tmp;

    private Path unitDir() throws IOException {
        return Files.createDirectories(tmp.resolve("units"));
    }

    private Path unit(Path dir, String service, String name) throws IOException {
        return Files.writeString(dir.resolve(service + ".service"), ONE_X_UNIT.formatted(name, name), StandardCharsets.UTF_8);
    }

    private Outcome run(Tool tool, RecordingOutput output, String... args) {
        return tool.run(tmp.resolve("secrets"), null, output, args);
    }

    @Test
    @DisplayName("M1X-010 the two units of 1.x are stopped, disabled and kept as <unit>.1x-backup, in this order of calls, and the service manager reloads its units")
    void m1x010_stopsDisablesAndKeepsTheUnits(Tool tool) throws IOException {
        Path dir = unitDir();
        Path mcp = unit(dir, NAME, NAME);
        Path admin = unit(dir, ADMIN, NAME);
        String mcpText = Files.readString(mcp);
        ScriptedServiceControl control = new ScriptedServiceControl();
        RecordingOutput output = new RecordingOutput();

        Outcome outcome = run(tool.withServiceControl(control), output, "stop-1x", "--unit-dir=" + dir);

        assertThat(outcome.exitCode()).isEqualTo(ExitStatus.SUCCESS.code());
        assertThat(control.calls()).containsExactly("systemctl stop " + NAME, "systemctl disable " + NAME,
                "systemctl stop " + ADMIN, "systemctl disable " + ADMIN, "systemctl daemon-reload");
        assertThat(mcp).doesNotExist();
        assertThat(admin).doesNotExist();
        assertThat(Files.readString(dir.resolve(NAME + ".service.1x-backup"))).isEqualTo(mcpText);
        assertThat(dir.resolve(ADMIN + ".service.1x-backup")).exists();
        assertThat(output.info()).contains("The 1.x service " + NAME + " was stopped and disabled")
                .contains(dir.resolve(NAME + ".service.1x-backup").toString())
                .contains("To return to the 1.x installation");
    }

    @Test
    @DisplayName("M1X-011 --name=other touches only the services other and other-admin; a name that is no instance name is exit 2 and makes no call")
    void m1x011_onlyTheNamedInstance(Tool tool) throws IOException {
        Path dir = unitDir();
        unit(dir, "other", "other");
        unit(dir, "other-admin", "other");
        unit(dir, NAME, NAME);
        ScriptedServiceControl control = new ScriptedServiceControl();

        Outcome outcome = run(tool.withServiceControl(control), new RecordingOutput(), "stop-1x", "--unit-dir=" + dir,
                "--name=other");

        assertThat(outcome.exitCode()).isEqualTo(ExitStatus.SUCCESS.code());
        assertThat(control.calls()).containsExactly("systemctl stop other", "systemctl disable other",
                "systemctl stop other-admin", "systemctl disable other-admin", "systemctl daemon-reload");
        assertThat(dir.resolve(NAME + ".service")).exists();
        assertThat(dir.resolve(NAME + ".service.1x-backup")).doesNotExist();

        for (String bad : List.of("Other", "has space", "a/b", "-x", "x-", "a".repeat(24))) {
            ScriptedServiceControl none = new ScriptedServiceControl();
            Outcome refused = run(tool.withServiceControl(none), new RecordingOutput(), "stop-1x", "--unit-dir=" + dir,
                    "--name=" + bad);
            assertThat(refused.code()).as(bad).contains(ErrorCode.INVALID_OPTION_VALUE);
            assertThat(refused.exitCode()).as(bad).isEqualTo(ExitStatus.USAGE.code());
            assertThat(none.calls()).as(bad).isEmpty();
        }
    }

    @Test
    @DisplayName("M1X-012 a unit that is not the unit of 1.x is NOT_1X_SERVICE (exit 1); no call is made and nothing is moved, also not the other unit")
    void m1x012_aUnitOfAnotherKindIsNeverTouched(Tool tool) throws IOException {
        Path dir = unitDir();
        Path mcp = unit(dir, NAME, NAME);
        Path admin = dir.resolve(ADMIN + ".service");
        Files.writeString(admin, "[Service]\nExecStart=/usr/bin/java -jar /opt/pg-ledger-mcp/ledger-admin-web.jar\n");
        ScriptedServiceControl control = new ScriptedServiceControl();

        Outcome outcome = run(tool.withServiceControl(control), new RecordingOutput(), "stop-1x", "--unit-dir=" + dir);

        assertThat(outcome.code()).contains(ErrorCode.NOT_1X_SERVICE);
        assertThat(outcome.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
        assertThat(outcome.refusal().orElseThrow().arguments()).containsExactly(admin.toString());
        assertThat(control.calls()).isEmpty();
        assertThat(mcp).exists();
        assertThat(admin).exists();
        assertThat(dir.resolve(NAME + ".service.1x-backup")).doesNotExist();
    }

    @Test
    @DisplayName("M1X-013 a 1.0.0 installation (the unit of the MCP server only) is stopped and the admin service is reported as not installed; with no unit at all the run is NO_1X_SERVICE")
    void m1x013_onlyTheMcpServerOrNone(Tool tool) throws IOException {
        Path dir = unitDir();
        unit(dir, NAME, NAME);
        ScriptedServiceControl control = new ScriptedServiceControl();
        RecordingOutput output = new RecordingOutput();

        Outcome outcome = run(tool.withServiceControl(control), output, "stop-1x", "--unit-dir=" + dir);

        assertThat(outcome.exitCode()).isEqualTo(ExitStatus.SUCCESS.code());
        assertThat(control.calls()).containsExactly("systemctl stop " + NAME, "systemctl disable " + NAME,
                "systemctl daemon-reload");
        assertThat(output.info()).contains("The 1.x service " + ADMIN + " is not installed.");

        Path empty = Files.createDirectories(tmp.resolve("empty"));
        ScriptedServiceControl none = new ScriptedServiceControl();
        Outcome refused = run(tool.withServiceControl(none), new RecordingOutput(), "stop-1x", "--unit-dir=" + empty);
        assertThat(refused.code()).contains(ErrorCode.NO_1X_SERVICE);
        assertThat(refused.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
        assertThat(none.calls()).isEmpty();
    }

    @Test
    @DisplayName("M1X-014 a second run only reloads the units and says both services were stopped before; a backup with other bytes is never replaced (BACKUP_DIFFERS); a failing call is SERVICE_COMMAND_FAILED and a rerun continues")
    void m1x014_rerunsAndRefusals(Tool tool) throws IOException {
        Path dir = unitDir();
        unit(dir, NAME, NAME);
        unit(dir, ADMIN, NAME);
        run(tool.withServiceControl(new ScriptedServiceControl()), new RecordingOutput(), "stop-1x", "--unit-dir=" + dir);

        ScriptedServiceControl again = new ScriptedServiceControl();
        RecordingOutput output = new RecordingOutput();
        Outcome second = run(tool.withServiceControl(again), output, "stop-1x", "--unit-dir=" + dir);
        assertThat(second.exitCode()).isEqualTo(ExitStatus.SUCCESS.code());
        assertThat(again.calls()).containsExactly("systemctl daemon-reload");
        assertThat(output.info()).contains("The 1.x service " + NAME + " was stopped before");

        // the unit is back (a hand-written copy of 1.x) and the backup holds other bytes: nothing is replaced
        Path unitAgain = unit(dir, NAME, NAME);
        Files.writeString(unitAgain, ONE_X_UNIT.formatted(NAME, NAME) + "# edited\n");
        ScriptedServiceControl none = new ScriptedServiceControl();
        Outcome differs = run(tool.withServiceControl(none), new RecordingOutput(), "stop-1x", "--unit-dir=" + dir);
        assertThat(differs.code()).contains(ErrorCode.BACKUP_DIFFERS);
        assertThat(none.calls()).isEmpty();
        assertThat(unitAgain).exists();

        // a failing call: the service is not moved, a rerun runs the calls again
        Path other = Files.createDirectories(tmp.resolve("failing"));
        Path failingUnit = unit(other, NAME, NAME);
        ScriptedServiceControl failing = new ScriptedServiceControl(
                command -> new Result(command.contains("disable") ? 5 : 0, ""));
        Outcome failed = run(tool.withServiceControl(failing), new RecordingOutput(), "stop-1x", "--unit-dir=" + other);
        assertThat(failed.code()).contains(ErrorCode.SERVICE_COMMAND_FAILED);
        assertThat(failed.refusal().orElseThrow().arguments()).containsExactly("systemctl disable " + NAME, "5");
        assertThat(failed.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
        assertThat(failingUnit).exists();
        ScriptedServiceControl fixed = new ScriptedServiceControl();
        assertThat(run(tool.withServiceControl(fixed), new RecordingOutput(), "stop-1x", "--unit-dir=" + other).exitCode())
                .isEqualTo(ExitStatus.SUCCESS.code());
        assertThat(fixed.calls()).contains("systemctl stop " + NAME, "systemctl disable " + NAME);
        assertThat(failingUnit).doesNotExist();
    }

    @Test
    @DisplayName("M1X-015 on Windows the definition is read with sc.exe qc, kept as <home>/<service>.1x-backup, the service is stopped and disabled; a service of 2.0 is NOT_1X_SERVICE")
    void m1x015_windows(Tool tool) throws IOException {
        Path home = Files.createDirectories(tmp.resolve("home"));
        String definition = """
                [SC] QueryServiceConfig SUCCESS

                SERVICE_NAME: %s
                        TYPE               : 10  WIN32_OWN_PROCESS
                        START_TYPE         : 2   AUTO_START
                        BINARY_PATH_NAME   : C:\\pg-ledger-mcp\\node.exe server.mjs
                """;
        ScriptedServiceControl control = new ScriptedServiceControl(command -> new Result(0,
                command.get(1).equals("qc") ? definition.formatted(command.get(2)) : ""));

        Outcome outcome = run(tool.withServiceControl(control), new RecordingOutput(), "stop-1x", "--platform=windows",
                "--home=" + home);

        assertThat(outcome.exitCode()).isEqualTo(ExitStatus.SUCCESS.code());
        assertThat(control.calls()).containsExactly("sc.exe qc " + NAME, "sc.exe qc " + ADMIN, "sc.exe stop " + NAME,
                "sc.exe config " + NAME + " start= disabled", "sc.exe stop " + ADMIN,
                "sc.exe config " + ADMIN + " start= disabled");
        assertThat(Files.readString(home.resolve(NAME + ".1x-backup"))).contains("START_TYPE         : 2   AUTO_START");
        assertThat(home.resolve(ADMIN + ".1x-backup")).exists();

        ScriptedServiceControl current = new ScriptedServiceControl(command -> new Result(0, command.get(1).equals("qc")
                ? "BINARY_PATH_NAME : C:\\pg-ledger-mcp\\lib\\ledger-mcp-server.jar\n" : ""));
        Path other = Files.createDirectories(tmp.resolve("home2"));
        Outcome refused = run(tool.withServiceControl(current), new RecordingOutput(), "stop-1x", "--platform=windows",
                "--home=" + other);
        assertThat(refused.code()).contains(ErrorCode.NOT_1X_SERVICE);
        assertThat(current.calls()).containsExactly("sc.exe qc " + NAME);
        assertThat(other).isEmptyDirectory();
    }

    @Test
    @DisplayName("M1X-016 the host names of the 1.x environment file must be carried by --mcp-host and --admin-host: refused before any call; carried, the run goes on; an unreadable file is ENV_FILE_FAILED")
    void m1x016_hostNames(Tool tool) throws IOException {
        Path dir = unitDir();
        unit(dir, NAME, NAME);
        Path env = Files.writeString(tmp.resolve("env"),
                "DBHUB_ALLOWED_HOSTS=ledger.example.org,localhost,127.0.0.1\nDBHUB_AUTH_TOKEN=x\n");
        ScriptedServiceControl none = new ScriptedServiceControl();

        Outcome dropped = run(tool.withServiceControl(none), new RecordingOutput(), "stop-1x", "--unit-dir=" + dir,
                "--env=" + env);
        assertThat(dropped.code()).contains(ErrorCode.HOSTS_DROPPED);
        assertThat(dropped.exitCode()).isEqualTo(ExitStatus.USAGE.code());
        assertThat(dropped.refusal().orElseThrow().arguments()).containsExactly("ledger.example.org", "mcp-host");
        assertThat(none.calls()).isEmpty();
        assertThat(dir.resolve(NAME + ".service")).exists();

        ScriptedServiceControl carried = new ScriptedServiceControl();
        Outcome ok = run(tool.withServiceControl(carried), new RecordingOutput(), "stop-1x", "--unit-dir=" + dir,
                "--env=" + env, "--mcp-host=Ledger.Example.org");
        assertThat(ok.exitCode()).isEqualTo(ExitStatus.SUCCESS.code());
        assertThat(carried.calls()).startsWith("systemctl stop " + NAME);

        ScriptedServiceControl missing = new ScriptedServiceControl();
        Outcome unreadable = run(tool.withServiceControl(missing), new RecordingOutput(), "stop-1x", "--unit-dir=" + dir,
                "--env=" + tmp.resolve("no-such-file"));
        assertThat(unreadable.code()).contains(ErrorCode.ENV_FILE_FAILED);
        assertThat(unreadable.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
        assertThat(missing.calls()).isEmpty();
    }

    @Test
    @DisplayName("M1X-017 an operand, an unknown option, host names without --env and the names of a file that allows another one are refused with exit 2 and no call")
    void m1x017_usage(Tool tool) throws IOException {
        Path dir = unitDir();
        unit(dir, NAME, NAME);
        Path env = Files.writeString(tmp.resolve("env"), "DBHUB_ALLOWED_HOSTS=ledger.example.org\n");
        record Case(ErrorCode code, String... args) {
        }
        for (Case refused : List.of(
                new Case(ErrorCode.TOO_MANY_ARGUMENTS, "stop-1x", "extra", "--unit-dir=" + dir),
                new Case(ErrorCode.UNKNOWN_OPTION, "stop-1x", "--force", "--unit-dir=" + dir),
                new Case(ErrorCode.HOSTS_DROPPED, "stop-1x", "--unit-dir=" + dir, "--env=" + env),
                new Case(ErrorCode.INVALID_OPTION_VALUE, "stop-1x", "--unit-dir=" + dir, "--mcp-host=a.example.org"))) {
            ScriptedServiceControl none = new ScriptedServiceControl();
            Outcome outcome = run(tool.withServiceControl(none), new RecordingOutput(), refused.args());
            assertThat(outcome.code()).as(refused.code().name()).contains(refused.code());
            assertThat(outcome.exitCode()).as(refused.code().name()).isEqualTo(ExitStatus.USAGE.code());
            assertThat(none.calls()).isEmpty();
            assertThat(dir.resolve(NAME + ".service")).exists();
        }
    }

    @Test
    @DisplayName("M1X-018 an option of the other platform and an unknown platform are INVALID_OPTION_VALUE (exit 2); no call, no file")
    void m1x018_platformOptions(Tool tool) throws IOException {
        Path dir = unitDir();
        unit(dir, NAME, NAME);
        for (String[] args : new String[][] {
                { "stop-1x", "--platform=windows", "--unit-dir=" + dir },
                { "stop-1x", "--platform=linux", "--home=" + tmp },
                { "stop-1x", "--platform=macos" } }) {
            ScriptedServiceControl none = new ScriptedServiceControl();
            Outcome outcome = run(tool.withServiceControl(none), new RecordingOutput(), args);
            assertThat(outcome.code()).as(String.join(" ", args)).contains(ErrorCode.INVALID_OPTION_VALUE);
            assertThat(outcome.exitCode()).isEqualTo(ExitStatus.USAGE.code());
            assertThat(none.calls()).isEmpty();
        }
        assertThat(dir.resolve(NAME + ".service")).exists();
    }

    @Test
    @DisplayName("M1X-040 a daemon-reload that fails leaves the units moved to their backups (exit 1); the rerun reports both services as stopped before and reloads again")
    void m1x040_aFailingReload(Tool tool) throws IOException {
        Path dir = unitDir();
        unit(dir, NAME, NAME);
        unit(dir, ADMIN, NAME);
        ScriptedServiceControl failing = new ScriptedServiceControl(
                command -> new Result(command.contains("daemon-reload") ? 1 : 0, ""));

        Outcome failed = run(tool.withServiceControl(failing), new RecordingOutput(), "stop-1x", "--unit-dir=" + dir);

        assertThat(failed.code()).contains(ErrorCode.SERVICE_COMMAND_FAILED);
        assertThat(failed.refusal().orElseThrow().arguments()).containsExactly("systemctl daemon-reload", "1");
        assertThat(dir.resolve(NAME + ".service")).doesNotExist();
        assertThat(dir.resolve(NAME + ".service.1x-backup")).exists();

        ScriptedServiceControl working = new ScriptedServiceControl();
        RecordingOutput output = new RecordingOutput();
        assertThat(run(tool.withServiceControl(working), output, "stop-1x", "--unit-dir=" + dir).exitCode())
                .isEqualTo(ExitStatus.SUCCESS.code());
        assertThat(working.calls()).containsExactly("systemctl daemon-reload");
        assertThat(output.info()).contains("was stopped before");
    }
}
