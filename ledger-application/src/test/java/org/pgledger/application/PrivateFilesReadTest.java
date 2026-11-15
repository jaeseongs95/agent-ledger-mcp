package org.pgledger.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.application.attachment.AttachmentKeys;
import org.pgledger.application.attachment.AttachmentKeys.Key;
import org.pgledger.application.attachment.AttachmentProperties;
import org.pgledger.application.attachment.AttachmentStore;
import org.pgledger.application.support.AttachmentSettings;
import org.pgledger.application.support.StoreFixture;
import org.pgledger.application.support.StoreFixture.Written;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;
import org.pgledger.domain.value.Sha256;
import org.pgledger.testing.BroadAccess;

/**
 * SFR-001 to SFR-005 (docs/testing/TEST_CASES.md section 41): the read rule of the secret files of the attachments in the personal
 * edition. The key folder, the key files, the storage folder and its files are read and used when other accounts may reach them, and
 * when they are reached through a link; a path that is not a regular file (not a folder where a folder belongs) or an empty key file
 * is refused; what the product creates is still owner-only. No database: the storage is built by hand on a temporary folder.
 *
 * <p>The owner of an existing file that is not the process account cannot be set without a privilege, so the case "another owner"
 * has no test here: the code no longer reads the owner, and the absence of the check is the rule (SFR-001 names the limit).
 */
class PrivateFilesReadTest {

    private StoreFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = StoreFixture.create();
    }

    @AfterEach
    void tearDown() {
        fixture.close();
    }

    @Test
    @DisplayName("SFR-001: a key folder, a key file, a storage folder and its files that other accounts may reach are read and written: the key is returned, the start check passes, an upload is stored and read (no refusal for broad permissions)")
    void sfr001_broadAccessIsNotRefused() throws Exception {
        Path keyDirectory = fixture.properties().keyDirectory();
        Path keyFile = keyDirectory.resolve(fixture.keyId());
        Path directory = fixture.properties().directory();
        assumeTrue(BroadAccess.supported(keyFile), "the file system has neither POSIX permissions nor ACLs");
        Written first = fixture.write(fixture.keyId(), "before".getBytes());
        BroadAccess.open(keyDirectory);
        BroadAccess.open(keyFile);
        BroadAccess.open(directory);
        BroadAccess.open(directory.resolve(first.key().folder()));
        BroadAccess.open(fixture.fileOf(first.key()));
        assertThat(List.of(keyDirectory, keyFile, directory, fixture.fileOf(first.key())))
                .as("the test really made them reachable").allMatch(BroadAccess::reachedByOthers);

        Key key = fixture.keys().byId(fixture.keyId());
        fixture.keys().requirePresent(List.of(fixture.keyId()));
        Written second = fixture.write(fixture.keyId(), "after".getBytes());

        assertThat(key.id()).isEqualTo(fixture.keyId());
        fixture.store().requireDiskSpace(600);
        assertThat(Files.size(fixture.fileOf(second.key()))).isPositive();
        assertThat(fixture.fileOf(first.key())).as("the file made before reads again").isRegularFile();
        try (var channel = fixture.files().openRead(fixture.fileOf(first.key()))) {
            assertThat(channel.size()).isPositive();
        }
    }

    @Test
    @DisplayName("SFR-002: a key file that is a link to a valid key file, a key folder that is a link and a storage folder that is a link are read and used (a link is not refused); the case is skipped where this process cannot make links")
    void sfr002_linksAreFollowed() throws Exception {
        Path keyDirectory = fixture.properties().keyDirectory();
        String second = fixture.keys().create();
        Path realKeyFile = fixture.root().resolve("key-file-real");
        Files.move(keyDirectory.resolve(second), realKeyFile);
        assumeTrue(BroadAccess.link(keyDirectory.resolve(second), realKeyFile), "this process may not make symbolic links");

        assertThat(fixture.keys().byId(second).id()).as("the key file is a link").isEqualTo(second);

        Path realKeys = fixture.root().resolve("keys-real");
        Path realStorage = fixture.root().resolve("attachments-real");
        Files.move(keyDirectory, realKeys);
        Files.move(fixture.properties().directory(), realStorage);
        assertThat(BroadAccess.link(keyDirectory, realKeys)).isTrue();
        assertThat(BroadAccess.link(fixture.properties().directory(), realStorage)).isTrue();

        assertThat(fixture.keys().byId(fixture.keyId()).id()).as("the key folder is a link").isEqualTo(fixture.keyId());
        fixture.keys().requirePresent(List.of(fixture.keyId(), second));
        Written written = fixture.write(fixture.keyId(), "through a link".getBytes());
        fixture.store().requireDiskSpace(600);
        assertThat(Files.size(fixture.fileOf(written.key()))).as("the storage folder is a link").isPositive();
    }

    @Test
    @DisplayName("SFR-003 (design edition 11): a directory where the key file is expected is ARTIFACT_STORAGE_UNSAFE; an empty key file, a key file of another length and a missing key file are ARTIFACT_KEY_MISSING; a file where the key folder or the storage folder belongs is ARTIFACT_STORAGE_UNSAFE")
    void sfr003_notRegularOrEmptyIsRefused() throws Exception {
        Path keyDirectory = fixture.properties().keyDirectory();

        Files.createDirectory(keyDirectory.resolve("key-is-a-directory"));
        Files.createFile(keyDirectory.resolve("key-is-empty"));
        Files.write(keyDirectory.resolve("key-too-short"), new byte[7]);

        assertThatThrownBy(() -> fixture.keys().byId("key-is-a-directory")).isInstanceOfSatisfying(InfrastructureException.class,
                failure -> assertThat(failure.code()).isEqualTo(InfrastructureCode.ARTIFACT_STORAGE_UNSAFE));
        for (String name : List.of("key-is-empty", "key-too-short", "key-absent")) {
            assertThatThrownBy(() -> fixture.keys().byId(name)).as(name).isInstanceOfSatisfying(InfrastructureException.class,
                    failure -> {
                        assertThat(failure.code()).isEqualTo(InfrastructureCode.ARTIFACT_KEY_MISSING);
                        assertThat(failure.args()).containsExactly(name);
                    });
        }

        Path fileAsKeyFolder = fixture.root().resolve("keys-is-a-file");
        Files.writeString(fileAsKeyFolder, "not a folder");
        AttachmentProperties keyFolderIsFile = AttachmentSettings.properties(fixture.properties().directory(), fileAsKeyFolder,
                fixture.keyId());
        assertThatThrownBy(() -> new AttachmentKeys(keyFolderIsFile, fixture.files(), new SecureRandom())
                .requirePresent(List.of())).isInstanceOfSatisfying(InfrastructureException.class,
                        failure -> assertThat(failure.code()).isEqualTo(InfrastructureCode.ARTIFACT_STORAGE_UNSAFE));

        Path fileAsStorage = fixture.root().resolve("attachments-is-a-file");
        Files.writeString(fileAsStorage, "not a folder");
        AttachmentStore onAFile = new AttachmentStore(
                AttachmentSettings.properties(fileAsStorage, keyDirectory, fixture.keyId()), fixture.files(), fixture.keys(),
                fixture.cipher());
        assertThatThrownBy(() -> onAFile.requireDiskSpace(600)).isInstanceOfSatisfying(InfrastructureException.class,
                failure -> assertThat(failure.code()).isEqualTo(InfrastructureCode.ARTIFACT_STORAGE_UNSAFE));
        assertThat(fileAsStorage).as("the file is not replaced").hasContent("not a folder");
    }

    @Test
    @DisplayName("SFR-006: ensureDirectory on <tmp>/a/b/attachments where a and b do not exist makes both (the product makes its own folders, as the data folder of a fresh home) and the last folder owner-only; the key folder of AttachmentKeys.create does the same")
    void sfr006_missingAncestorsAreMade() throws Exception {
        assumeTrue(BroadAccess.supported(fixture.root()), "the file system has neither POSIX permissions nor ACLs");
        Path last = fixture.root().resolve("a").resolve("b").resolve("attachments");
        assertThat(fixture.root().resolve("a")).doesNotExist();

        fixture.files().ensureDirectory(last);

        assertThat(fixture.root().resolve("a").resolve("b")).isDirectory();
        assertThat(last).isDirectory();
        assertThat(BroadAccess.ownerOnly(last)).as("the last folder is owner-only").isTrue();
        fixture.files().ensureDirectory(last);

        Path keyDirectory = fixture.root().resolve("c").resolve("d").resolve("keys");
        AttachmentProperties properties = AttachmentSettings.properties(last, keyDirectory, "to-be-created");
        String keyId = new AttachmentKeys(properties, fixture.files(), new SecureRandom()).create();
        assertThat(keyDirectory.resolve(keyId)).isRegularFile();
        assertThat(BroadAccess.ownerOnly(keyDirectory)).as("the key folder is owner-only").isTrue();
        assertThat(BroadAccess.ownerOnly(keyDirectory.resolve(keyId))).as("the key file is owner-only").isTrue();
    }

    @Test
    @DisplayName("SFR-004: what the product creates stays owner-only (POSIX rwx------ / rw-------, or an ACL whose every entry is the owner's): the key folder, a new key file, the storage folder, its sub folder and a stored file, even when the folder above them is open to every account")
    void sfr004_creationIsOwnerOnly() throws Exception {
        Path root = fixture.root();
        assumeTrue(BroadAccess.supported(root), "the file system has neither POSIX permissions nor ACLs");
        Files.createDirectory(root.resolve("open-parent"));
        BroadAccess.open(root.resolve("open-parent"));
        AttachmentProperties properties = AttachmentSettings.properties(root.resolve("open-parent").resolve("attachments"),
                root.resolve("open-parent").resolve("keys"), "to-be-created");
        String keyId = new AttachmentKeys(properties, fixture.files(), new SecureRandom()).create();
        AttachmentProperties real = AttachmentSettings.properties(properties.directory(), properties.keyDirectory(), keyId);
        AttachmentStore store = new AttachmentStore(real, fixture.files(), new AttachmentKeys(real, fixture.files(), new SecureRandom()),
                fixture.cipher());
        store.prepare();
        var storageKey = fixture.storageKeys().next();
        byte[] chunk = "owner only".getBytes();
        store.append(storageKey, keyId, 0, 0, chunk, Sha256.ofBytes(chunk));

        assertThat(BroadAccess.ownerOnly(real.keyDirectory())).as("key folder").isTrue();
        assertThat(BroadAccess.ownerOnly(real.keyDirectory().resolve(keyId))).as("key file").isTrue();
        assertThat(BroadAccess.ownerOnly(real.directory())).as("storage folder").isTrue();
        assertThat(BroadAccess.ownerOnly(real.directory().resolve(storageKey.folder()))).as("sub folder").isTrue();
        assertThat(BroadAccess.ownerOnly(real.directory().resolve(storageKey.folder()).resolve(storageKey.hex())))
                .as("stored file").isTrue();
    }
}
