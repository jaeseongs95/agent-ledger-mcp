// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.cli.deploy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
 * Unit tests for {@link HostFiles} covering product jar staging, file system view checks, absent-directory save, and
 * structured {@link FailureException} mapping.
 */
class HostFilesUnitTest {

    @Test
    @DisplayName("DEP-HF-01 copyProduct copies all three Veridger jars and JDBC driver jar into lib and drivers")
    void copyProductStagesAllJarsAndDriver(@TempDir Path dir) throws IOException {
        Path jars = dir.resolve("jars");
        Path lib = dir.resolve("lib");
        Path drivers = dir.resolve("drivers");
        Files.createDirectories(jars);
        Files.createDirectories(lib);
        Files.createDirectories(drivers);
        Files.writeString(jars.resolve(DeployLayout.CLI_JAR), "cli", StandardCharsets.UTF_8);
        Files.writeString(jars.resolve(DeployLayout.MCP_JAR), "mcp", StandardCharsets.UTF_8);
        Files.writeString(jars.resolve(DeployLayout.ADMIN_JAR), "admin", StandardCharsets.UTF_8);
        Path driver = dir.resolve("postgresql-test.jar");
        Files.writeString(driver, "driver", StandardCharsets.UTF_8);

        HostFiles hostFiles = new HostFiles(new DeployFiles());
        hostFiles.copyProduct(jars, driver, lib, drivers);

        assertThat(lib.resolve(DeployLayout.CLI_JAR)).hasContent("cli");
        assertThat(lib.resolve(DeployLayout.MCP_JAR)).hasContent("mcp");
        assertThat(lib.resolve(DeployLayout.ADMIN_JAR)).hasContent("admin");
        assertThat(drivers.resolve("postgresql-test.jar")).hasContent("driver");
        assertThat(hostFiles.save(dir.resolve("does-not-exist"))).isEmpty();
    }

    @Test
    @DisplayName("DEP-HF-02 requireView and failure map unsupported views and IOException subclasses cleanly")
    void requireViewAndFailureMapping() {
        HostFiles hostFiles = new HostFiles(new DeployFiles());
        hostFiles.requireView(FileSystems.getDefault(), "basic");
        assertThatThrownBy(() -> hostFiles.requireView(FileSystems.getDefault(), "nonexistent-view"))
                .isInstanceOf(FailureException.class)
                .extracting(e -> ((FailureException) e).code()).isEqualTo(ErrorCode.HOST_UNSUPPORTED);

        FailureException fsFail = HostFiles.failure(new FileSystemException("/opt/veridger", null, "denied"));
        assertThat(fsFail.code()).isEqualTo(ErrorCode.HOST_FAILED);

        FailureException userFail = HostFiles.failure(new UserPrincipalNotFoundException("veridger-svc"));
        assertThat(userFail.code()).isEqualTo(ErrorCode.HOST_FAILED);

        FailureException genericFail = HostFiles.failure(new IOException("disk error"));
        assertThat(genericFail.code()).isEqualTo(ErrorCode.HOST_FAILED);
    }
}
