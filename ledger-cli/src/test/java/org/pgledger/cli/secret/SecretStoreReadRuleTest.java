package org.pgledger.cli.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.FailureException;
import org.pgledger.testing.BroadAccess;

/**
 * SFR-010 to SFR-013 (docs/testing/TEST_CASES.md section 42): the read rule of the secret store in the personal edition. A secrets
 * directory and a secret file that other accounts may reach, or that are reached through a link, are read and replaced; a path of the
 * wrong kind and an empty secret file are refused; what the store creates is owner-only even below a folder that is open to everybody.
 * The cases choose by the attribute views of the file system (POSIX bits or an ACL), never by the name of the operating system.
 */
class SecretStoreReadRuleTest {

    private static final SecretName NAME = new SecretName("cloudflare-api-token");

    @TempDir
    Path tmp;

    private static SecretStore store(Path directory) {
        return new SecretStore(directory, directory.getParent(), new SystemProcessUser());
    }

    private static void assumeProtectable(Path path) {
        assumeTrue(BroadAccess.supported(path), "the file system has neither POSIX permissions nor ACLs");
    }

    @Test
    @DisplayName("SFR-010: a secrets directory and a secret file that other accounts may reach (an ALLOW entry of Authenticated Users, or group and other bits) are checked, found, loaded and replaced; the directory stays as it is and the replacement is owner-only")
    void sfr010_broadAccessIsNotRefused() throws IOException {
        assumeProtectable(tmp);
        Path directory = tmp.resolve("secrets");
        Path file = store(directory).store(NAME, "old-value".toCharArray());
        BroadAccess.open(directory);
        BroadAccess.open(file);
        assertThat(BroadAccess.reachedByOthers(directory)).as("the test made the directory reachable").isTrue();
        assertThat(BroadAccess.reachedByOthers(file)).as("the test made the file reachable").isTrue();

        assertThatCode(() -> store(directory).check(NAME)).doesNotThrowAnyException();
        assertThat(store(directory).exists(NAME)).isTrue();
        assertThat(String.valueOf(store(directory).load(NAME))).isEqualTo("old-value");
        store(directory).store(NAME, "new-value".toCharArray());

        assertThat(Files.readString(file)).isEqualTo("new-value");
        assertThat(BroadAccess.reachedByOthers(directory)).as("the existing directory is not narrowed").isTrue();
        assertThat(BroadAccess.ownerAndServiceRead(file)).as("what the store creates is owner-only").isTrue();
    }

    @Test
    @DisplayName("SFR-011: a secret file that is a link to a valid file, and a secrets directory that is a link, are read (a link is not refused); skipped where this process may not make links")
    void sfr011_linksAreFollowed() throws IOException {
        Path real = tmp.resolve("real");
        Path file = store(real).store(NAME, "through-a-link".toCharArray());
        Path directoryLink = tmp.resolve("link-to-secrets");
        assumeTrue(BroadAccess.link(directoryLink, real), "this process may not make symbolic links");
        Path other = tmp.resolve("other");
        store(other).store(new SecretName("another"), "x".toCharArray());
        assumeTrue(BroadAccess.link(other.resolve(NAME.value()), file), "this process may not make symbolic links");

        assertThat(store(directoryLink).exists(NAME)).isTrue();
        assertThat(String.valueOf(store(directoryLink).load(NAME))).isEqualTo("through-a-link");
        assertThat(String.valueOf(store(other).load(NAME))).isEqualTo("through-a-link");
        assertThatCode(() -> store(other).check(NAME)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("SFR-012: a directory at the place of the secret file, and a file at the place of the secrets directory, are refused with UNEXPECTED_FILE_TYPE (naming the path); an empty secret file is refused by load with EMPTY_SECRET_FILE (naming the secret, never a content)")
    void sfr012_wrongKindAndEmptyAreRefused() throws IOException {
        assumeProtectable(tmp);
        Path directory = tmp.resolve("secrets");
        store(directory).store(new SecretName("other"), "x".toCharArray());
        Files.createDirectory(directory.resolve(NAME.value()));

        for (Runnable action : List.<Runnable>of(() -> store(directory).check(NAME), () -> store(directory).exists(NAME),
                () -> store(directory).load(NAME), () -> store(directory).store(NAME, "v".toCharArray()))) {
            assertThatThrownBy(action::run).isInstanceOfSatisfying(FailureException.class, e -> {
                assertThat(e.code()).isEqualTo(ErrorCode.UNEXPECTED_FILE_TYPE);
                assertThat(e.arguments()).containsExactly(directory.resolve(NAME.value()).toString());
            });
        }

        Path fileAsDirectory = tmp.resolve("secrets-is-a-file");
        Files.writeString(fileAsDirectory, "not a directory");
        assertThatThrownBy(() -> store(fileAsDirectory).check(NAME)).isInstanceOfSatisfying(FailureException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.UNEXPECTED_FILE_TYPE));
        assertThat(fileAsDirectory).hasContent("not a directory");

        SecretName empty = new SecretName("empty-one");
        Files.createFile(directory.resolve(empty.value()));
        assertThatThrownBy(() -> store(directory).load(empty)).isInstanceOfSatisfying(FailureException.class, e -> {
            assertThat(e.code()).isEqualTo(ErrorCode.EMPTY_SECRET_FILE);
            assertThat(e.arguments()).containsExactly("empty-one");
        });
    }

    @Test
    @DisplayName("SFR-013: below a folder that is open to every account the store creates the secrets directory and the secret file owner-only (no group or other bit, or an ACL whose every entry is the owner's): the creation is unchanged")
    void sfr013_creationIsOwnerOnly() throws IOException {
        assumeProtectable(tmp);
        Path parent = Files.createDirectory(tmp.resolve("open-parent"));
        BroadAccess.open(parent);
        Path directory = parent.resolve("secrets");

        Path file = store(directory).store(NAME, "v".toCharArray());

        assertThat(BroadAccess.ownerAndServiceRead(directory)).as("directory").isTrue();
        assertThat(BroadAccess.ownerAndServiceRead(file)).as("file").isTrue();
    }
}
