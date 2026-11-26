// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.cli.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.veridger.files.FileKind;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * R9-D0089: the installation that the command line tool works for is found where the installer of the host puts it, without an
 * option. The tool reads its settings below {@code ledger.secrets.home}; its default was the Linux directory {@code /etc/<name>} on
 * every host, so on Windows (where {@code deploy/install.cmd} puts the installation below {@code C:/ProgramData/<name>}) a command
 * given as the README gives it, {@code java -jar ledger-cli.jar project-add ...}, read no settings and no secret.
 */
class HostDefaultsTest {

    private static final String INSTANCE = "r9d0089";

    @Configuration(proxyBeanMethods = false)
    static class Empty {
    }

    /** The layout of the installer of the host this test runs on: decided here by the attribute views, the way the product decides. */
    private static DeployLayout installerLayout() {
        boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
        return posix ? DeployLayout.linux(INSTANCE) : DeployLayout.windows(INSTANCE, "C:/ProgramData/" + INSTANCE);
    }

    private static ConfigurableEnvironment environment(String... arguments) {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(Empty.class)
                .web(WebApplicationType.NONE).run(arguments)) {
            return context.getEnvironment();
        }
    }

    @Test
    @DisplayName("R9-D0089 without an option the settings directory of the tool is the one of the installer of this host: /etc/<name> on Linux, C:/ProgramData/<name>/etc on Windows; the secret directory, the driver registration and the driver directory follow it")
    void theDefaultHomeIsThatOfTheHost() {
        String etc = installerLayout().etc();

        ConfigurableEnvironment environment = environment("--ledger.secrets.instance=" + INSTANCE);

        assertThat(environment.getProperty("ledger.secrets.home")).isEqualTo(etc);
        assertThat(environment.getProperty("ledger.secrets.directory")).isEqualTo(etc + "/secrets");
        assertThat(environment.getProperty("ledger.cli.driver.file")).isEqualTo(etc + "/jdbc-driver.yaml");
        assertThat(environment.getProperty("ledger.cli.driver.directory")).isEqualTo(etc + "/drivers");
        assertThat(environment.getProperty("spring.config.import")).as("the import reads the files of the same directory")
                .contains("optional:file:" + etc + "/application.yaml", "optional:configtree:" + etc + "/secrets/common/",
                        "optional:configtree:" + etc + "/secrets/mcp/");
    }

    @Test
    @DisplayName("R9-D0089 both kinds of host, on any host: a POSIX file system gives /etc/<name> and an ACL one C:/ProgramData/<name>/etc, the very paths of DeployLayout (where the installers put the installation)")
    void bothKindsOfHostFollowTheLayoutOfTheInstaller() {
        for (FileKind kind : List.of(FileKind.POSIX, FileKind.ACL, FileKind.OTHER)) {
            Map<String, Object> placeholders = HostDefaults.settingsPlaceholders(kind);
            String home = placeholders.get(HostDefaults.SETTINGS_PREFIX) + "name" + placeholders.get(HostDefaults.SETTINGS_SUFFIX);

            assertThat(home).as(kind.name()).isEqualTo(kind == FileKind.ACL ? DeployLayout.windows("name").etc()
                    : DeployLayout.linux("name").etc());
        }
        assertThat(HostDefaults.settingsPlaceholders(FileKind.POSIX)).containsEntry(HostDefaults.SETTINGS_PREFIX, "/etc/")
                .containsEntry(HostDefaults.SETTINGS_SUFFIX, "");
        assertThat(HostDefaults.settingsPlaceholders(FileKind.ACL)).containsEntry(HostDefaults.SETTINGS_PREFIX, "C:/ProgramData/")
                .containsEntry(HostDefaults.SETTINGS_SUFFIX, "/etc");
    }

    @Test
    @DisplayName("R9-D0089 install.cmd puts the home of an instance where DeployLayout says (C:/ProgramData/<name>), so the tool finds it without an option")
    void theInstallerOfWindowsUsesTheHomeRootOfTheLayout() throws java.io.IOException {
        Path root = Path.of(Objects.requireNonNull(System.getProperty("ledger.docs.root"), "set by the test task of ledger-cli"));
        String installer = Files.readString(root.resolve("deploy/install.cmd"), StandardCharsets.UTF_8);

        assertThat(installer).contains("set \"HOME=" + DeployLayout.WINDOWS_HOME_ROOT + "/%NAME%\"")
                .contains("set \"WHOME=" + DeployLayout.WINDOWS_HOME_ROOT.replace('/', '\\') + "\\%NAME%\"");
    }

    @Test
    @DisplayName("R9-D0089 a settings directory given on the command line wins over the default of the host")
    void anOptionWins() {
        ConfigurableEnvironment environment = environment("--ledger.secrets.instance=" + INSTANCE,
                "--ledger.secrets.home=/given/place");

        assertThat(environment.getProperty("ledger.secrets.home")).isEqualTo("/given/place");
        assertThat(environment.getProperty("ledger.secrets.directory")).isEqualTo("/given/place/secrets");
    }
}
