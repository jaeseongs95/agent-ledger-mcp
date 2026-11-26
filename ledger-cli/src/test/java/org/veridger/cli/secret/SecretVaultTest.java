// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.cli.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.veridger.cli.ErrorCode;
import org.veridger.cli.FailureException;
import org.veridger.cli.support.RequiresPosixPermissions;

/**
 * SPL-002 (defect D-0031): the secrets go into the folder their name belongs in, with the protection of the secret directory
 * (owner-only), and the folders are made when their first secret is stored. The store of one folder is {@code SecretStore}
 * (SEC-001 .. SEC-028, unchanged).
 */
class SecretVaultTest {

    @TempDir
    Path tmp;

    private Path etc() {
        return tmp.resolve("etc");
    }

    private Path secrets() {
        return etc().resolve("secrets");
    }

    private SecretVault vault() {
        return new SecretVault(new SecretsProperties("veridger", secrets()), new SystemProcessUser());
    }

    private static List<String> names(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    @RequiresPosixPermissions
    @DisplayName("SPL-002 each secret is stored in its folder: 0700 for the secret directory and the folder, 0600 for the file; only the folders that are used are made; nothing is stored at the top")
    void spl002_storesInTheFolderOfTheName() throws IOException {
        Files.createDirectories(etc());
        SecretVault vault = vault();

        Path password = vault.store(new SecretName("ledger.jdbc.password"), "db-pass".toCharArray());
        Path mcp = vault.store(new SecretName("ledger.mcp.token"), "mcp-token".toCharArray());
        Path admin = vault.store(new SecretName("ledger.admin.token"), "admin-token".toCharArray());

        assertThat(password).isEqualTo(secrets().resolve("common").resolve("ledger.jdbc.password"));
        assertThat(mcp).isEqualTo(secrets().resolve("mcp").resolve("ledger.mcp.token"));
        assertThat(admin).isEqualTo(secrets().resolve("admin").resolve("ledger.admin.token"));
        assertThat(names(secrets())).as("no file at the top, no folder that is not used").containsExactly("admin", "common", "mcp");
        for (Path directory : List.of(secrets(), secrets().resolve("common"), secrets().resolve("mcp"), secrets().resolve("admin"))) {
            assertThat(Files.getPosixFilePermissions(directory)).as(directory.toString())
                    .isEqualTo(PosixFilePermissions.fromString("rwx------"));
        }
        for (Path file : List.of(password, mcp, admin)) {
            assertThat(Files.getPosixFilePermissions(file)).as(file.toString()).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        }
        assertThat(Files.readString(admin)).isEqualTo("admin-token");
    }

    @Test
    @RequiresPosixPermissions
    @DisplayName("SPL-002 exists and load look in the folder of the name; a secret of another folder is not seen")
    void spl002_existsAndLoadFollowTheFolder() throws IOException {
        Files.createDirectories(etc());
        SecretVault vault = vault();
        SecretName token = new SecretName("ledger.mcp.token");
        SecretName agent = new SecretName("ledger.admin.agent-token");

        assertThat(vault.exists(token)).isFalse();
        vault.store(token, "the-value".toCharArray());

        assertThat(vault.exists(token)).isTrue();
        assertThat(vault.exists(agent)).as("the copy for the admin web is another secret in another folder").isFalse();
        assertThat(String.valueOf(vault.load(token))).isEqualTo("the-value");
        vault.store(agent, vault.load(token));
        assertThat(Files.readString(secrets().resolve("admin").resolve("ledger.admin.agent-token"))).isEqualTo("the-value");
        assertThat(names(secrets().resolve("mcp"))).containsExactly("ledger.mcp.token");
    }

    @Test
    @RequiresPosixPermissions
    @DisplayName("SPL-002 a name that is no secret of the product is refused by every operation before anything is made or read")
    void spl002_unknownNamesAreRefused() throws IOException {
        Files.createDirectories(etc());
        SecretVault vault = vault();
        SecretName unknown = new SecretName("cloudflare-api-token");

        for (Runnable operation : List.<Runnable>of(() -> vault.check(unknown), () -> vault.exists(unknown), () -> vault.load(unknown),
                () -> vault.store(unknown, "x".toCharArray()))) {
            assertThatThrownBy(operation::run).isInstanceOfSatisfying(FailureException.class,
                    failure -> assertThat(failure.code()).isEqualTo(ErrorCode.UNKNOWN_SECRET_NAME));
        }
        assertThat(Files.exists(secrets())).as("nothing is made").isFalse();
    }

    @Test
    @RequiresPosixPermissions
    @DisplayName("SPL-011 a file where the secret directory belongs is UNEXPECTED_FILE_TYPE from check and from store, before anything is made or written")
    void spl011_aFileInsteadOfTheSecretDirectoryIsRefused() throws IOException {
        Files.createDirectories(etc());
        Files.writeString(secrets(), "not a directory");
        SecretVault vault = vault();
        SecretName token = new SecretName("ledger.mcp.token");

        for (Runnable operation : List.<Runnable>of(() -> vault.check(token), () -> vault.exists(token),
                () -> vault.store(token, "x".toCharArray()))) {
            assertThatThrownBy(operation::run).isInstanceOfSatisfying(FailureException.class, failure -> {
                assertThat(failure.code()).isEqualTo(ErrorCode.UNEXPECTED_FILE_TYPE);
                assertThat(failure.arguments()).containsExactly(secrets().toString());
            });
        }
        assertThat(Files.readString(secrets())).isEqualTo("not a directory");
        assertThat(names(etc())).containsExactly("secrets");
    }

    @Test
    @RequiresPosixPermissions
    @DisplayName("SPL-002 the settings folder belongs to the installation: without it nothing is made (PARENT_MISSING), as for the secret directory before")
    void spl002_theSettingsFolderMustExist() {
        SecretVault vault = vault();

        assertThatThrownBy(() -> vault.store(new SecretName("ledger.mcp.token"), "x".toCharArray()))
                .isInstanceOfSatisfying(FailureException.class, failure -> assertThat(failure.code()).isEqualTo(ErrorCode.PARENT_MISSING));
        assertThat(Files.exists(etc())).isFalse();
    }
}
