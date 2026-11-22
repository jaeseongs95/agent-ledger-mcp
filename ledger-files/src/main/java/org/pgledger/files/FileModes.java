// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.files;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * The modes of the files of the product, one place for what depends on the kind of the file system ({@link FileKind}): where the
 * file system has POSIX permissions a file is given its mode, where it has not it is left to the protection of the folder it is
 * in (a Windows file system: the ACL it inherits, and the read-only flag for a file that must not be changed). Every inspection and
 * change of an existing file uses {@link LinkOption#NOFOLLOW_LINKS}.
 */
public final class FileModes {

    /** A file only its owner can read and write. */
    public static final String PRIVATE = "rw-------";
    /** A file only its owner can read: the form of a file that is finished and never changed again. */
    public static final String PRIVATE_READ_ONLY = "r--------";
    /** A file that its owner and its group can read. */
    public static final String GROUP_READABLE = "rw-r-----";
    /** A file that everybody can read and its owner write (the jars and the settings of an installation). */
    public static final String WORLD_READABLE = "rw-r--r--";

    private FileModes() {
    }

    /**
     * The attribute that makes a file or a temporary file {@code mode} from its first moment, so that it never exists with wider
     * permissions; none where the file system has no POSIX permissions.
     */
    public static FileAttribute<?>[] creation(FileSystem fileSystem, String mode) {
        return FileKind.of(fileSystem) == FileKind.POSIX
                ? new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(mode))}
                : new FileAttribute<?>[0];
    }

    /** Gives an existing file {@code mode}; nothing where the file system has no POSIX permissions. */
    public static void apply(Path file, String mode) throws IOException {
        if (FileKind.of(file.getFileSystem()) == FileKind.POSIX) {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(mode));
        }
    }

    /** Makes the file one that cannot be changed: {@link #PRIVATE_READ_ONLY} where there are modes, else the read-only flag. */
    public static void makeReadOnly(Path file) throws IOException {
        change(file, PRIVATE_READ_ONLY, true);
    }

    /** Makes the file one that can be changed again: {@link #PRIVATE} where there are modes, else the read-only flag cleared. */
    public static void makeWritable(Path file) throws IOException {
        change(file, PRIVATE, false);
    }

    private static void change(Path file, String mode, boolean readOnly) throws IOException {
        FileSystem fileSystem = file.getFileSystem();
        if (FileKind.of(fileSystem) == FileKind.POSIX) {
            PosixFileAttributeView posix = Files.getFileAttributeView(file, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (posix != null) {
                posix.setPermissions(PosixFilePermissions.fromString(mode));
            }
        } else if (FileKind.hasDosView(fileSystem)) {
            DosFileAttributeView dos = Files.getFileAttributeView(file, DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (dos != null) {
                dos.setReadOnly(readOnly);
            }
        }
    }
}
