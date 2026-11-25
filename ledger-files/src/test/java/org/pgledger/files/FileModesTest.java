// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R9-D0092: the modes of the files of the product, decided by the kind of the file system in this module only. The creation
 * attributes of each kind are tested on every host ({@link FileSystemWithViews}); the changes of a file are tested on the file
 * system of the host, which is one kind or the other.
 */
class FileModesTest {

    private static final FileSystem HOST = FileSystems.getDefault();

    @Test
    @DisplayName("R9-D0092 a temporary file is made with its mode from its first moment on a POSIX file system, and with no attribute (the protection of its folder) on a file system with ACLs or with none")
    void creationFollowsTheKind() {
        FileAttribute<?>[] linux = FileModes.creation(new FileSystemWithViews(HOST, FileSystemWithViews.LINUX), FileModes.PRIVATE);
        assertThat(linux).hasSize(1);
        assertThat(linux[0].name()).isEqualTo("posix:permissions");
        assertThat(linux[0].value()).isEqualTo(PosixFilePermissions.fromString("rw-------"));

        assertThat(FileModes.creation(new FileSystemWithViews(HOST, FileSystemWithViews.WINDOWS), FileModes.PRIVATE)).isEmpty();
        assertThat(FileModes.creation(new FileSystemWithViews(HOST, FileSystemWithViews.NEITHER), FileModes.PRIVATE)).isEmpty();
    }

    @Test
    @DisplayName("R9-D0092 the modes: private, read-only, group-readable and world-readable are the ones the product used")
    void theModesAreTheOnesTheProductUsed() {
        assertThat(FileModes.PRIVATE).isEqualTo("rw-------");
        assertThat(FileModes.PRIVATE_READ_ONLY).isEqualTo("r--------");
        assertThat(FileModes.GROUP_READABLE).isEqualTo("rw-r-----");
        assertThat(FileModes.WORLD_READABLE).isEqualTo("rw-r--r--");
    }

    @Test
    @DisplayName("R9-D0092 a file is made read only and writable again on the file system of the host: the mode r-------- and rw------- where there are modes, the read-only flag where there are DOS attributes")
    void aFileIsMadeReadOnlyAndWritableAgain(@TempDir Path temp) throws IOException {
        Path file = Files.createFile(temp.resolve("attachment"));

        FileModes.makeReadOnly(file);
        assertThat(readOnly(file)).isTrue();

        FileModes.makeWritable(file);
        assertThat(readOnly(file)).isFalse();
    }

    @Test
    @DisplayName("R9-D0092 apply gives an existing file its mode where there are modes and changes nothing elsewhere")
    void applyGivesTheMode(@TempDir Path temp) throws IOException {
        Path file = Files.createFile(temp.resolve("registration"));

        FileModes.apply(file, FileModes.GROUP_READABLE);

        if (FileKind.of(HOST) == FileKind.POSIX) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-r-----");
        } else {
            assertThat(Files.exists(file)).isTrue();
        }
    }

    @Test
    @DisplayName("ARC-BND-06 apply, makeReadOnly and makeWritable refuse a symbolic link (including a dangling one) and never change the file it points to")
    void aLinkIsNeverFollowed(@TempDir Path temp) throws IOException {
        Assumptions.assumeTrue(FileKind.of(HOST) == FileKind.POSIX, "modes exist on a POSIX file system only");
        Path target = Files.createFile(temp.resolve("target"), FileModes.creation(HOST, FileModes.WORLD_READABLE));
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString(FileModes.WORLD_READABLE));
        Path link = Files.createSymbolicLink(temp.resolve("link"), target);
        Path dangling = Files.createSymbolicLink(temp.resolve("dangling"), temp.resolve("missing"));
        Set<PosixFilePermission> privateMode = PosixFilePermissions.fromString(FileModes.PRIVATE);

        for (Path candidate : new Path[] { link, dangling }) {
            for (IoAction change : new IoAction[] {
                    () -> FileModes.apply(candidate, FileModes.PRIVATE),
                    () -> FileModes.apply(candidate, privateMode),
                    () -> FileModes.makeReadOnly(candidate),
                    () -> FileModes.makeWritable(candidate) }) {
                assertThatThrownBy(change::run).isInstanceOf(FileSystemException.class);
                assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(target, LinkOption.NOFOLLOW_LINKS)))
                        .isEqualTo(FileModes.WORLD_READABLE);
            }
        }
    }

    @FunctionalInterface
    private interface IoAction {
        void run() throws IOException;
    }

    private static boolean readOnly(Path file) throws IOException {
        if (FileKind.of(HOST) == FileKind.POSIX) {
            return "r--------".equals(PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        }
        return (Boolean) Files.getAttribute(file, "dos:readonly");
    }
}
