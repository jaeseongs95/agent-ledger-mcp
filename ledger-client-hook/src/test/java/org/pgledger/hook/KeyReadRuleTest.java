package org.pgledger.hook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pgledger.hook.keys.SessionKey;
import org.pgledger.hook.keys.SessionKeyException;
import org.pgledger.hook.keys.SessionKeyException.Reason;
import org.pgledger.hook.keys.SessionKeyStore;
import org.pgledger.testing.BroadAccess;

/**
 * SFR-020 to SFR-023 (docs/testing/TEST_CASES.md section 42): the read rule of the session keys in the personal edition. An existing
 * key folder and key file that other accounts may reach, or that are reached through a link, are read as they are and are not changed;
 * a directory at the place of the key file and an empty key file are refused; what the hook creates is owner-only even below a
 * folder that is open to everybody. The cases choose by the attribute views of the file system (POSIX bits or an ACL), never by the
 * name of the operating system. The owner of an existing file that is not the current user cannot be set without a privilege, so the
 * case "another owner" has no test: the owner is not read any more, which the source rule HK-044 pins.
 */
class KeyReadRuleTest {

    private static final String SERVER = "ledger";
    private static final String AUTHOR = "claude:read-rule-session";

    @TempDir
    Path temp;

    private static Path onlyFile(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.findFirst().orElseThrow();
        }
    }

    private static void assumeProtectable(Path path) {
        assumeTrue(BroadAccess.supported(path), "the file system has neither POSIX permissions nor ACLs");
    }

    @Test
    @DisplayName("SFR-020: a key folder and a key file that other accounts may reach (an ALLOW entry of Authenticated Users, or group and other bits) are read as they are: the same key, the file and the folder not repaired")
    void sfr020_broadAccessIsNotRefused() throws IOException {
        assumeProtectable(temp);
        Path directory = temp.resolve("keys");
        SessionKey first = TestStores.load(TestStores.open(directory), SERVER, AUTHOR);
        Path file = onlyFile(directory);
        BroadAccess.open(directory);
        BroadAccess.open(file);
        assertThat(BroadAccess.reachedByOthers(directory)).as("the test made the folder reachable").isTrue();
        assertThat(BroadAccess.reachedByOthers(file)).as("the test made the file reachable").isTrue();
        byte[] before = Files.readAllBytes(file);

        SessionKey again = TestStores.load(TestStores.open(directory), SERVER, AUTHOR);

        assertThat(again.publicKey().getEncoded()).isEqualTo(first.publicKey().getEncoded());
        assertThat(Files.readAllBytes(file)).as("the key file is not touched").isEqualTo(before);
        assertThat(BroadAccess.reachedByOthers(directory)).as("the folder is not narrowed").isTrue();
        assertThat(BroadAccess.reachedByOthers(file)).as("the file is not narrowed").isTrue();
    }

    @Test
    @DisplayName("SFR-021: a key file that is a link to a valid key file, and a key folder that is a link, are read (a link is not refused); skipped where this process may not make links")
    void sfr021_linksAreFollowed() throws IOException {
        Path directory = temp.resolve("keys");
        SessionKey first = TestStores.load(TestStores.open(directory), SERVER, AUTHOR);
        Path file = onlyFile(directory);
        Path real = temp.resolve("key-file-real");
        Files.move(file, real);
        assumeTrue(BroadAccess.link(file, real), "this process may not make symbolic links");

        assertThat(TestStores.load(TestStores.open(directory), SERVER, AUTHOR).publicKey().getEncoded())
                .as("the key file is a link").isEqualTo(first.publicKey().getEncoded());

        Path realFolder = temp.resolve("keys-real");
        Files.move(directory, realFolder);
        assumeTrue(BroadAccess.link(directory, realFolder), "this process may not make symbolic links");
        assertThat(TestStores.load(TestStores.open(directory), SERVER, AUTHOR).publicKey().getEncoded())
                .as("the key folder is a link").isEqualTo(first.publicKey().getEncoded());
    }

    @Test
    @DisplayName("SFR-022: a directory at the place of the key file is refused as NOT_A_REGULAR_FILE, an empty key file as CORRUPT (nothing is repaired or replaced), a file at the place of the key folder as FOLDER_UNAVAILABLE")
    void sfr022_notRegularOrEmptyIsRefused() throws IOException {
        Path directory = temp.resolve("keys");
        SessionKeyStore store = TestStores.open(directory);
        TestStores.load(store, SERVER, AUTHOR);
        Path file = onlyFile(directory);

        Files.delete(file);
        Files.createDirectory(file);
        assertRefused(directory, Reason.NOT_A_REGULAR_FILE);
        assertThat(file).isDirectory();

        Files.delete(file);
        Files.createFile(file);
        assertRefused(directory, Reason.CORRUPT);
        assertThat(file).as("the empty file is neither repaired nor replaced").hasSize(0);

        Path folderIsAFile = temp.resolve("keys-is-a-file");
        Files.writeString(folderIsAFile, "not a folder");
        assertRefused(folderIsAFile, Reason.FOLDER_UNAVAILABLE);
        assertThat(folderIsAFile).hasContent("not a folder");
    }

    private static void assertRefused(Path directory, Reason reason) {
        assertThatThrownBy(() -> TestStores.load(TestStores.open(directory), SERVER, AUTHOR))
                .isInstanceOfSatisfying(SessionKeyException.class, failure -> assertThat(failure.reason()).isEqualTo(reason));
    }

    @Test
    @DisplayName("SFR-023: below a folder that is open to every account the hook creates the key folder and the key file owner-only (no group or other bit, or an ACL whose every entry is the owner's): the creation is unchanged")
    void sfr023_creationIsOwnerOnly() throws IOException {
        assumeProtectable(temp);
        Path parent = Files.createDirectory(temp.resolve("open-parent"));
        BroadAccess.open(parent);
        Path directory = parent.resolve("keys");

        TestStores.load(TestStores.open(directory), SERVER, AUTHOR);

        assertThat(List.of(directory, onlyFile(directory))).allMatch(BroadAccess::ownerOnly);
    }
}
