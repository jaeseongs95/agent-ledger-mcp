// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.cli.deploy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.UserPrincipal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.veridger.cli.ErrorCode;
import org.veridger.cli.UsageException;
import org.veridger.cli.deploy.DeployLayout.Platform;
import org.veridger.files.WellKnownAccount;

/**
 * Unit tests for {@link LinuxPlatform} covering layout rules, path resolution, option validation, and systemd unit
 * generation.
 */
class LinuxPlatformUnitTest {

    private static final HostAccounts UNUSED_ACCOUNTS = new HostAccounts() {
        @Override
        public UserPrincipal user(FileSystem fileSystem, String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public UserPrincipal wellKnown(FileSystem fileSystem, WellKnownAccount account) {
            throw new UnsupportedOperationException();
        }

        @Override
        public GroupPrincipal group(FileSystem fileSystem, String name) {
            throw new UnsupportedOperationException();
        }
    };

    private final LinuxPlatform linux = new LinuxPlatform(UNUSED_ACCOUNTS, new HostFiles(new DeployFiles()),
            new DeployFiles());

    @Test
    @DisplayName("DEP-LP-01 LinuxPlatform validates layout, hostPath, java option, and renders both systemd unit files")
    void layoutHostPathValidationAndServiceDefinitions(@TempDir Path root) {
        assertThat(linux.platform()).isEqualTo(Platform.LINUX);
        assertThat(linux.hostPath(root, "/etc/veridger")).isEqualTo(root.resolve("etc/veridger"));
        Path absTarget = root.resolve("etc/veridger").toAbsolutePath().normalize();
        assertThat(linux.hostPath(null, absTarget.toString())).isEqualTo(absTarget);

        DeployLayout layout = linux.layout("veridger", null);
        assertThat(layout.platform()).isEqualTo(Platform.LINUX);
        assertThatThrownBy(() -> linux.layout("veridger", "/custom/home"))
                .isInstanceOf(UsageException.class)
                .extracting(e -> ((UsageException) e).code()).isEqualTo(ErrorCode.INVALID_DEPLOY_VALUE);

        DeployParameters valid = new DeployParameters(layout, "/usr/bin/java",
                "jdbc:postgresql://127.0.0.1:5432/ledger", "ledger_app", "ledger", 8864, 8865,
                "mcp.example.com", "admin.example.com");
        assertThat(linux.refusedOptions(valid)).isEmpty();

        List<RenderedFile> units = linux.serviceDefinitions(valid);
        assertThat(units).hasSize(2);
        assertThat(units.get(0).path()).endsWith("veridger.service");
        assertThat(units.get(1).path()).endsWith("veridger-admin.service");

        DeployParameters badJava = new DeployParameters(layout, "relative/java",
                "jdbc:postgresql://127.0.0.1:5432/ledger", "ledger_app", null, 8864, 8865, null, null);
        assertThat(linux.refusedOptions(badJava)).containsExactly(DeployOptions.JAVA);
    }
}
