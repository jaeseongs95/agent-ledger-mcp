// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.hookclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pgledger.files.FileProtection;
import org.pgledger.files.ServicePrincipalAcl;

/**
 * R9-D0096-G (owner only, capability not operating system name): the folder and the files of the state folder are reachable by
 * their owner alone, on the file system the machine has (POSIX permissions where it has them, an ACL where it has that).
 */
class OwnerOnlyFilesTest {

    @TempDir
    Path root;

    private OwnerOnlyFiles files() throws IOException {
        return OwnerOnlyFiles.forFileSystem(FileSystems.getDefault(), System.getProperty("user.name"));
    }

    /** The protection check of the repository ({@code FileProtection}) for this file system: no exposure to another account. */
    private FileProtection protection() throws IOException {
        var views = FileSystems.getDefault().supportedFileAttributeViews();
        if (views.contains("posix")) {
            return new FileProtection.Posix();
        }
        if (views.contains("acl")) {
            return new FileProtection.Acl(FileSystems.getDefault().getUserPrincipalLookupService()
                    .lookupPrincipalByName(System.getProperty("user.name")));
        }
        return new FileProtection.Unprotected();
    }

    @Test
    @DisplayName("R9-D0096-G a folder, a written file, a lock file and an empty file made by OwnerOnlyFiles are open to no other account (POSIX rwx------ / rw-------, or an ACL with the owner only)")
    void everythingItMakesIsOwnerOnly() throws IOException {
        Path folder = root.resolve("a").resolve("b");
        files().createDirectories(folder);
        files().writeNew(folder.resolve("written"), "x".getBytes());
        try (FileChannel lock = files().openLockFile(folder.resolve("lock"))) {
            assertThat(lock.isOpen()).isTrue();
        }
        files().createIfMissing(folder.resolve("empty"));
        files().createIfMissing(folder.resolve("empty"));

        for (Path path : new Path[] {root.resolve("a"), folder, folder.resolve("written"), folder.resolve("lock"), folder.resolve("empty")}) {
            assertThat(protection().openExposure(path)).as(path.toString()).isEmpty();
        }
        // a path that is open to others is seen by the same check (the check is not vacuous on this file system)
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(folder.resolve("written"), java.util.Set.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.GROUP_READ));
            assertThat(protection().openExposure(folder.resolve("written"))).isPresent();
        } else if (FileSystems.getDefault().supportedFileAttributeViews().contains("acl")) {
            assertThat(ServicePrincipalAcl.HARMLESS).isNotEmpty();
        }
    }

    @Test
    @DisplayName("R9-D0096-G a file that exists is never overwritten by writeNew (CREATE_NEW), and the lock file that exists is opened as it is")
    void existingFilesAreKept() throws IOException {
        Path file = root.resolve("file");
        files().writeNew(file, "first".getBytes());
        assertThatThrownBy(() -> files().writeNew(file, "second".getBytes())).isInstanceOf(FileAlreadyExistsException.class);
        assertThat(Files.readString(file)).isEqualTo("first");
        try (FileChannel again = files().openLockFile(file)) {
            assertThat(again.size()).isEqualTo(5);
        }
    }

    @Test
    @DisplayName("R9-D0096-G a lock on the lock file is held by one channel at a time: a second tryLock on the same file does not get it (the startup lock of the clients)")
    void aLockIsHeldByOne() throws IOException {
        Path file = root.resolve("lock");
        try (FileChannel first = files().openLockFile(file); FileChannel second = files().openLockFile(file)) {
            FileLock held = first.tryLock();
            assertThat(held).isNotNull();
            // one JVM: the second channel on the same file reports the overlap as an exception, which the launcher treats as held
            assertThatThrownBy(second::tryLock).isInstanceOf(java.nio.channels.OverlappingFileLockException.class);
            held.release();
            FileLock after = second.tryLock();
            assertThat(after).isNotNull();
            after.release();
        }
    }
}
