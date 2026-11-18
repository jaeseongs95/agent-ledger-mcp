// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.cli.secret;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pgledger.cli.ErrorCode;
import org.pgledger.files.ServicePrincipalAcl;
import org.pgledger.cli.FailureException;
import org.pgledger.cli.support.RequiresPosixPermissions;
import org.pgledger.cli.support.WindowsEffectiveRights;

class SecretStoreTest {

    private static final SecretName NAME = new SecretName("cloudflare-api-token");

    @TempDir
    Path tmp;

    private static SecretStore store(Path directory) {
        return store(directory, new SystemProcessUser());
    }

    private static SecretStore store(Path directory, ProcessUser user) {
        return new SecretStore(directory, directory.getParent(), user);
    }

    private static List<String> names(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    private static void assertRefused(Runnable action, ErrorCode code, String... arguments) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(FailureException.class, e -> {
            assertThat(e.code()).isEqualTo(code);
            assertThat(e.arguments()).containsExactly(arguments);
        });
    }

    @Test
    @RequiresPosixPermissions
    @DisplayName("SEC-001 creates the directory 0700 and the file 0600 with the exact UTF-8 bytes and no trailing newline")
    void sec001_createsDirectoryAndFileWithOwnerOnlyModes() throws IOException {
        Path parent = Files.createDirectories(tmp.resolve("etc").resolve("pg-ledger-mcp"));
        Path directory = parent.resolve("secrets");
        String value = "päss-한글-😀 with space";

        Path file = store(directory).store(NAME, value.toCharArray());

        assertThat(file).isEqualTo(directory.resolve("cloudflare-api-token"));
        assertThat(Files.getPosixFilePermissions(directory)).isEqualTo(PosixFilePermissions.fromString("rwx------"));
        assertThat(Files.getPosixFilePermissions(file)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThat(Files.readAllBytes(file)).isEqualTo(value.getBytes(UTF_8));
        assertThat(names(directory)).containsExactly("cloudflare-api-token");
    }

    @Test
    @RequiresPosixPermissions
    @DisplayName("SEC-002 the same name again is replaced through a new 0600 file and a move, leaving no temporary file")
    void sec002_replacesAtomically() throws IOException {
        Path directory = tmp.resolve("secrets");
        SecretStore store = store(directory);
        Path file = store.store(NAME, "first-value".toCharArray());
        Object firstFileKey = Files.readAttributes(file, BasicFileAttributes.class).fileKey();

        store.store(NAME, "second".toCharArray());

        assertThat(Files.readString(file)).isEqualTo("second");
        assertThat(Files.getPosixFilePermissions(file)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThat(Files.readAttributes(file, BasicFileAttributes.class).fileKey())
                .as("a new inode proves the replacement was not written in place")
                .isNotEqualTo(firstFileKey);
        assertThat(names(directory)).containsExactly("cloudflare-api-token");
    }

    @Test
    @RequiresPosixPermissions
    @DisplayName("SEC-002 a value that cannot be encoded leaves the old content and no temporary file")
    void sec002_failedWriteKeepsOldValue() throws IOException {
        Path directory = tmp.resolve("secrets");
        SecretStore store = store(directory);
        Path file = store.store(NAME, "old-value".toCharArray());

        assertRefused(() -> store.store(NAME, new char[] {'a', '\ud800', 'b'}), ErrorCode.INVALID_ENCODING);

        assertThat(Files.readString(file)).isEqualTo("old-value");
        assertThat(names(directory)).containsExactly("cloudflare-api-token");
    }

    @Test
    @RequiresPosixPermissions
    @DisplayName("SEC-006 (changed by SFR-010) an existing directory with mode 0755 is used as it is: the secret is stored in it, the directory keeps its mode and the new file is 0600")
    void sec006_usesLooseDirectory() throws IOException {
        Path directory = tmp.resolve("secrets");
        Files.createDirectory(directory);
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxr-xr-x"));

        assertThatCode(() -> store(directory).check(NAME)).doesNotThrowAnyException();
        Path file = store(directory).store(NAME, "v".toCharArray());

        assertThat(Files.getPosixFilePermissions(directory)).isEqualTo(PosixFilePermissions.fromString("rwxr-xr-x"));
        assertThat(Files.getPosixFilePermissions(file)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThat(names(directory)).containsExactly("cloudflare-api-token");
    }

    @Test
    @RequiresPosixPermissions
    @DisplayName("SEC-006 (changed by SFR-010) an existing file with mode 0644 is read and replaced: the old value is loaded, the replacement is a new 0600 file")
    void sec006_replacesLooseFile() throws IOException {
        Path directory = tmp.resolve("secrets");
        SecretStore store = store(directory);
        Path file = store.store(NAME, "old-value".toCharArray());
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));

        assertThat(String.valueOf(store.load(NAME))).isEqualTo("old-value");
        store.store(NAME, "new".toCharArray());

        assertThat(Files.readString(file)).isEqualTo("new");
        assertThat(Files.getPosixFilePermissions(file)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThat(names(directory)).containsExactly("cloudflare-api-token");
    }

    @Test
    @RequiresPosixPermissions
    @DisplayName("SEC-006 (changed by SFR-011) a symbolic link in place of the secret file is followed when it is read; a store replaces the link, not the file it points at")
    void sec006_followsSymbolicLinkTarget() throws IOException {
        Path directory = tmp.resolve("secrets");
        SecretStore store = store(directory);
        store.store(new SecretName("other"), "x".toCharArray());
        Files.createSymbolicLink(directory.resolve("cloudflare-api-token"), directory.resolve("other"));

        assertThat(String.valueOf(store.load(NAME))).isEqualTo("x");
        assertThat(store.exists(NAME)).isTrue();
        store.store(NAME, "v".toCharArray());

        assertThat(Files.isSymbolicLink(directory.resolve("cloudflare-api-token"))).isFalse();
        assertThat(Files.readString(directory.resolve("cloudflare-api-token"))).isEqualTo("v");
        assertThat(Files.readString(directory.resolve("other"))).isEqualTo("x");
    }

    /** Answers with the given principals one call after the other; the last one repeats. */
    private static ProcessUser users(UserPrincipal... principals) {
        Iterator<UserPrincipal> remaining = List.of(principals).iterator();
        UserPrincipal[] last = new UserPrincipal[1];
        return fileSystem -> {
            if (remaining.hasNext()) {
                last[0] = remaining.next();
            }
            return last[0];
        };
    }

    private static UserPrincipal named(String name) throws IOException {
        return FileSystems.getDefault().getUserPrincipalLookupService().lookupPrincipalByName(name);
    }

    @Test
    @RequiresPosixPermissions
    @DisplayName("SEC-013 the real process user owns what the store creates, so the owner check passes")
    void sec013_processUserIsTheOwnerOfCreatedFiles() throws IOException {
        Path directory = tmp.resolve("secrets");
        Path file = store(directory).store(NAME, "v".toCharArray());

        assertThat(new SystemProcessUser().current(directory.getFileSystem())).isEqualTo(Files.getOwner(directory)).isEqualTo(Files.getOwner(file));
        assertThatCode(() -> store(directory).check(NAME)).doesNotThrowAnyException();
    }

    @Test
    @RequiresPosixPermissions
    @DisplayName("SEC-013 (changed by SFR-010) a directory and a file whose owner is not the account the command runs as (here: the process account is taken to be root) are used as they are: the check passes, the secret is read and replaced. The owner of a real file cannot be changed without a privilege, so the process account is the stub")
    void sec013_usesDirectoryAndFileOfAnotherOwner() throws IOException {
        Path directory = tmp.resolve("secrets");
        Path file = store(directory).store(NAME, "old".toCharArray());
        UserPrincipal root = named("root");
        assumeThat(root).isNotEqualTo(Files.getOwner(directory));

        assertThatCode(() -> store(directory, users(root)).check(NAME)).doesNotThrowAnyException();
        assertThat(String.valueOf(store(directory, users(root)).load(NAME))).isEqualTo("old");
        store(directory, users(root)).store(NAME, "new".toCharArray());

        assertThat(Files.readString(file)).isEqualTo("new");
    }

    @Test
    @DisplayName("SEC-014 a missing parent directory is refused naming the parent and is not created")
    void sec014_refusesMissingParent() throws IOException {
        Path parent = tmp.resolve("etc").resolve("pg-ledger-mcp");
        Path directory = parent.resolve("secrets");
        assumeThat(directory.getFileSystem().supportedFileAttributeViews()).containsAnyOf("posix", "acl");

        assertRefused(() -> store(directory).store(NAME, "v".toCharArray()), ErrorCode.PARENT_MISSING, parent.toString());
        assertRefused(() -> store(directory).check(NAME), ErrorCode.PARENT_MISSING, parent.toString());

        assertThat(Files.exists(tmp.resolve("etc"))).isFalse();
    }

    @Test
    @RequiresPosixPermissions
    @DisplayName("SEC-014 only the secrets directory itself is created, with mode 0700, inside an existing parent")
    void sec014_createsOnlyTheSecretsDirectory() throws IOException {
        Path parent = Files.createDirectory(tmp.resolve("pg-ledger-mcp"));
        Path directory = parent.resolve("secrets");

        store(directory).store(NAME, "v".toCharArray());

        assertThat(names(parent)).containsExactly("secrets");
        assertThat(Files.getPosixFilePermissions(directory)).isEqualTo(PosixFilePermissions.fromString("rwx------"));
    }

    @Test
    @DisplayName("SEC-012 a file system without POSIX permissions fails with an explicit code and stores nothing")
    void sec012_refusesNonPosixFileSystem() throws IOException {
        try (FileSystem zip = FileSystems.newFileSystem(tmp.resolve("not-posix.zip"), Map.of("create", "true"))) {
            Path directory = zip.getPath("/secrets");

            assertRefused(() -> store(directory).store(NAME, "v".toCharArray()), ErrorCode.NON_POSIX_FILESYSTEM, directory.toString());
            assertRefused(() -> store(directory).check(NAME), ErrorCode.NON_POSIX_FILESYSTEM, directory.toString());

            assertThat(Files.exists(directory)).isFalse();
        }
    }

    // ---- SEC-016 .. SEC-020: a file system with ACLs and without POSIX permissions (the Windows file systems) ----

    /** The test needs the default file system to have ACLs and no POSIX permissions: the capability, never the name of the system. */
    private static void assumeAclFileSystem() {
        assumeThat(FileSystems.getDefault().supportedFileAttributeViews()).contains("acl").doesNotContain("posix");
    }

    private static List<AclEntry> acl(Path path) throws IOException {
        return Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).getAcl();
    }

    private static AclEntry allowAll(UserPrincipal principal) {
        return AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(principal)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build();
    }

    /** The service account LocalService as the file system knows it (the lookup is by the file system). */
    private static UserPrincipal serviceAccount() throws IOException {
        return ServicePrincipalAcl.principal(FileSystems.getDefault());
    }

    /**
     * PCS-030: the ACL of the path has exactly two entries, both ALLOW: the owner, which has the rights to read, write and delete,
     * and the service account LocalService with nothing but the read rights (no write, no delete, no change of the ACL or the owner).
     */
    private static void assertOwnerAndServiceAcl(Path path) throws IOException {
        // the account of the process as the product looks it up (the store makes it the owner, SecretProtection.settle), not an assumption
        UserPrincipal owner = new SystemProcessUser().current(FileSystems.getDefault());
        UserPrincipal service = serviceAccount();
        List<AclEntry> entries = acl(path);
        assertThat(entries).as("the ACL of %s", path).hasSize(2).allSatisfy(entry -> assertThat(entry.type()).isEqualTo(AclEntryType.ALLOW));
        assertThat(entries.stream().map(AclEntry::principal)).containsExactlyInAnyOrder(owner, service);
        AclEntry ownerEntry = entries.stream().filter(entry -> entry.principal().equals(owner)).findFirst().orElseThrow();
        AclEntry serviceEntry = entries.stream().filter(entry -> entry.principal().equals(service)).findFirst().orElseThrow();
        assertThat(ownerEntry.permissions()).contains(AclEntryPermission.READ_DATA, AclEntryPermission.WRITE_DATA,
                AclEntryPermission.DELETE, AclEntryPermission.WRITE_ACL);
        assertThat(serviceEntry.permissions()).contains(AclEntryPermission.READ_DATA, AclEntryPermission.READ_ATTRIBUTES,
                AclEntryPermission.READ_ACL).doesNotContain(AclEntryPermission.WRITE_DATA, AclEntryPermission.APPEND_DATA,
                AclEntryPermission.DELETE, AclEntryPermission.DELETE_CHILD, AclEntryPermission.WRITE_ACL, AclEntryPermission.WRITE_OWNER);
        assertThat(ServicePrincipalAcl.READ_FOLDER).containsAll(serviceEntry.permissions());
    }

    @Test
    @DisplayName("SEC-016 (changed by PCS-030) on a file system with ACLs the directory and the file are created with the ACL of the owner (everything) and of the service account LocalService (read) and the exact UTF-8 bytes")
    void sec016_createsOwnerOnlyAcl() throws IOException {
        assumeAclFileSystem();
        Path directory = tmp.resolve("secrets");
        String value = "päss-한글-😀 with space";

        Path file = store(directory).store(NAME, value.toCharArray());

        assertOwnerAndServiceAcl(directory);
        assertOwnerAndServiceAcl(file);
        UserPrincipal self = new SystemProcessUser().current(FileSystems.getDefault());
        assertThat(Files.getOwner(directory)).as("the account of the process owns it, not the group Administrators").isEqualTo(self);
        assertThat(Files.getOwner(file)).isEqualTo(self);
        assertThat(Files.readAllBytes(file)).isEqualTo(value.getBytes(UTF_8));
        assertThat(names(directory)).containsExactly("cloudflare-api-token");
        assertThatCode(() -> store(directory).check(NAME)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("SEC-017 (changed by PCS-030) on a file system with ACLs the same name again is replaced through a new file with the same two entries and a move, leaving no temporary file")
    void sec017_replacesWithOwnerOnlyAcl() throws IOException {
        assumeAclFileSystem();
        Path directory = tmp.resolve("secrets");
        SecretStore store = store(directory);
        Path file = store.store(NAME, "first-value".toCharArray());

        store.store(NAME, "second".toCharArray());

        assertThat(Files.readString(file)).isEqualTo("second");
        assertOwnerAndServiceAcl(file);
        assertThat(names(directory)).containsExactly("cloudflare-api-token");
    }

    /** SYSTEM exists on every Windows file system and is not the current user; the lookup is by the file system. */
    private static UserPrincipal system() {
        try {
            return FileSystems.getDefault().getUserPrincipalLookupService().lookupPrincipalByName("SYSTEM");
        } catch (IOException absent) {
            Assumptions.abort("this machine has no account SYSTEM");
            throw new IllegalStateException(absent);
        }
    }

    @Test
    @DisplayName("SEC-018 (changed by SFR-010) on a file system with ACLs an existing directory whose ACL allows another account is used as it is: check and store pass, its ACL is not narrowed")
    void sec018_usesDirectoryThatAllowsAnotherAccount() throws IOException {
        assumeAclFileSystem();
        Path directory = tmp.resolve("secrets");
        store(directory).store(NAME, "old".toCharArray());
        UserPrincipal owner = Files.getOwner(directory);
        Files.getFileAttributeView(directory, AclFileAttributeView.class).setAcl(List.of(allowAll(owner), allowAll(system())));
        List<AclEntry> before = acl(directory);

        assertThatCode(() -> store(directory).check(NAME)).doesNotThrowAnyException();
        store(directory).store(NAME, "new".toCharArray());

        assertThat(acl(directory)).isEqualTo(before);
        assertThat(Files.readString(directory.resolve("cloudflare-api-token"))).isEqualTo("new");
        assertOwnerAndServiceAcl(directory.resolve("cloudflare-api-token"));
    }

    @Test
    @DisplayName("SEC-019 (changed by SFR-010) on a file system with ACLs an existing secret file whose ACL allows another account is read and replaced: the replacement is a new file with the owner and service entries")
    void sec019_replacesFileThatAllowsAnotherAccount() throws IOException {
        assumeAclFileSystem();
        Path directory = tmp.resolve("secrets");
        Path file = store(directory).store(NAME, "old".toCharArray());
        UserPrincipal owner = Files.getOwner(file);
        Files.getFileAttributeView(file, AclFileAttributeView.class).setAcl(List.of(allowAll(owner), allowAll(system())));

        assertThat(String.valueOf(store(directory).load(NAME))).isEqualTo("old");
        store(directory).store(NAME, "new".toCharArray());

        assertThat(Files.readString(file)).isEqualTo("new");
        assertOwnerAndServiceAcl(file);
    }

    @Test
    @DisplayName("PCS-031 the service account LocalService has the effective right to list the secrets folder and to read the secret (the Windows API computes it from the ACL), and no right to write, append, delete or change the ACL or the owner; Everyone and Users have none")
    void pcs031_theServiceAccountReadsTheSecretByTheNewAcl() throws IOException {
        assumeAclFileSystem();
        Path directory = tmp.resolve("secrets");
        Path file = store(directory).store(NAME, "never-printed-0123456789".toCharArray());

        for (Path path : List.of(directory, file)) {
            int service = WindowsEffectiveRights.of(path, ServicePrincipalAcl.WINDOWS_SERVICE_ACCOUNT);
            assertThat(service & WindowsEffectiveRights.FILE_READ_DATA).as("read of %s", path).isNotZero();
            assertThat(service & (WindowsEffectiveRights.FILE_WRITE_DATA | WindowsEffectiveRights.FILE_APPEND_DATA
                    | WindowsEffectiveRights.DELETE | WindowsEffectiveRights.WRITE_DAC | WindowsEffectiveRights.WRITE_OWNER))
                    .as("no change by the service account of %s", path).isZero();
            assertThat(WindowsEffectiveRights.of(path, "Everyone")).as("Everyone on %s", path).isZero();
            assertThat(WindowsEffectiveRights.of(path, "BUILTIN\\Users")).as("Users on %s", path).isZero();
        }
        assertThat(WindowsEffectiveRights.of(directory, ServicePrincipalAcl.WINDOWS_SERVICE_ACCOUNT)
                & WindowsEffectiveRights.FILE_EXECUTE).as("the service account may traverse the folder").isNotZero();
    }

    @Test
    @DisplayName("PCS-030 a secret made in a folder that the service account may modify (the settings folder: an inherited entry) gets the same two entries and no write right for the service account")
    void pcs030_theInheritedEntryOfTheParentIsReplaced() throws IOException {
        assumeAclFileSystem();
        UserPrincipal self = new SystemProcessUser().current(FileSystems.getDefault());
        Path parent = Files.createDirectories(tmp.resolve("etc"));
        AclEntry modify = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(serviceAccount())
                .setPermissions(EnumSet.complementOf(EnumSet.of(AclEntryPermission.WRITE_ACL, AclEntryPermission.WRITE_OWNER)))
                .setFlags(java.nio.file.attribute.AclEntryFlag.FILE_INHERIT, java.nio.file.attribute.AclEntryFlag.DIRECTORY_INHERIT).build();
        Files.getFileAttributeView(parent, AclFileAttributeView.class).setAcl(List.of(allowAll(self), modify));
        Path directory = parent.resolve("secrets");

        Path file = store(directory).store(NAME, "x".toCharArray());

        assertOwnerAndServiceAcl(directory);
        assertOwnerAndServiceAcl(file);
        assertThat(WindowsEffectiveRights.of(file, ServicePrincipalAcl.WINDOWS_SERVICE_ACCOUNT) & WindowsEffectiveRights.FILE_WRITE_DATA).isZero();
    }

    @Test
    @DisplayName("PCS-032 a new secret in an existing folder whose ACL has an entry for another account is made with the two entries, and the folder itself is not narrowed (an existing path is never judged)")
    void pcs032_aNewSecretInAnExistingLooseFolder() throws IOException {
        assumeAclFileSystem();
        Path directory = tmp.resolve("secrets");
        store(directory).store(new SecretName("first"), "1".toCharArray());
        UserPrincipal owner = Files.getOwner(directory);
        Files.getFileAttributeView(directory, AclFileAttributeView.class).setAcl(List.of(allowAll(owner), allowAll(system())));
        List<AclEntry> before = acl(directory);

        Path file = store(directory).store(NAME, "2".toCharArray());

        assertThat(acl(directory)).isEqualTo(before);
        assertOwnerAndServiceAcl(file);
        assertThat(names(directory)).containsExactly("cloudflare-api-token", "first");
    }

    // SEC-020 (an owner that is not the current user is refused, FOREIGN_OWNER) was removed with the rule: the owner of an existing
    // file is no longer looked at (SFR-010). A stub account cannot test the new rule on a file system with ACLs, because the store
    // takes ownership of what it creates for the account it is given (SEC-016), so the rule is shown by the absence of the check.

    @Test
    @DisplayName("SEC-022 exists reports whether a secret is stored, and refuses like check when the store or the file cannot be used")
    void sec022_existsReportsPresenceAndRefusesLikeCheck() throws IOException {
        assumeThat(FileSystems.getDefault().supportedFileAttributeViews()).containsAnyOf("posix", "acl");
        Path directory = tmp.resolve("secrets");
        SecretStore store = store(directory);
        SecretStore orphan = store(tmp.resolve("etc").resolve("pg-ledger-mcp").resolve("secrets"));
        assertRefused(() -> orphan.exists(NAME), ErrorCode.PARENT_MISSING, tmp.resolve("etc").resolve("pg-ledger-mcp").toString());
        store.store(new SecretName("other"), "x".toCharArray());

        assertThat(store.exists(NAME)).isFalse();
        store.store(NAME, "v".toCharArray());
        assertThat(store.exists(NAME)).isTrue();
        assertThat(Files.readString(directory.resolve(NAME.value()))).isEqualTo("v");
    }

    @Test
    @DisplayName("SEC-021 (changed by CLI-PRN-11) a protection is chosen by the attribute views of the file system: POSIX first, then ACL; a file system with neither is refused as NON_POSIX_FILESYSTEM naming the directory")
    void sec021_protectionFollowsTheAttributeViews() throws IOException {
        FileSystem system = FileSystems.getDefault();
        Set<String> views = system.supportedFileAttributeViews();
        SecretProtection chosen = SecretProtection.of(tmp, new SystemProcessUser());

        assertThat(chosen instanceof SecretProtection.Posix).isEqualTo(views.contains("posix"));
        assertThat(chosen instanceof SecretProtection.Acl).isEqualTo(!views.contains("posix") && views.contains("acl"));
        try (FileSystem zip = FileSystems.newFileSystem(tmp.resolve("neither.zip"), Map.of("create", "true"))) {
            Path inside = zip.getPath("/secrets");

            assertThatThrownBy(() -> SecretProtection.of(inside, new SystemProcessUser()))
                    .isInstanceOfSatisfying(FailureException.class, refused -> {
                        assertThat(refused.code()).isEqualTo(ErrorCode.NON_POSIX_FILESYSTEM);
                        assertThat(refused.arguments()).containsExactly(inside.toString());
                    });
        }
    }
}
