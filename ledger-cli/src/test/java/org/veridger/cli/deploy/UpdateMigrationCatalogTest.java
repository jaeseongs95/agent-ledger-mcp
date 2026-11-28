// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.cli.deploy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.veridger.cli.support.PostgresDriverJar;
import org.veridger.cli.support.RequiresOwnerOnlyStorage;

@ExtendWith(OutputCaptureExtension.class)
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

        candidate(FIRST);
        jar(installed.resolve(DeployLayout.CLI_JAR), FIRST, SECOND);
        assertThatThrownBy(() -> UpdateMigrationCatalog.requiresMigration(installed, candidate))
                .isInstanceOf(IOException.class).hasMessageContaining("remove or rewrite");
    }

    @Test
    void refusesMissingMigrationLibrariesAndNonContiguousMigrations() throws IOException {
        Path installed = installed();
        Path archive = installed.resolve(DeployLayout.CLI_JAR);
        Path candidate = candidate(FIRST, SECOND);

        try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            entry(zip, "META-INF/MANIFEST.MF", new byte[0]);
        }
        assertThatThrownBy(() -> UpdateMigrationCatalog.requiresMigration(installed, candidate))
                .isInstanceOf(IOException.class).hasMessageContaining("Expected one ledger-database library");

        jar(archive, Map.of("db/README.md", "Not a migration"));
        assertThatThrownBy(() -> UpdateMigrationCatalog.requiresMigration(installed, candidate))
                .isInstanceOf(IOException.class).hasMessageContaining("contiguous from V1");

        jar(archive, Map.of("db/V1__init.sql", FIRST, "db/V3__example.sql", SECOND));
        assertThatThrownBy(() -> UpdateMigrationCatalog.requiresMigration(installed, candidate))
                .isInstanceOf(IOException.class).hasMessageContaining("contiguous from V1");
    }

    @Test
    void refusesDuplicateAndOversizedMigrations() throws IOException {
        Path installed = installed();
        Path archive = installed.resolve(DeployLayout.CLI_JAR);
        Path candidate = candidate(FIRST, SECOND);

        jar(archive, Map.of("db/V1__init.sql", FIRST, "db/V1__duplicate.sql", SECOND));
        assertThatThrownBy(() -> UpdateMigrationCatalog.requiresMigration(installed, candidate))
                .isInstanceOf(IOException.class).hasMessageContaining("Oversized or duplicate migration");

        jar(archive, Map.of("db/V1__large.sql", " ".repeat(4 * 1024 * 1024 + 1)));
        assertThatThrownBy(() -> UpdateMigrationCatalog.requiresMigration(installed, candidate))
                .isInstanceOf(IOException.class).hasMessageContaining("Oversized or duplicate migration");
    }

    @Test
    void plansJarOnlyUpdatesWithoutCredentialsAndRequiresProvisioningForSchemaChanges(CapturedOutput output)
            throws IOException {
        Path installed = installed();
        Path candidate = candidate(FIRST);
        Path settings = directory.resolve("settings");
        String[] args = {"host-update", "plan", installed.toString(), candidate.toString(), settings.toString()};

        assertThat(HostDatabaseUpdate.run(args)).isZero();
        assertThat(output.getOut()).contains("JARS_ONLY");
        candidate(FIRST, SECOND);
        assertThat(HostDatabaseUpdate.run(args)).isOne();
        assertThat(output.getErr()).contains("Automatic schema migration is not configured");
    }

    @Test
    @RequiresOwnerOnlyStorage
    void plansMigrationOnlyForTheProvisionedDatabase(CapturedOutput output) throws IOException {
        Path installed = installed();
        Path candidate = candidate(FIRST, SECOND);
        Path settings = Files.createDirectory(directory.resolve("settings"));
        String url = "jdbc:postgresql://localhost/test";
        String app = "app_account";
        Files.writeString(settings.resolve("application.yaml"),
                "ledger.jdbc.url: " + url + "\nledger.jdbc.username: " + app + "\n");
        String executable = ProcessHandle.current().info().command().orElseThrow();
        byte[] credential = (UUID.randomUUID() + "\n").getBytes(StandardCharsets.UTF_8);
        String[] provision = {"host-update", "provision", installed.toString(), settings.toString(),
            PostgresDriverJar.path().toString(), executable, "owner_account", url, app, "--stdin"};
        assertThat(HostDatabaseUpdate.run(provision, () -> new ByteArrayInputStream(credential))).isZero();
        Files.createFile(installed.resolve(".schema-updates"));
        String[] plan = {"host-update", "plan", installed.toString(), candidate.toString(), settings.toString()};

        assertThat(HostDatabaseUpdate.run(plan)).isZero();
        assertThat(output.getOut()).contains("MIGRATE");

        Files.writeString(settings.resolve("local.yaml"), "ledger.schema.name: other_schema\n");
        assertThat(HostDatabaseUpdate.run(plan)).isOne();
        assertThat(output.getErr()).contains("Database settings changed");
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
        var contents = new LinkedHashMap<String, String>();
        for (int i = 0; i < migrations.length; i++) {
            contents.put("db/V" + (i + 1) + "__example.sql", migrations[i]);
        }
        jar(target, contents);
    }

    private static void jar(Path target, Map<String, String> contents) throws IOException {
        var library = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(library)) {
            for (var file : contents.entrySet()) {
                entry(zip, file.getKey(), file.getValue().getBytes(StandardCharsets.UTF_8));
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
