// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.cli.deploy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.CharBuffer;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.HashMap;
import java.util.Objects;
import java.util.Properties;
import java.util.regex.Pattern;
import org.veridger.cli.StreamCliOutput;
import org.veridger.cli.secret.ConsoleSecretInput;
import org.veridger.cli.secret.StandardInput;
import org.veridger.cli.secret.StdinSecretInput;
import org.veridger.cli.secret.SystemProcessUser;
import org.veridger.database.schema.GrantApplier;
import org.veridger.files.WellKnownAccount;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.FileSystemResource;

/** Internal commands used by the privileged host installers and updaters. The web service cannot read the migration files. */
public final class HostDatabaseUpdate {
    private static final String POSTGRES_DRIVER = "org.postgresql.Driver";
    private static final String MARKER = ".schema-updates";
    private static final String PRIVATE_DIR = "schema-update";
    private static final String SECRET = "owner-password";
    private static final Pattern DATABASE_NAME = Pattern.compile("/[A-Za-z0-9_.-]+");
    private static final Pattern SSL_MODE = Pattern.compile("sslmode=(disable|allow|prefer|require|verify-ca|verify-full)");
    private static final String INVALID_ENDPOINT = "Schema updates require a single-host PostgreSQL JDBC URL with an explicit database";

    private enum Operation {
        PROVISION, PLAN, BACKUP, MIGRATE;

        static Operation read(String[] args) {
            for (Operation operation : values()) {
                if (args.length > 1 && Objects.equals(operation.name().toLowerCase(java.util.Locale.ROOT), args[1])) {
                    return operation;
                }
            }
            throw new IllegalArgumentException("Unknown host-update operation");
        }
    }

    private record Database(String url, String appAccount, String schema) {
        private Database {
            endpoint(url);
            if (!GrantApplier.isAccountName(appAccount) || (!schema.isEmpty() && !schema.matches("[a-z][a-z0-9_]{0,29}"))) {
                throw new IllegalArgumentException("Invalid database application account or schema");
            }
        }
    }

    private record Configuration(Path directory, Database database, Path pgDump, String owner, String driverHash) {
        Path secret() {
            return directory.resolve(SECRET);
        }
    }

    private HostDatabaseUpdate() {
    }

    public static int run(String[] args) {
        try {
            switch (Operation.read(args)) {
                case PROVISION -> {
                    if (args.length != 9 && !(args.length == 10 && "--stdin".equals(args[9]))) {
                        throw new IllegalArgumentException("Invalid host-update provision arguments");
                    }
                    provision(Path.of(args[2]), Path.of(args[3]), Path.of(args[4]), Path.of(args[5]),
                            args[6], args[7], args[8], args.length == 10);
                }
                case PLAN -> {
                    requireLength(args, 5);
                    Path lib = Path.of(args[2]);
                    boolean change = UpdateMigrationCatalog.requiresMigration(lib, Path.of(args[3]));
                    if (change) {
                        Configuration configuration = load(lib);
                        requireDatabaseMatch(configuration.database(), readDatabase(Path.of(args[4])));
                    }
                    StreamCliOutput.hostUpdatePlan(change ? "MIGRATE" : "JARS_ONLY");
                }
                case BACKUP -> {
                    requireLength(args, 4);
                    backup(load(Path.of(args[2])), Path.of(args[3]));
                }
                case MIGRATE -> {
                    requireLength(args, 5);
                    migrate(load(Path.of(args[2])), Path.of(args[3]), Path.of(args[4]));
                }
            }
            return 0;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return 1;
        } catch (IOException | IllegalArgumentException | org.veridger.cli.FailureException failure) {
            StreamCliOutput.hostUpdateFailure("host-update: " + failure.getMessage());
            return 1;
        }
    }

    private static void requireLength(String[] args, int count) {
        if (args.length != count) {
            throw new IllegalArgumentException("Invalid host-update arguments");
        }
    }

    private static void provision(Path lib, Path etc, Path driver, Path pgDump, String owner, String expectedUrl,
            String expectedApp, boolean stdin) throws IOException {
        Database database = readDatabase(etc);
        if (!Objects.equals(expectedUrl, database.url()) || !Objects.equals(expectedApp, database.appAccount())) {
            throw new IOException("Installer JDBC URL and application account differ from the effective database settings");
        }
        requirePgDump(pgDump);
        if (!GrantApplier.isAccountName(owner) || owner.equalsIgnoreCase(database.appAccount())) {
            throw new IllegalArgumentException("Use a separate PostgreSQL database owner account for schema updates");
        }
        var input = stdin ? new StdinSecretInput(StandardInput.ofProcess()) : new ConsoleSecretInput();
        char[] secret = input.read("PostgreSQL owner password (stored for host schema updates): ");
        if (secret == null || secret.length == 0) {
            throw new IllegalArgumentException("A PostgreSQL owner password is required");
        }
        try {
            Path directory = protect(lib.resolve(PRIVATE_DIR));
            Files.deleteIfExists(lib.resolve(MARKER));
            Path pinnedDriver = directory.resolve("postgresql.jar");
            replacePrivateFile(pinnedDriver, out -> {
                try (var source = Files.newInputStream(driver)) {
                    source.transferTo(out);
                }
            });
            var properties = new Properties();
            properties.setProperty("url", database.url());
            properties.setProperty("appAccount", database.appAccount());
            properties.setProperty("schema", database.schema());
            properties.setProperty("owner", owner);
            properties.setProperty("pgDump", pgDump.toString());
            properties.setProperty("driverHash", HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(pinnedDriver))));
            replacePrivateFile(directory.resolve("settings.properties"), out -> properties.store(out, null));
            replacePrivateFile(directory.resolve(SECRET), out -> {
                var writer = new java.io.OutputStreamWriter(out, java.nio.charset.StandardCharsets.UTF_8);
                writer.append(CharBuffer.wrap(secret)).append('\n');
                writer.flush();
            });
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        } finally {
            Arrays.fill(secret, '\0');
        }
    }

    @FunctionalInterface
    private interface FileContent {
        void write(OutputStream output) throws IOException;
    }

    private static Path privateTempFile(Path directory) throws IOException {
        if (hasPosixPermissions(directory)) {
            return Files.createTempFile(directory, "private-", ".tmp",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        }
        return Files.createTempFile(directory, "private-", ".tmp");
    }

    private static void replacePrivateFile(Path target, FileContent content) throws IOException {
        Path temporary = privateTempFile(target.getParent());
        try {
            try (var output = Files.newOutputStream(temporary)) {
                content.write(output);
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Configuration load(Path lib) throws IOException {
        if (!Files.isRegularFile(lib.resolve(MARKER), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Automatic schema migration is not configured on the host");
        }
        Path directory = lib.resolve(PRIVATE_DIR);
        var properties = new Properties();
        try (var file = Files.newInputStream(directory.resolve("settings.properties"))) {
            properties.load(file);
        }
        Database database = new Database(properties.getProperty("url"), properties.getProperty("appAccount"),
                properties.getProperty("schema", ""));
        Path pgDump = Path.of(properties.getProperty("pgDump", ""));
        requirePgDump(pgDump);
        String owner = properties.getProperty("owner");
        if (!GrantApplier.isAccountName(owner) || owner.equalsIgnoreCase(database.appAccount())
                || !Files.isRegularFile(directory.resolve(SECRET), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Host schema migration credentials are incomplete");
        }
        return new Configuration(directory, database, pgDump, owner, properties.getProperty("driverHash"));
    }

    private static void requirePgDump(Path pgDump) throws IOException {
        if (!pgDump.isAbsolute() || !Files.isRegularFile(pgDump) || !Files.isExecutable(pgDump)) {
            throw new IOException("Supply the absolute path to an executable PostgreSQL pg_dump");
        }
    }

    private static Path protect(Path directory) throws IOException {
        if (Files.isSymbolicLink(directory)) {
            throw new IOException("Refused a link at the private update directory");
        }
        if (hasPosixPermissions(directory)) {
            var mode = PosixFilePermissions.fromString("rwx------");
            if (!Files.exists(directory)) {
                Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(mode));
            }
            if (!Objects.equals(mode, Files.getPosixFilePermissions(directory, LinkOption.NOFOLLOW_LINKS))) {
                throw new IOException("Private update directory must have mode 0700");
            }
        } else {
            if (!Files.exists(directory)) {
                Files.createDirectory(directory);
            }
            var accounts = new SystemHostAccounts();
            var fs = directory.getFileSystem();
            var system = accounts.wellKnown(fs, WellKnownAccount.SYSTEM);
            var admins = accounts.wellKnown(fs, WellKnownAccount.ADMINISTRATORS);
            var process = new SystemProcessUser().current(fs);
            new WindowsAccess().set(directory, WindowsAccess.closedWith(system, admins, process, WindowsAccess.FULL));
        }
        return directory;
    }

    private static boolean hasPosixPermissions(Path path) {
        return Files.getFileAttributeView(path, PosixFileAttributeView.class) != null;
    }

    private static Database readDatabase(Path settings) throws IOException {
        var loader = new YamlPropertySourceLoader();
        var properties = new Properties();
        var keys = List.of("ledger.jdbc.url", "ledger.jdbc.username", "ledger.schema.name");
        for (String name : List.of("application.yaml", "local.yaml")) {
            Path file = settings.resolve(name);
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 1024 * 1024) {
                throw new IOException("Refused unsafe database settings file");
            }
            var documents = loader.load(name, new FileSystemResource(file));
            if (documents.size() > 1) {
                throw new IOException("Use one document per YAML settings file for automatic schema updates");
            }
            for (var document : documents) {
                for (String key : keys) {
                    Object value = document.getProperty(key);
                    if (value != null) {
                        properties.setProperty(key, value.toString());
                    }
                }
            }
        }
        return new Database(properties.getProperty(keys.get(0)), properties.getProperty(keys.get(1)),
                properties.getProperty(keys.get(2), ""));
    }

    private static void requireDatabaseMatch(Database saved, Database current) throws IOException {
        if (!Objects.equals(saved, current)) {
            throw new IOException("Database settings changed; provision host schema updates again");
        }
    }

    private static URI endpoint(String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:postgresql://")) {
            throw new IllegalArgumentException(INVALID_ENDPOINT);
        }
        URI uri;
        try {
            uri = new URI(jdbcUrl.substring("jdbc:".length()));
        } catch (java.net.URISyntaxException malformed) {
            throw new IllegalArgumentException(INVALID_ENDPOINT);
        }
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
                || !DATABASE_NAME.matcher(uri.getRawPath()).matches() || uri.getPort() == 0 || uri.getPort() > 65535
                || (uri.getRawQuery() != null && !SSL_MODE.matcher(uri.getRawQuery()).matches())) {
            throw new IllegalArgumentException(INVALID_ENDPOINT);
        }
        return uri;
    }

    private static void backup(Configuration config, Path backup) throws IOException, InterruptedException {
        protect(backup);
        URI db = endpoint(config.database().url());
        String host = db.getHost().replace("[", "").replace("]", "");
        String port = db.getPort() == -1 ? "5432" : Integer.toString(db.getPort());
        String name = db.getPath().substring(1);
        Path passFile = privateTempFile(config.directory());
        Path dump = backup.resolve("database.dump");
        try {
            String secret = Files.readString(config.secret());
            String line = List.of(host, port, name, config.owner(), secret.substring(0, secret.length() - 1))
                    .stream().map(HostDatabaseUpdate::escapePgpass).collect(java.util.stream.Collectors.joining(":"));
            try (var writer = Files.newBufferedWriter(passFile)) {
                writer.append(line).append('\n');
            }
            var arguments = List.of(config.pgDump().toString(), "--no-password", "--format=custom", "--file=" + dump,
                    "--host=" + host, "--port=" + port, "--username=" + config.owner(), "--dbname=" + name);
            var environment = new HashMap<String, String>();
            environment.put("PGPASSFILE", passFile.toString());
            environment.put("PGCONNECT_TIMEOUT", "10");
            if (db.getRawQuery() != null) {
                environment.put("PGSSLMODE", db.getRawQuery().substring("sslmode=".length()));
            }
            HostUpdateProcess.backup(arguments, environment, backup.resolve("backup.log"));
            if (Files.size(dump) == 0) {
                throw new IOException("PostgreSQL backup is empty");
            }
        } finally {
            Files.deleteIfExists(passFile);
        }
    }

    private static String escapePgpass(String text) {
        return text.replace("\\", "\\\\").replace(":", "\\:");
    }

    private static void migrate(Configuration config, Path candidate, Path backup) throws IOException, InterruptedException {
        Path bin = Path.of(System.getProperty("java.home"), "bin");
        Path java = Files.isRegularFile(bin.resolve("java")) ? bin.resolve("java") : bin.resolve("java.exe");
        var arguments = new ArrayList<>(List.of(java.toString(), JvmPreset.OPTIONS, "-jar",
                candidate.resolve(DeployLayout.CLI_JAR).toString(), "--spring.config.location=classpath:/application.properties",
                "--ledger.secrets.home=" + config.directory().toString().replace('\\', '/'),
                "--ledger.jdbc.url=" + config.database().url(),
                "--ledger.jdbc.username=" + config.database().appAccount(),
                "--ledger.jdbc.pool-size=2", "--ledger.jdbc.driver.jar=" + config.directory().resolve("postgresql.jar"),
                "--ledger.jdbc.driver.class-name=" + POSTGRES_DRIVER,
                "--ledger.jdbc.driver.sha256=" + Objects.requireNonNull(config.driverHash()), "migrate",
                "--owner-account=" + config.owner(), "--app-account=" + config.database().appAccount(), "--stdin"));
        if (!config.database().schema().isEmpty()) {
            arguments.add("--ledger.schema.name=" + config.database().schema());
        }
        HostUpdateProcess.migrate(arguments, config.directory(), config.secret(), backup.resolve("migration.log"));
    }
}
