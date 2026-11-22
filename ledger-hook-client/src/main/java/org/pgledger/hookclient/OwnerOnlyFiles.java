// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.hookclient;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.UserPrincipal;
import java.util.Set;
import org.pgledger.files.FileProtection;

/**
 * Creates the folders and files of the state folder so that no other account can reach them. The protection is the one of
 * {@link FileProtection}, the owner-only code the repository already has for secrets, attachments and session keys, chosen by
 * what the file system can do and never by the name of the operating system: POSIX permissions ({@code rwx------},
 * {@code rw-------}) where the file system has the POSIX view, an ACL with one entry for the current account where it has the
 * ACL view, nothing to set where it has neither (the default protection of the user profile applies).
 *
 * <p>Every new folder and file is given the protection at creation and is checked before any content is written; a path that is
 * still open to others after that is refused. A folder or file that exists is used as it is: who owns it is not looked at (the
 * personal edition trusts the whole PC, as the key store does).
 */
public final class OwnerOnlyFiles {

    private final FileProtection protection;

    public OwnerOnlyFiles(FileProtection protection) {
        this.protection = protection;
    }

    /**
     * The protection of this file system for the account {@code userName}.
     *
     * @throws IOException if the file system has ACLs and the account is not known to it or none was given
     */
    public static OwnerOnlyFiles forFileSystem(FileSystem system, String userName) throws IOException {
        Set<String> views = system.supportedFileAttributeViews();
        if (views.contains("posix")) {
            return new OwnerOnlyFiles(new FileProtection.Posix());
        }
        if (!views.contains("acl")) {
            return new OwnerOnlyFiles(new FileProtection.Unprotected());
        }
        if (userName == null || userName.isBlank()) {
            throw new IOException("the account that owns the state folder is not known");
        }
        UserPrincipal owner = system.getUserPrincipalLookupService().lookupPrincipalByName(userName);
        return new OwnerOnlyFiles(new FileProtection.Acl(owner));
    }

    /** The folder and the parents that are missing, each given the protection at creation. */
    public void createDirectories(Path directory) throws IOException {
        if (Files.isDirectory(directory)) {
            return;
        }
        Files.createDirectories(directory, protection.directoryAttributes());
        require(directory);
    }

    /** A new file with this content; the protection is certain before the first byte is written. */
    public void writeNew(Path file, byte[] content) throws IOException {
        try (FileChannel channel = FileChannel.open(file, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                protection.fileAttributes())) {
            require(file);
            channel.write(ByteBuffer.wrap(content));
        }
    }

    /** The file opened for writing, created owner-only if it is missing: the channel that holds a lock. */
    public FileChannel openLockFile(Path file) throws IOException {
        try {
            FileChannel created = FileChannel.open(file, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                    protection.fileAttributes());
            require(file);
            return created;
        } catch (FileAlreadyExistsException existing) {
            return FileChannel.open(file, StandardOpenOption.WRITE);
        }
    }

    /** An empty file at this path if there is none, created owner-only. */
    public void createIfMissing(Path file) throws IOException {
        if (!Files.exists(file)) {
            try {
                writeNew(file, new byte[0]);
            } catch (FileAlreadyExistsException existing) {
                // another process made it between the check and the creation: it is used as it is
                return;
            }
        }
    }

    private void require(Path path) throws IOException {
        if (protection.settle(path, true).isPresent()) {
            throw new IOException("the state of the resident hook program could not be made private to its owner");
        }
    }
}
