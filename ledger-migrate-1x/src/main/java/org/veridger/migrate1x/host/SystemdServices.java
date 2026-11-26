// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.migrate1x.host;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.veridger.cli.FailureException;
import org.veridger.cli.deploy.DeployLayout;
import org.veridger.migrate1x.host.ServiceControl.Result;
import org.veridger.migrate1x.tool.MigrationError;
import org.springframework.stereotype.Component;

/**
 * The Linux platform of {@code stop-1x}: what belongs to systemd, the service manager of the one platform that 1.x was installed on.
 * It holds the calls of {@code systemctl} (fixed words and a validated name of a service, made through {@link ServiceControl}, the
 * one boundary that starts a process), the directory the units are in and the environment file a 1.x unit reads, which it takes from
 * {@link DeployLayout}, where the installer defines the places of a Linux instance (1.x used the same places). The command
 * {@code stop-1x} holds the steps of the move and asks this class for everything that is systemd's.
 */
@Component
public class SystemdServices {

    private static final String SYSTEMCTL = "systemctl";

    private final ServiceControl control;

    SystemdServices(ServiceControl control) {
        this.control = control;
    }

    /** The directory that systemd reads the units of the administrator from (where 1.x and 2.0 wrote them). */
    public Path defaultUnitDirectory() {
        return Path.of(DeployLayout.UNIT_DIRECTORY);
    }

    /** The environment file that the unit of the 1.x instance {@code name} reads (the 1.x installer wrote it with the settings). */
    public String environmentFile(String name) {
        return DeployLayout.linux(name).etc() + "/env";
    }

    /**
     * Whether the text of a unit file is the unit of the 1.x instance {@code name}: it has {@code EnvironmentFile=} naming the
     * environment file of that instance (the value may start with {@code -}, which systemd reads as "optional").
     */
    public boolean isUnitOf1x(String unitText, String name) {
        String environment = environmentFile(name);
        for (String raw : (Iterable<String>) unitText.lines()::iterator) {
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

    /** Stops the service now. */
    public void stop(String service) {
        call("stop", service);
    }

    /** Disables the service: it does not start at boot. */
    public void disable(String service) {
        call("disable", service);
    }

    /** Makes systemd read its units again (after a unit file was moved away). */
    public void reloadUnits() {
        call("daemon-reload");
    }

    private void call(String... arguments) {
        List<String> command = new ArrayList<>();
        command.add(SYSTEMCTL);
        command.addAll(List.of(arguments));
        Result result = control.run(command);
        if (result.exitCode() != 0) {
            throw new FailureException(MigrationError.SERVICE_COMMAND_FAILED, String.join(" ", command),
                    String.valueOf(result.exitCode()));
        }
    }
}
