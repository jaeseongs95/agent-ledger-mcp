// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.cli.deploy;

import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/** Compares the migration files shipped in the installed and verified candidate applications. */
final class UpdateMigrationCatalog {
    private static final Pattern DATABASE_JAR = Pattern.compile("BOOT-INF/lib/ledger-database(?:-[^/]+)?\\.jar");
    private static final Pattern SQL_FILE = Pattern.compile("db/V([1-9][0-9]*)__[a-z0-9_]+\\.sql");
    private static final int MAX_SQL_BYTES = 4 * 1024 * 1024;

    private record Migration(String name, String digest) {
    }

    private UpdateMigrationCatalog() {
    }

    static boolean requiresMigration(Path installed, Path candidate) throws IOException {
        List<Migration> current = migrations(installed.resolve(DeployLayout.CLI_JAR));
        List<Migration> target = migrations(candidate.resolve(DeployLayout.CLI_JAR));
        if (!Objects.equals(target, migrations(candidate.resolve(DeployLayout.MCP_JAR)))
                || !Objects.equals(target, migrations(candidate.resolve(DeployLayout.ADMIN_JAR)))) {
            throw new IOException("Update JARs disagree about the schema");
        }
        if (target.size() < current.size() || !Objects.equals(target.subList(0, current.size()), current)) {
            throw new IOException("Update would remove or rewrite previously shipped migrations");
        }
        return target.size() > current.size();
    }

    private static List<Migration> migrations(Path jar) throws IOException {
        try (ZipFile archive = new ZipFile(jar.toFile())) {
            var libraries = archive.stream().filter(entry -> DATABASE_JAR.matcher(entry.getName()).matches()).toList();
            if (libraries.size() != 1) {
                throw new IOException("Expected one ledger-database library in " + jar.getFileName());
            }
            try (ZipInputStream database = new ZipInputStream(archive.getInputStream(libraries.getFirst()))) {
                return readSql(database);
            }
        }
    }

    private static List<Migration> readSql(ZipInputStream database) throws IOException {
        var files = new TreeMap<Integer, Migration>();
        for (var entry = database.getNextEntry(); entry != null; entry = database.getNextEntry()) {
            var name = SQL_FILE.matcher(entry.getName());
            if (!name.matches()) {
                continue;
            }
            byte[] sql = database.readNBytes(MAX_SQL_BYTES + 1);
            if (sql.length > MAX_SQL_BYTES || files.putIfAbsent(Integer.parseInt(name.group(1)),
                    new Migration(entry.getName(), sha256(sql))) != null) {
                throw new IOException("Oversized or duplicate migration " + entry.getName());
            }
        }
        if (files.isEmpty() || files.lastKey() != files.size()) {
            throw new IOException("Packaged migrations must be contiguous from V1");
        }
        return List.copyOf(files.values());
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
