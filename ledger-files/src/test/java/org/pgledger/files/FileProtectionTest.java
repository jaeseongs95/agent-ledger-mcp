// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.files;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** REM-007 (D-0045): shared POSIX/ACL file protection and settle behavior. */
class FileProtectionTest {

    @Test
    @DisplayName("REM-007 Unprotected leaves attributes empty and reports no exposure")
    void unprotectedLeavesDefaults(@TempDir Path temp) throws IOException {
        FileProtection protection = new FileProtection.Unprotected();
        assertThat(protection.directoryAttributes()).isEmpty();
        assertThat(protection.fileAttributes()).isEmpty();
        Path file = Files.createFile(temp.resolve("plain.txt"));
        assertThat(protection.openExposure(file)).isEmpty();
        assertThat(protection.settle(file, false)).isEmpty();
    }

    @Test
    @DisplayName("REM-007 Acl settles permissive inherited entries down to owner(+reader) only")
    void aclSettlesOpenEntries(@TempDir Path temp) throws IOException {
        FileSystem fs = temp.getFileSystem();
        Assumptions.assumeTrue(fs.supportedFileAttributeViews().contains("acl")
                && !fs.supportedFileAttributeViews().contains("posix"), "ACL-only file system test");
        UserPrincipal owner = fs.getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name"));
        UserPrincipal service = ServicePrincipalAcl.principal(fs);
        FileProtection.Acl ownerOnly = new FileProtection.Acl(owner);
        Path dir = Files.createDirectory(temp.resolve("priv-dir"), ownerOnly.directoryAttributes());
        Path file = Files.createFile(dir.resolve("priv.txt"), ownerOnly.fileAttributes());

        UserPrincipal everyone = fs.getUserPrincipalLookupService().lookupPrincipalByName("Everyone");
        AclFileAttributeView view = Files.getFileAttributeView(file, AclFileAttributeView.class);
        List<AclEntry> widened = new ArrayList<>(view.getAcl());
        widened.add(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(everyone)
                .setPermissions(Set.of(AclEntryPermission.READ_DATA)).build());
        view.setAcl(widened);

        assertThat(ownerOnly.openExposure(file)).contains(new FileProtection.Exposure.AclPrincipal(everyone.getName()));
        assertThat(ownerOnly.settle(file, true)).isEmpty();

        FileProtection.Acl withService = new FileProtection.Acl(owner, service, false);
        assertThat(withService.directoryAttributes()).hasSize(1);
        assertThat(withService.entries(false)).hasSizeBetween(1, 2);
    }

    @Test
    @DisplayName("REM-007 Posix inspects group and other bits")
    void posixChecksModes(@TempDir Path temp) throws IOException {
        Assumptions.assumeTrue(temp.getFileSystem().supportedFileAttributeViews().contains("posix"),
                "POSIX file system test");
        FileProtection.Posix posix = new FileProtection.Posix();
        Path file = Files.createFile(temp.resolve("secret.txt"), posix.fileAttributes());
        assertThat(posix.openExposure(file)).isEmpty();
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r-----"));
        assertThat(posix.openExposure(file)).isPresent();
    }

    @Test
    @DisplayName("F9 Acl.settle uses NOFOLLOW_LINKS owner lookup without following symlink targets")
    void aclSettleHandlesMissingOrNonSymlinkPathsSafely(@TempDir Path temp) throws IOException {
        FileSystem fs = temp.getFileSystem();
        Assumptions.assumeTrue(fs.supportedFileAttributeViews().contains("acl")
                && !fs.supportedFileAttributeViews().contains("posix"), "ACL-only file system test");
        UserPrincipal owner = fs.getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name"));
        FileProtection.Acl ownerOnly = new FileProtection.Acl(owner);
        Path target = Files.createFile(temp.resolve("real.txt"), ownerOnly.fileAttributes());
        Path link = temp.resolve("link.txt");
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException denied) {
            Assumptions.assumeTrue(false, "process cannot create symlinks: " + denied.getMessage());
        }
        assertThat(ownerOnly.settle(link, true)).isEmpty();
        assertThat(Files.isSymbolicLink(link)).isTrue();
    }
}
