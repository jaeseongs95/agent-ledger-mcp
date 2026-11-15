package org.pgledger.migrate1x.command;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
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
import org.pgledger.migrate1x.host.Hosts1x;
import org.pgledger.migrate1x.host.ServiceControl;
import org.pgledger.migrate1x.host.ServiceControl.Result;
import org.pgledger.migrate1x.tool.MigrationError;
import org.pgledger.migrate1x.tool.ToolCommand;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/**
 * {@code stop-1x [--name=<instance>] [--platform=linux|windows] [--unit-dir=<dir>] [--home=<dir>] [--env=<file>
 * --mcp-host=<host>[,<host>...] --admin-host=<host>[,<host>...]]}: the first step of the move to 2.0. It stops and disables the
 * two services of the 1.x installation, {@code <name>} (the MCP server) and {@code <name>-admin} (the admin web, absent in
 * 1.0.0), and keeps the definition of each as a backup file next to it, so the old installation can be put back (the rollback of
 * the migration): on Linux the unit file {@code <unit-dir>/<service>.service} is moved to {@code <service>.service.1x-backup}
 * (the installer of 2.0 may then write a unit of the same name), on Windows the text of {@code sc.exe qc <service>} is saved as
 * {@code <home>/<service>.1x-backup}. Nothing of the 1.x database or of the 1.x environment file is touched.
 *
 * <p><b>Why a process:</b> Java has no API for the service manager of the host. The command composes the calls of
 * {@code systemctl} (Linux) or {@code sc.exe} (Windows) with fixed arguments and the validated name, and
 * {@link ServiceControl} runs them; see {@code SystemServiceControl} for the one place that starts a process.
 *
 * <p>It never touches a service that is not the 1.x ledger: the names are exactly {@code <name>} and {@code <name>-admin}, and
 * the definition must be that of 1.x (the unit has {@code EnvironmentFile=} naming {@code /etc/<name>/env}, which the 1.x
 * installer wrote; a Windows service whose binary is a jar of 2.0 is refused). Every service is checked before the first change,
 * so a refusal changes nothing. A run that stopped half way, or a second run, continues where it stopped: a service whose
 * definition is kept already is reported as stopped before. A backup file that holds other bytes than the definition is never
 * replaced (BACKUP_DIFFERS).
 *
 * <p>With {@code --env=<file>} (the 1.x environment file) it first compares the public host names the file allows with the
 * {@code --mcp-host} and {@code --admin-host} that the operator will give to the installer of 2.0, and refuses before anything is
 * stopped when a name would be lost ({@link Hosts1x}). The platform is an option and not a detection (rule ARC-033).
 */
@Component
public class Stop1xCommand implements Command {

    static final String NAME = "name";
    static final String PLATFORM = "platform";
    static final String UNIT_DIR = "unit-dir";
    static final String HOME = "home";
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

    /** The form of an instance name: a letter first, letters, digits and hyphens, no hyphen last, at most 23 characters. */
    private static final Pattern INSTANCE_NAME = Pattern.compile("[a-z]([a-z0-9-]{0,21}[a-z0-9])?");
    /** The Windows error of {@code sc.exe} for a service that does not exist, and for a stop of a service that does not run. */
    private static final int SERVICE_DOES_NOT_EXIST = 1060;
    private static final int SERVICE_NOT_STARTED = 1062;
    /** {@code START_TYPE} 4 is DISABLED in the output of {@code sc.exe qc}, in any language of Windows. */
    private static final String DISABLED = "4";

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
        return Set.of(NAME, PLATFORM, UNIT_DIR, HOME, ENV, MCP_HOST, ADMIN_HOST);
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
        boolean windows = platformIsWindows(CliOptions.value(arguments, PLATFORM).orElse(LINUX));
        Optional<String> unitDir = CliOptions.value(arguments, UNIT_DIR);
        Optional<String> home = CliOptions.value(arguments, HOME);
        if (windows ? unitDir.isPresent() : home.isPresent()) {
            throw new UsageException(ErrorCode.INVALID_OPTION_VALUE);
        }
        checkHostNames(arguments);
        List<String> services = List.of(name, name + "-admin");
        if (windows) {
            stopWindows(services, Path.of(home.orElse("C:/ProgramData/" + name)));
        } else {
            stopLinux(services, name, Path.of(unitDir.orElse(DEFAULT_UNIT_DIR)));
        }
    }

    private static boolean platformIsWindows(String platform) {
        if (Objects.equals(platform, WINDOWS)) {
            return true;
        }
        if (Objects.equals(platform, LINUX)) {
            return false;
        }
        throw new UsageException(ErrorCode.INVALID_OPTION_VALUE);
    }

    /** The host names of the 1.x environment file against the options the installer of 2.0 will get; before anything is stopped. */
    private void checkHostNames(ApplicationArguments arguments) {
        Optional<String> env = CliOptions.value(arguments, ENV);
        List<String> mcpHosts = names(CliOptions.value(arguments, MCP_HOST).orElse(""));
        List<String> adminHosts = names(CliOptions.value(arguments, ADMIN_HOST).orElse(""));
        if (env.isEmpty()) {
            // the names have nothing to be compared with: an option that would pass silently is refused
            if (!mcpHosts.isEmpty() || !adminHosts.isEmpty()) {
                throw new UsageException(ErrorCode.INVALID_OPTION_VALUE);
            }
            return;
        }
        Hosts1x.require(Path.of(env.orElseThrow()), mcpHosts, adminHosts);
    }

    /** The names of a comma list as given ({@code ""} when the option was not given). */
    private static List<String> names(String option) {
        List<String> names = new ArrayList<>();
        for (String entry : option.split(",")) {
            if (!entry.isBlank()) {
                names.add(entry.strip());
            }
        }
        return names;
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

    // -------------------------------------------------------------------------------------------------- Windows

    /** One service of a Windows installation: the text of its definition, the backup file and what is to be done. */
    private record Service(String service, String definition, Path backup, State state) {
    }

    private void stopWindows(List<String> services, Path home) {
        List<Service> plan = new ArrayList<>();
        for (String service : services) {
            plan.add(inspectWindows(service, home));
        }
        if (plan.stream().allMatch(service -> service.state() == State.ABSENT)) {
            throw new FailureException(MigrationError.NO_1X_SERVICE, home.toString(), String.join(", ", services));
        }
        for (Service service : plan) {
            switch (service.state()) {
                case ABSENT -> output.info(messages.get("stop-1x.absent", service.service()));
                case STOPPED -> output.info(messages.get("stop-1x.done", service.service(), service.backup().toString()));
                case TO_STOP -> {
                    // the definition is saved before the first change, so the start type it had is in the file
                    save(service);
                    stop(service.service());
                    call("sc.exe", "config", service.service(), "start=", "disabled");
                    output.info(messages.get("stop-1x.stopped", service.service(), service.backup().toString()));
                }
            }
        }
        output.info(messages.get("stop-1x.rollback.windows", services.getFirst()));
    }

    private Service inspectWindows(String service, Path home) {
        Path backup = home.resolve(service + BACKUP_SUFFIX);
        Result result = control.run(List.of("sc.exe", "qc", service));
        if (result.exitCode() == SERVICE_DOES_NOT_EXIST) {
            return new Service(service, "", backup, State.ABSENT);
        }
        if (result.exitCode() != 0) {
            throw new FailureException(MigrationError.SERVICE_COMMAND_FAILED, "sc.exe qc " + service,
                    String.valueOf(result.exitCode()));
        }
        String definition = result.output();
        // a service of 2.0 (the jars of the services of 2.0 are its binary) is not the 1.x ledger, whatever it is called
        if (definition.contains("ledger-mcp-server.jar") || definition.contains("ledger-admin-web.jar")) {
            throw new FailureException(MigrationError.NOT_1X_SERVICE, service);
        }
        return new Service(service, definition, backup, startTypeIsDisabled(definition) ? State.STOPPED : State.TO_STOP);
    }

    /** The third word of the line {@code START_TYPE : 4 DISABLED} is the number of the start type; 4 is disabled. */
    static boolean startTypeIsDisabled(String definition) {
        for (String raw : (Iterable<String>) definition.lines()::iterator) {
            String[] words = raw.strip().split("\\s+");
            if (words.length >= 3 && Objects.equals(words[0], "START_TYPE")) {
                return Objects.equals(words[2], DISABLED);
            }
        }
        return false;
    }

    private static void save(Service service) {
        try {
            if (!Files.exists(service.backup())) {
                Files.writeString(service.backup(), service.definition(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW);
            }
        } catch (IOException e) {
            throw new FailureException(MigrationError.STOP_WRITE_FAILED, e, service.backup().toString(),
                    e.getClass().getSimpleName());
        }
    }

    /** {@code sc.exe stop} of a service that does not run is the error 1062: the service is stopped, which is what is wanted. */
    private void stop(String service) {
        Result result = control.run(List.of("sc.exe", "stop", service));
        if (result.exitCode() != 0 && result.exitCode() != SERVICE_NOT_STARTED) {
            throw new FailureException(MigrationError.SERVICE_COMMAND_FAILED, "sc.exe stop " + service,
                    String.valueOf(result.exitCode()));
        }
    }
}
