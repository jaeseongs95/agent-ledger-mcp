package org.pgledger.migrate1x.command;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.pgledger.cli.CliMessages;
import org.pgledger.cli.CliOptions;
import org.pgledger.cli.CliOutput;
import org.pgledger.cli.Command;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.FailureException;
import org.pgledger.cli.UsageException;
import org.pgledger.cli.deploy.DeployForms;
import org.pgledger.migrate1x.host.Hosts1x;
import org.pgledger.migrate1x.host.ServiceControl;
import org.pgledger.migrate1x.host.ServiceControl.Result;
import org.pgledger.migrate1x.tool.MigrationError;
import org.pgledger.migrate1x.tool.ToolCommand;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/**
 * {@code stop-1x [--name=<instance>] [--platform=linux] [--unit-dir=<dir>] [--env=<file>
 * [--mcp-host=<host>] [--admin-host=<host>]]}: the first step of the move to 2.0. It stops and disables the
 * two services of the 1.x installation, {@code <name>} (the MCP server) and {@code <name>-admin} (the admin web, absent in
 * 1.0.0), and keeps the definition of each as a backup file next to it, so the old installation can be put back (the rollback of
 * the migration): the unit file {@code <unit-dir>/<service>.service} is moved to {@code <service>.service.1x-backup} (the
 * installer of 2.0 may then write a unit of the same name). Nothing of the 1.x database or of the 1.x environment file is touched.
 * 1.x had Linux (systemd) installations only, so {@code --platform=windows} is refused (NO_1X_SERVICE_WINDOWS): there is nothing
 * of 1.x to stop on Windows, and a service of that name there is one of 2.0 or of the system.
 *
 * <p><b>Why a process:</b> Java has no API for the service manager of the host. The command composes the calls of
 * {@code systemctl} with fixed arguments and the validated name, and {@link ServiceControl} runs them; see
 * {@code SystemServiceControl} for the one place that starts a process.
 *
 * <p>It never touches a service that is not the 1.x ledger: the names are exactly {@code <name>} and {@code <name>-admin}, and
 * the definition must be that of 1.x (the unit has {@code EnvironmentFile=} naming {@code /etc/<name>/env}, which the 1.x
 * installer wrote). Every service is checked before the first change,
 * so a refusal changes nothing. A run that stopped half way, or a second run, continues where it stopped: a service whose
 * definition is kept already is reported as stopped before. A backup file that holds other bytes than the definition is never
 * replaced (BACKUP_DIFFERS).
 *
 * <p>With {@code --env=<file>} (the 1.x environment file) it first compares the public host names the file allows with the one
 * {@code --mcp-host} and the one {@code --admin-host} that the operator will give to the installer of 2.0 (the form of the
 * installer, {@link DeployForms#isHostName}) and tells the names that 2.0 will no longer accept; it refuses nothing for them
 * ({@link Hosts1x}). The platform is an option and not a detection (rule ARC-033); the one that
 * is accepted is the default, {@code linux}.
 */
@Component
public class Stop1xCommand implements Command {

    static final String NAME = "name";
    static final String PLATFORM = "platform";
    static final String UNIT_DIR = "unit-dir";
    static final String ENV = "env";
    static final String MCP_HOST = "mcp-host";
    static final String ADMIN_HOST = "admin-host";

    /** The name of the instance of an installation made with the default name (the 1.x installer's default too). */
    static final String DEFAULT_NAME = "pg-ledger-mcp";
    static final String DEFAULT_UNIT_DIR = "/etc/systemd/system";
    static final String LINUX = "linux";
    static final String WINDOWS = "windows";
    /** What the definition of a 1.x service is kept as: {@code <definition>.1x-backup}. */
    static final String BACKUP_SUFFIX = ".1x-backup";

    /**
     * The form of an instance name that the installer of 1.x accepted ({@code ^[a-z][a-z0-9-]{0,40}$} in its install.sh): a lower-case
     * letter first, then up to 40 lower-case letters, digits or hyphens (a hyphen may be last). This tool takes the names 1.x took; the
     * installer of 2.0 has its own, narrower rule for the names it makes.
     */
    private static final Pattern INSTANCE_NAME = Pattern.compile("[a-z][a-z0-9-]{0,40}");
    private final ServiceControl control;
    private final CliOutput output;
    private final CliMessages messages;

    Stop1xCommand(ServiceControl control, CliOutput output, CliMessages messages) {
        this.control = control;
        this.output = output;
        this.messages = messages;
    }

    @Override
    public ToolCommand name() {
        return ToolCommand.STOP_1X;
    }

    @Override
    public Set<String> options() {
        return Set.of(NAME, PLATFORM, UNIT_DIR, ENV, MCP_HOST, ADMIN_HOST);
    }

    /** What the state of one service is, before anything is done. */
    private enum State {
        /** Neither the definition nor its backup is there: the service is not installed. */
        ABSENT,
        /** The definition is 1.x's and has to be stopped and kept. */
        TO_STOP,
        /** The service was stopped before (the definition is kept, or the service is disabled). */
        STOPPED
    }

    @Override
    public void run(List<String> operands, ApplicationArguments arguments) {
        if (!operands.isEmpty()) {
            throw new UsageException(ErrorCode.TOO_MANY_ARGUMENTS);
        }
        String name = CliOptions.value(arguments, NAME).orElse(DEFAULT_NAME);
        if (!INSTANCE_NAME.matcher(name).matches()) {
            throw new UsageException(ErrorCode.INVALID_OPTION_VALUE);
        }
        String platform = CliOptions.value(arguments, PLATFORM).orElse(LINUX);
        if (Objects.equals(platform, WINDOWS)) {
            // 1.x had no Windows installation (its installer made systemd units only): there is nothing to stop, and a service of
            // that name on Windows is a service of 2.0 or of the system, which this tool never touches
            throw new FailureException(MigrationError.NO_1X_SERVICE_WINDOWS);
        }
        if (!Objects.equals(platform, LINUX)) {
            throw new UsageException(ErrorCode.INVALID_OPTION_VALUE);
        }
        Optional<String> unitDir = CliOptions.value(arguments, UNIT_DIR);
        tellHostNames(arguments);
        stopLinux(List.of(name, name + "-admin"), name, Path.of(unitDir.orElse(DEFAULT_UNIT_DIR)));
    }

    /**
     * The host names of the 1.x environment file that the one name of each option (the form of the installer of 2.0) does not carry
     * are told, before anything is stopped; none of them refuses the run. An option that is no host name of the installer's form (a
     * list, a capital letter, a port) is INVALID_OPTION_VALUE before anything is stopped.
     */
    private void tellHostNames(ApplicationArguments arguments) {
        Optional<String> env = CliOptions.value(arguments, ENV);
        Optional<String> mcpHost = CliOptions.value(arguments, MCP_HOST);
        Optional<String> adminHost = CliOptions.value(arguments, ADMIN_HOST);
        for (Optional<String> name : List.of(mcpHost, adminHost)) {
            if (name.isPresent() && !DeployForms.isHostName(name.get())) {
                throw new UsageException(ErrorCode.INVALID_OPTION_VALUE);
            }
        }
        if (env.isEmpty()) {
            // the names have nothing to be compared with: an option that would pass silently is refused
            if (mcpHost.isPresent() || adminHost.isPresent()) {
                throw new UsageException(ErrorCode.INVALID_OPTION_VALUE);
            }
            return;
        }
        for (Hosts1x.NotAccepted notAccepted : Hosts1x.notAccepted(Path.of(env.orElseThrow()), mcpHost, adminHost)) {
            output.info(messages.get("stop-1x.hosts-not-accepted", String.join(", ", notAccepted.names()), notAccepted.option()));
        }
    }

    // ---------------------------------------------------------------------------------------------------- Linux

    /** One service of a Linux installation: its unit file, the backup of it and what is to be done. */
    private record Unit(String service, Path unit, Path backup, State state) {
    }

    private void stopLinux(List<String> services, String name, Path unitDir) {
        List<Unit> plan = new ArrayList<>();
        for (String service : services) {
            plan.add(inspect(service, name, unitDir));
        }
        if (plan.stream().allMatch(unit -> unit.state() == State.ABSENT)) {
            throw new FailureException(MigrationError.NO_1X_SERVICE, unitDir.toString(), String.join(", ", services));
        }
        for (Unit unit : plan) {
            switch (unit.state()) {
                case ABSENT -> output.info(messages.get("stop-1x.absent", unit.service()));
                case STOPPED -> output.info(messages.get("stop-1x.done", unit.service(), unit.backup().toString()));
                case TO_STOP -> {
                    call("systemctl", "stop", unit.service());
                    call("systemctl", "disable", unit.service());
                    keep(unit);
                    output.info(messages.get("stop-1x.stopped", unit.service(), unit.backup().toString()));
                }
            }
        }
        // the units are gone from the directory: the service manager reads its units again (it is cheap and repeatable)
        call("systemctl", "daemon-reload");
        output.info(messages.get("stop-1x.rollback", services.getFirst()));
    }

    private Unit inspect(String service, String name, Path unitDir) {
        Path unit = unitDir.resolve(service + ".service");
        Path backup = unitDir.resolve(service + ".service" + BACKUP_SUFFIX);
        try {
            if (!Files.exists(unit)) {
                return new Unit(service, unit, backup, Files.exists(backup) ? State.STOPPED : State.ABSENT);
            }
            if (!isOneXUnit(Files.readString(unit, StandardCharsets.UTF_8), name)) {
                throw new FailureException(MigrationError.NOT_1X_SERVICE, unit.toString());
            }
            if (Files.exists(backup) && Files.mismatch(unit, backup) != -1) {
                throw new FailureException(MigrationError.BACKUP_DIFFERS, unit.toString(), backup.toString());
            }
            return new Unit(service, unit, backup, State.TO_STOP);
        } catch (IOException e) {
            throw new FailureException(ErrorCode.IO_FAILED, e, unit.toString(), e.getClass().getSimpleName());
        }
    }

    /** The unit of 1.x reads the environment file {@code /etc/<name>/env} ({@code EnvironmentFile=}, the value may start with {@code -}). */
    static boolean isOneXUnit(String text, String name) {
        String environment = "/etc/" + name + "/env";
        for (String raw : (Iterable<String>) text.lines()::iterator) {
            String line = raw.strip();
            if (line.startsWith("EnvironmentFile=")) {
                String value = line.substring("EnvironmentFile=".length()).strip();
                if (value.startsWith("-")) {
                    value = value.substring(1).strip();
                }
                if (Objects.equals(value, environment)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The definition is kept as the backup: moved when the backup is not there, removed when the backup has its bytes already. */
    private static void keep(Unit unit) {
        try {
            if (Files.exists(unit.backup())) {
                Files.delete(unit.unit());
            } else {
                Files.move(unit.unit(), unit.backup(), StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (IOException e) {
            throw new FailureException(MigrationError.STOP_WRITE_FAILED, e, unit.unit().toString(), e.getClass().getSimpleName());
        }
    }

    private void call(String... command) {
        Result result = control.run(List.of(command));
        if (result.exitCode() != 0) {
            throw new FailureException(MigrationError.SERVICE_COMMAND_FAILED, String.join(" ", command),
                    String.valueOf(result.exitCode()));
        }
    }

}
