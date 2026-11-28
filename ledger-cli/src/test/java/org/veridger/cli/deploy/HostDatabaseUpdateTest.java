// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.cli.deploy;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.veridger.cli.support.PostgresDriverJar;
import org.veridger.cli.support.RequiresOwnerOnlyStorage;
import org.veridger.cli.support.RequiresPosixPermissions;
import org.veridger.database.schema.MigrationFile;
import org.veridger.database.schema.SchemaProperties;
import org.veridger.testing.database.TestDatabase;
import org.veridger.testing.database.TestSchema;
import org.veridger.testing.database.TestSchemaServices;

@ExtendWith(OutputCaptureExtension.class)
@RequiresOwnerOnlyStorage
class HostDatabaseUpdateTest {
    private static final String URL = "jdbc:postgresql://127.0.0.1/ledger";
    private static final String APP = "ledger_app";
    private static final String OWNER = "ledger_owner";
    private static final String MARKER = ".schema-updates";
    private static final String SETTINGS = "application.yaml";
    private static final String LOCAL = "local.yaml";

    @TempDir
    Path temp;
    private Path installed;
    private Path settings;
    private Path privateDirectory;

    @BeforeEach
    void directories() throws IOException {
        installed = Files.createDirectory(temp.resolve("installed"));
        settings = Files.createDirectory(temp.resolve("settings"));
        privateDirectory = installed.resolve("schema-update");
    }

    @Test
    void validatesTheEffectiveSettingsBeforeStoringOwnerCredentials(CapturedOutput output) throws IOException {
        Path dump = dumpProgram("exit 0", "exit /b 0");
        writeSettings(settings.resolve(SETTINGS), URL, APP, "");
        Files.writeString(settings.resolve(LOCAL), "ledger.jdbc.username: overridden_app\n");
        String secret = UUID.randomUUID().toString();

        assertThat(provision(dump, OWNER, URL, APP, secret)).isOne();
        assertThat(output.getErr()).contains("differ from the effective database settings");
        assertThat(run("provision", installed, settings, PostgresDriverJar.path(), dump, OWNER,
                "jdbc:postgresql://127.0.0.1/other", "overridden_app")).isOne();
        var stdinArgs = arguments("provision", installed, settings, PostgresDriverJar.path(), dump, OWNER,
                URL, "overridden_app", "--stdin");
        assertThat(HostDatabaseUpdate.run(stdinArgs, InputStream::nullInputStream)).isOne();
        assertThat(provision(dump, OWNER, URL, "overridden_app", "")).isOne();
        assertThat(output.getErr()).contains("owner password is required");
        assertThat(provision(dump, "OVERRIDDEN_APP", URL, "overridden_app", secret)).isOne();
        assertThat(provision(dump, "bad owner name", URL, "overridden_app", secret)).isOne();
        assertThat(output.getErr()).contains("separate PostgreSQL database owner");
        assertThat(provision(Path.of("pg_dump"), OWNER, URL, "overridden_app", secret)).isOne();
        assertThat(output.getErr()).contains("absolute path");
        assertThat(privateDirectory).doesNotExist();

        Files.createFile(installed.resolve(MARKER));
        assertThat(provision(dump, OWNER, URL, "overridden_app", secret)).isZero();
        assertThat(installed.resolve(MARKER)).doesNotExist();
        assertThat(privateDirectory.resolve("postgresql.jar")).hasSameBinaryContentAs(PostgresDriverJar.path());
        assertThat(privateDirectory.resolve("settings.properties")).content()
                .contains("appAccount=overridden_app", "owner=" + OWNER, "driverHash=").doesNotContain(secret);
        assertThat(Files.readString(privateDirectory.resolve("owner-password")).equals(secret + "\n")).isTrue();
        assertThat(output.getAll().contains(secret)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"jdbc:h2:mem:test", "jdbc:postgresql://bad host/db", "jdbc:postgresql:///db",
        "jdbc:postgresql://user@localhost/db", "jdbc:postgresql://a,b/db", "jdbc:postgresql://localhost/db#fragment",
        "jdbc:postgresql://localhost/", "jdbc:postgresql://localhost:0/db", "jdbc:postgresql://localhost:65536/db",
        "jdbc:postgresql://localhost/db?unrecognized=invalid"})
    void refusesEndpointsThatCannotBeBackedUpSafely(String url, CapturedOutput output) throws IOException {
        writeSettings(settings.resolve(SETTINGS), url, APP, "");
        assertThat(provision(temp.resolve("unneeded"), OWNER, url, APP, "")).isOne();
        assertThat(output.getErr()).contains("single-host PostgreSQL JDBC URL");
        assertThat(privateDirectory).doesNotExist();
    }

    @Test
    void refusesUnsafeSettingsFilesAndInvalidDatabaseIdentifiers(CapturedOutput output) throws IOException {
        Path file = settings.resolve(SETTINGS);
        Path dump = temp.resolve("unneeded");
        assertThat(provision(dump, OWNER, URL, APP, "")).isOne();
        assertThat(output.getErr()).contains("single-host PostgreSQL JDBC URL");

        Files.createDirectory(file);
        assertThat(provision(dump, OWNER, URL, APP, "")).isOne();
        assertThat(output.getErr()).contains("unsafe database settings file");

        Files.delete(file);
        Files.writeString(file, " ".repeat(1024 * 1024 + 1));
        assertThat(provision(dump, OWNER, URL, APP, "")).isOne();
        assertThat(output.getErr()).contains("unsafe database settings file");

        Files.writeString(file, "ledger.jdbc.username: ledger_app\n---\nledger.jdbc.username: other_app\n");
        assertThat(provision(dump, OWNER, URL, APP, "")).isOne();
        assertThat(output.getErr()).contains("one document per YAML settings file");

        writeSettings(file, URL, "not an account", "");
        assertThat(provision(dump, OWNER, URL, "not an account", "")).isOne();
        assertThat(output.getErr()).contains("Invalid database application account or schema");

        writeSettings(file, URL, APP, "Invalid-Schema");
        assertThat(provision(dump, OWNER, URL, APP, "")).isOne();
        assertThat(output.getErr()).contains("Invalid database application account or schema");
        assertThat(privateDirectory).doesNotExist();
    }

    @Test
    @RequiresPosixPermissions
    void refusesToStoreSecretsInALinkOrAnAccessibleDirectory(CapturedOutput output) throws IOException {
        writeSettings(settings.resolve(SETTINGS), URL, APP, "");
        Path dump = dumpProgram("exit 0", "exit /b 0");
        Path other = Files.createDirectory(temp.resolve("other"));
        Files.createSymbolicLink(privateDirectory, other);
        String secret = UUID.randomUUID().toString();

        assertThat(provision(dump, OWNER, URL, APP, secret)).isOne();
        assertThat(output.getErr()).contains("Refused a link");
        assertThat(other).isEmptyDirectory();

        Files.delete(privateDirectory);
        Files.createDirectory(privateDirectory, PosixFilePermissions.asFileAttribute(
                PosixFilePermissions.fromString("rwxr-xr-x")));
        assertThat(provision(dump, OWNER, URL, APP, secret)).isOne();
        assertThat(output.getErr()).contains("mode 0700");
        assertThat(privateDirectory).isEmptyDirectory();
    }

    @Test
    void writesTheBackupUsingAnEscapedTemporaryPassfile(CapturedOutput output) throws IOException {
        String url = "jdbc:postgresql://[::1]:5440/ledger?sslmode=require";
        writeSettings(settings.resolve(SETTINGS), url, APP, "");
        Path dump = dumpProgram("""
                printf '%s\n' "$*" "$PGSSLMODE" "$PGCONNECT_TIMEOUT"
                cat "$PGPASSFILE"
                printf 'custom archive fixture' > "${3#--file=}"
                """, """
                @echo %*
                @echo %PGSSLMODE%
                @echo %PGCONNECT_TIMEOUT%
                @type "%PGPASSFILE%"
                @echo custom archive fixture>"%~dp0backups\\database.dump"
                @exit /b 0
                """);
        String secret = "fixture:" + UUID.randomUUID() + "\\end";
        assertThat(provision(dump, OWNER, url, APP, secret)).isZero();
        Files.createFile(installed.resolve(MARKER));
        Path backup = temp.resolve("backups");

        assertThat(run("backup", installed, backup)).isZero();
        assertThat(backup.resolve("database.dump")).content().contains("custom archive fixture");
        var invocation = Files.readAllLines(backup.resolve("backup.log"));
        assertThat(invocation.getFirst()).contains("--no-password", "--format=custom",
                "--file=" + backup.resolve("database.dump"), "--host=::1", "--port=5440",
                "--username=" + OWNER, "--dbname=ledger");
        assertThat(invocation.subList(1, invocation.size())).containsExactly("require", "10",
                "\\:\\:1:5440:ledger:" + OWNER + ":" + secret.replace("\\", "\\\\").replace(":", "\\:"));
        assertThat(output.getAll().contains(secret)).isFalse();
        assertNoTemporaryPassfiles();
    }

    @Test
    void removesThePassfileWhenBackupFailsOrProducesAnEmptyArchive(CapturedOutput output) throws IOException {
        writeSettings(settings.resolve(SETTINGS), URL, APP, "");
        Path dump = dumpProgram("exit 4", "exit /b 4");
        String secret = UUID.randomUUID().toString();
        assertThat(provision(dump, OWNER, URL, APP, secret)).isZero();
        Files.createFile(installed.resolve(MARKER));
        Path failed = temp.resolve("failed");
        assertThat(run("backup", installed, failed)).isOne();
        assertThat(output.getErr()).contains("PostgreSQL backup failed");
        assertThat(failed.resolve("database.dump")).doesNotExist();
        assertNoTemporaryPassfiles();

        dumpProgram(": > \"${3#--file=}\"", "@type nul > \"%~dp0empty\\database.dump\"\n@exit /b 0");
        Path empty = temp.resolve("empty");
        assertThat(run("backup", installed, empty)).isOne();
        assertThat(output.getErr()).contains("PostgreSQL backup is empty");
        assertNoTemporaryPassfiles();

        Files.delete(privateDirectory.resolve("owner-password"));
        assertThat(run("backup", installed, temp.resolve("missing-secret"))).isOne();
        assertThat(output.getErr()).contains("credentials are incomplete");
        assertThat(output.getAll().contains(secret)).isFalse();
    }

    @Test
    void startsTheCandidateToMigrateExistingVersionLinksAndPropagatesChildFailure(CapturedOutput output)
            throws IOException {
        var database = TestDatabase.shared();
        var db = database.settings();
        var ownerPassword = db.ownerPassword();
        assumeTrue(ownerPassword.isPresent(), "The candidate migration reads a test database password from stdin");
        String secret = ownerPassword.orElseThrow();
        try (var schema = database.newSchema()) {
            var jdbc = JdbcClient.create(schema.dataSource());
            schema.allowUsage(db.appUser());
            installLegacySchema(schema, jdbc);
            writeSettings(settings.resolve(SETTINGS), db.url(), db.appUser(), schema.name());
            Path dump = dumpProgram("exit 0", "exit /b 0");
            assertThat(provision(dump, db.user(), db.url(), db.appUser(), secret)).isZero();
            Files.createFile(installed.resolve(MARKER));
            Path candidate = Files.createDirectory(temp.resolve("candidate"));
            Path backup = Files.createDirectory(temp.resolve("backup"));

            assertThat(run("migrate", installed, candidate, backup)).isOne();
            assertThat(output.getErr()).contains("Database migration failed");
            assertThat(backup.resolve("migration.log")).content().contains("Unable to access jarfile");

            Path jar = Path.of(Objects.requireNonNull(System.getProperty("ledger.test.cli-jar")));
            Files.copy(jar, candidate.resolve(DeployLayout.CLI_JAR));
            int result = run("migrate", installed, candidate, backup);
            assertThat(result).as("candidate migration exit code (see the migration log)").isZero();
            assertThat(jdbc.sql("SELECT outcome FROM schema_history WHERE script = :script")
                    .param("script", "V16__project_version_items.sql").query(String.class).single()).isEqualTo("SUCCEEDED");
            assertThat(jdbc.sql("SELECT project_id, version_id, item_kind, item_id FROM project_version_item")
                    .query().singleRow()).containsEntry("project_id", "legacy").containsEntry("version_id", "version-1")
                    .containsEntry("item_kind", "issue").containsEntry("item_id", "LEGACY-I-0001");
            assertThat(output.getAll().contains(secret)).isFalse();
        }
    }

    private void installLegacySchema(TestSchema schema, JdbcClient jdbc) throws IOException {
        Path migrations = Files.createDirectory(temp.resolve("baseline"));
        var sources = MigrationFile.load(new PathMatchingResourcePatternResolver(), SchemaProperties.DEFAULT_LOCATION);
        for (var source : sources) {
            if (source.version() <= 15) {
                Files.writeString(migrations.resolve(source.fileName()), source.text());
            }
        }
        TestSchemaServices.migrator(schema.dataSource(), new SchemaProperties(migrations.toUri().toString(), true, null),
                Clock.systemUTC()).migrate();
        jdbc.sql("INSERT INTO issue_project_version (project_id, version_id, issue_id) VALUES "
                + "('legacy', 'version-1', 'LEGACY-I-0001'), ('legacy', 'version-1', 'LEGACY-I-0001')").update();
    }

    @Test
    void refusesUnknownOperationsAndExtraArguments(CapturedOutput output) {
        assertThat(HostDatabaseUpdate.run(new String[] {"host-update"})).isOne();
        assertThat(run("unknown")).isOne();
        assertThat(output.getErr()).contains("Unknown host-update operation");
        assertThat(run("provision", installed, settings, temp, temp, OWNER, URL, APP, "--unknown")).isOne();
        assertThat(output.getErr()).contains("Invalid host-update provision arguments");
        assertThat(run("migrate", installed)).isOne();
        assertThat(output.getErr()).contains("Invalid host-update arguments");
    }

    private void assertNoTemporaryPassfiles() throws IOException {
        try (var files = Files.list(privateDirectory)) {
            assertThat(files.map(path -> path.getFileName().toString()).toList())
                    .containsExactlyInAnyOrder("postgresql.jar", "owner-password", "settings.properties");
        }
    }

    private Path dumpProgram(String shell, String batch) throws IOException {
        if (temp.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Path program = Files.writeString(temp.resolve("pg_dump"), "#!/bin/sh\nset -eu\n" + shell + "\n");
            return Files.setPosixFilePermissions(program, PosixFilePermissions.fromString("rwx------"));
        }
        return Files.writeString(temp.resolve("pg_dump.cmd"), "@echo off\r\n" + batch.replace("\n", "\r\n") + "\r\n");
    }

    private int provision(Path dump, String owner, String url, String app, String secret) {
        var args = arguments("provision", installed, settings, PostgresDriverJar.path(), dump, owner, url, app, "--stdin");
        return HostDatabaseUpdate.run(args, () -> new ByteArrayInputStream((secret + "\n").getBytes(UTF_8)));
    }

    private static int run(String operation, Object... args) {
        return HostDatabaseUpdate.run(arguments(operation, args));
    }

    private static String[] arguments(String operation, Object... args) {
        return Stream.concat(Stream.of("host-update", operation), Arrays.stream(args).map(Object::toString))
                .toArray(String[]::new);
    }

    private static void writeSettings(Path path, String url, String app, String schema) throws IOException {
        Files.writeString(path, """
                ledger.jdbc.url: '%s'
                ledger.jdbc.username: '%s'
                ledger.schema.name: '%s'
                """.formatted(url, app, schema));
    }
}
