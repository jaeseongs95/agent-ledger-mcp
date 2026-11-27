// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.database.driver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.veridger.domain.error.InfrastructureCode;
import org.veridger.domain.error.InfrastructureException;
import org.veridger.domain.value.Sha256;

/**
 * Direct unit tests for {@link DriverLoader} covering configuration validation, path safety checks, archive validation,
 * digest verification, isolated class-loader failure branches, and private-copy cleanup.
 */
class DriverLoaderUnitTest {

    private final DriverLoader loader = new DriverLoader();

    private static Path writeZip(Path path, boolean addEntry) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(path))) {
            if (addEntry) {
                zos.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
                zos.write("Manifest-Version: 1.0\n".getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return path;
    }

    @Test
    @DisplayName("DRV-LD-01 load and describe reject unconfigured, incomplete, unsafe-path, bad-archive, and bad-class jars")
    void rejectsInvalidDriverRegistrationsAndArchives(@TempDir Path dir) throws Exception {
        // Unconfigured
        assertThatThrownBy(() -> loader.load(new DriverRegistration(null, null, null, null)))
                .isInstanceOf(InfrastructureException.class)
                .extracting(e -> ((InfrastructureException) e).code())
                .isEqualTo(InfrastructureCode.DRIVER_NOT_CONFIGURED);

        // Partially configured
        assertThatThrownBy(() -> loader.load(new DriverRegistration(dir.resolve("a.jar"), "", null, null)))
                .isInstanceOf(InfrastructureException.class)
                .extracting(e -> ((InfrastructureException) e).code())
                .isEqualTo(InfrastructureCode.DRIVER_INVALID);
        assertThatThrownBy(() -> loader.load(new DriverRegistration(null, "com.example.Driver", null, null)))
                .isInstanceOf(InfrastructureException.class)
                .extracting(e -> ((InfrastructureException) e).code())
                .isEqualTo(InfrastructureCode.DRIVER_INVALID);
        assertThatThrownBy(() -> loader.load(new DriverRegistration(dir.resolve("a.jar"), "com.example.Driver", null, null)))
                .isInstanceOf(InfrastructureException.class)
                .extracting(e -> ((InfrastructureException) e).code())
                .isEqualTo(InfrastructureCode.DRIVER_INVALID);

        // Path checks: relative, non-normalized, placeholder, directory, missing file
        assertThatThrownBy(() -> loader.describe(Path.of("relative.jar"), "com.example.Driver", "jdbc:x:y", Instant.now()))
                .isInstanceOf(InfrastructureException.class);
        assertThatThrownBy(() -> loader.describe(dir.resolve("sub/../a.jar"), "com.example.Driver", "jdbc:x:y", Instant.now()))
                .isInstanceOf(InfrastructureException.class);
        assertThatThrownBy(() -> loader.describe(dir.resolve("${env.JAR}.jar"), "com.example.Driver", "jdbc:x:y", Instant.now()))
                .isInstanceOf(InfrastructureException.class);
        assertThatThrownBy(() -> loader.describe(dir, "com.example.Driver", "jdbc:x:y", Instant.now()))
                .isInstanceOf(InfrastructureException.class);
        assertThatThrownBy(() -> loader.describe(dir.resolve("missing.jar"), "com.example.Driver", "jdbc:x:y", Instant.now()))
                .isInstanceOf(InfrastructureException.class);

        // Digest mismatch
        Path nonZip = Files.writeString(dir.resolve("not-zip.jar"), "plain text", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> loader.load(new DriverRegistration(nonZip, "com.example.Driver",
                "0000000000000000000000000000000000000000000000000000000000000000", Instant.now())))
                .isInstanceOf(InfrastructureException.class);

        // Not a zip archive
        String nonZipSha = Sha256.ofBytes(Files.readAllBytes(nonZip)).hex();
        assertThatThrownBy(() -> loader.load(new DriverRegistration(nonZip, "com.example.Driver", nonZipSha, Instant.now())))
                .isInstanceOf(InfrastructureException.class);

        // Empty zip archive
        Path emptyZip = writeZip(dir.resolve("empty.jar"), false);
        assertThatThrownBy(() -> loader.describe(emptyZip, "com.example.Driver", "jdbc:x:y", Instant.now()))
                .isInstanceOf(InfrastructureException.class);

        // Valid zip, missing class -> ClassNotFoundException
        Path validZip = writeZip(dir.resolve("valid.jar"), true);
        assertThatThrownBy(() -> loader.describe(validZip, "com.example.NoSuchDriver", "jdbc:x:y", Instant.now()))
                .isInstanceOf(InfrastructureException.class);

        // Platform class that does not implement java.sql.Driver
        assertThatThrownBy(() -> loader.describe(validZip, "java.lang.String", "jdbc:x:y", Instant.now()))
                .isInstanceOf(InfrastructureException.class);

        // deleteCopy on missing path is a clean no-op
        Path temp = Files.writeString(dir.resolve("copy.jar"), "x", StandardCharsets.UTF_8);
        DriverLoader.deleteCopy(temp);
        assertThat(temp).doesNotExist();
        DriverLoader.deleteCopy(temp);
    }
}
