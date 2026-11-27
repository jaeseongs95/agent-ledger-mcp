// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.cli.deploy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.UserPrincipalNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.veridger.cli.ErrorCode;
import org.veridger.cli.FailureException;

/**
 * Unit tests for {@link HostFiles} covering view checks, missing-path save, product jar copying, and failure formatting.
 */
class HostFilesUnitTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("HFL-01 requireView, save(missing), copyProduct, and failure formatting cover all host-file helper paths")
    void hostFilesHelpersAndFailureFormatting() throws IOException {
        HostFiles hostFiles = new HostFiles(new DeployFiles());

        // requireView succeeds for basic view and fails with HOST_UNSUPPORTED for nonexistent view
        hostFiles.requireView(FileSystems.getDefault(), "basic");
        assertThatThrownBy(() -> hostFiles.requireView(FileSystems.getDefault(), "nonexistent-view"))
                .isInstanceOfSatisfying(FailureException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.HOST_UNSUPPORTED);
                    assertThat(e.arguments()).containsExactly("nonexistent-view");
                });

        // save(missing) returns Optional.empty()
        assertThat(hostFiles.save(tempDir.resolve("does-not-exist"))).isEmpty();

        // copyProduct copies all three product jars + driver jar
        Path jars = Files.createDirectories(tempDir.resolve("jars"));
        Files.writeString(jars.resolve(DeployLayout.CLI_JAR), "cli");
        Files.writeString(jars.resolve(DeployLayout.MCP_JAR), "mcp");
        Files.writeString(jars.resolve(DeployLayout.ADMIN_JAR), "admin");
        Path driver = tempDir.resolve("postgresql-42.jar");
        Files.writeString(driver, "driver");
        Path lib = tempDir.resolve("out/lib");
        Path drivers = tempDir.resolve("out/drivers");
        hostFiles.copyProduct(jars, driver, lib, drivers);
        assertThat(Files.readString(lib.resolve(DeployLayout.CLI_JAR))).isEqualTo("cli");
        assertThat(Files.readString(lib.resolve(DeployLayout.MCP_JAR))).isEqualTo("mcp");
        assertThat(Files.readString(lib.resolve(DeployLayout.ADMIN_JAR))).isEqualTo("admin");
        assertThat(Files.readString(drivers.resolve("postgresql-42.jar"))).isEqualTo("driver");

        // failure(IOException) formats every branch cleanly without leaking exception message text
        FailureException backupDiffers = HostFiles.failure(
                new DeployFiles.BackupDiffersException(Path.of("a.conf"), Path.of("a.conf.backup")));
        assertThat(backupDiffers.code()).isEqualTo(ErrorCode.HOST_FAILED);
        assertThat(backupDiffers.arguments()).containsExactly("a.conf and a.conf.backup", "BackupDiffersException");

        FailureException fsWithFile = HostFiles.failure(new FileSystemException("/etc/x", null, "secret detail"));
        assertThat(fsWithFile.arguments()).containsExactly("/etc/x", "FileSystemException");

        FailureException fsNoFile = HostFiles.failure(new FileSystemException(null, null, "secret detail"));
        assertThat(fsNoFile.arguments()).containsExactly("-", "FileSystemException");

        FailureException missingUser = HostFiles.failure(new UserPrincipalNotFoundException("veridger-svc"));
        assertThat(missingUser.arguments()).containsExactly("veridger-svc", "UserPrincipalNotFoundException");

        FailureException genericIo = HostFiles.failure(new IOException("internal path detail"));
        assertThat(genericIo.arguments()).containsExactly("-", "IOException");
    }
}
