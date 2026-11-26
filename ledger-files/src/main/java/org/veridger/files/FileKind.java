// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.files;

import java.nio.file.FileSystem;
import java.util.Set;

/**
 * The kind of file system a process works on, decided by the attribute views the file system supports and never by the name of the
 * operating system (rule ARC-033). This is the one place that asks {@link FileSystem#supportedFileAttributeViews()}: the file
 * protection ({@link FileProtection#select}), the modes of files ({@link FileModes}) and the defaults of a host of the command line
 * tool take the answer from here, so the question is not put, and not answered differently, in more than one place.
 */
public enum FileKind {

    /** POSIX permissions (a Linux file system): owner, group and mode. */
    POSIX,
    /** ACLs and no POSIX permissions (a Windows file system). */
    ACL,
    /** Neither of the two. */
    OTHER;

    /** The kind of {@code fileSystem}: POSIX when it has the {@code posix} view, else ACL when it has {@code acl}, else other. */
    public static FileKind of(FileSystem fileSystem) {
        Set<String> views = fileSystem.supportedFileAttributeViews();
        if (views.contains("posix")) {
            return POSIX;
        }
        return views.contains("acl") ? ACL : OTHER;
    }

    /** Whether {@code fileSystem} has the DOS attribute view (the read-only flag of a file), which a POSIX one is asked about first. */
    static boolean hasDosView(FileSystem fileSystem) {
        return fileSystem.supportedFileAttributeViews().contains("dos");
    }
}
