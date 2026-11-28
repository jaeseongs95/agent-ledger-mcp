// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.cli.deploy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpdateMigrationCatalogTest {
    private static final String FIRST = "CREATE TABLE example (id BIGINT);";
    private static final String SECOND = "CREATE INDEX ix_example ON example (id);";

    @TempDir
    Path directory;

    @Test
    void distinguishesJarOnlyUpdatesFromAnAppendedMigration() throws IOException {
        Path installed = installed();
        Path candidate = candidate(FIRST);
        assertThat(UpdateMigrationCatalog.requiresMigration(installed, candidate)).isFalse();
        candidate(FIRST, SECOND);
        assertThat(UpdateMigrationCatalog.requiresMigration(installed, candidate)).isTrue();
    }

    @Test
    void refusesRewrittenMigrationsAndDifferentServiceCatalogs() throws IOException {
        Path installed = installed();
        Path candidate = candidate("DROP TABLE example;");
        assertThatThrownBy(() -> UpdateMigrationCatalog.requiresMigration(installed, candidate))
                .isInstanceOf(IOException.class).hasMessageContaining("remove or rewrite");

        candidate(FIRST, SECOND);
        jar(candidate.resolve(DeployLayout.ADMIN_JAR), FIRST);
        assertThatThrownBy(() -> UpdateMigrationCatalog.requiresMigration(installed, candidate))
                .isInstanceOf(IOException.class).hasMessageContaining("disagree");
    }

    private Path installed() throws IOException {
        Path path = Files.createDirectory(directory.resolve("installed"));
        jar(path.resolve(DeployLayout.CLI_JAR), FIRST);
        return path;
    }

    private Path candidate(String... migrations) throws IOException {
        Path path = Files.createDirectories(directory.resolve("candidate"));
        for (String name : List.of(DeployLayout.CLI_JAR, DeployLayout.MCP_JAR, DeployLayout.ADMIN_JAR)) {
            jar(path.resolve(name), migrations);
        }
        return path;
    }

    private static void jar(Path target, String... migrations) throws IOException {
        var library = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(library)) {
            for (int i = 0; i < migrations.length; i++) {
                entry(zip, "db/V" + (i + 1) + "__example.sql", migrations[i].getBytes(StandardCharsets.UTF_8));
            }
        }
        try (var zip = new ZipOutputStream(Files.newOutputStream(target))) {
            entry(zip, "BOOT-INF/lib/ledger-database-5.0.0.jar", library.toByteArray());
        }
    }

    private static void entry(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }
}
