// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.migrate1x;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * R9-D0093: what belongs to the service manager of the host (the program {@code systemctl}, the directory of the systemd units, the
 * environment file of a Linux instance) is written in the class of the Linux platform ({@code SystemdServices}), which builds the
 * calls from the layout of the installer ({@code DeployLayout}); the command {@code stop-1x} holds the steps of the move and none of
 * these words. The test reads the source of the command (rule ARC-033 keeps the operating system out of the code, and the one place
 * of a platform is a class of its own).
 */
class Stop1xPlatformBoundaryTest {

    private static String source(String file) throws IOException {
        return Files.readString(Path.of("src", "main", "java", "org", "veridger", "migrate1x", file), StandardCharsets.UTF_8);
    }

    private static String code(String text) {
        // what is code: the comments and the strings of the javadoc are not looked at
        return text.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    @Test
    @DisplayName("R9-D0093 Stop1xCommand holds no systemctl call and no Linux path of its own: the calls and the paths are those of SystemdServices, built from DeployLayout")
    void theCommandHoldsNoServiceManagerWords() throws IOException {
        String command = code(source("command/Stop1xCommand.java"));

        assertThat(command).doesNotContain("systemctl").doesNotContain("\"/etc/").doesNotContain("systemd");
    }

    @Test
    @DisplayName("R9-D0093 SystemdServices takes the unit directory and the environment file of an instance from DeployLayout, which is where the installer defines them")
    void theLinuxPlatformUsesTheLayoutOfTheInstaller() throws IOException {
        String platform = code(source("host/SystemdServices.java"));

        assertThat(platform).contains("DeployLayout.UNIT_DIRECTORY").contains("DeployLayout.linux(").doesNotContain("\"/etc/");
    }
}
