// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.application.attachment;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalNotFoundException;
import java.util.List;
import java.util.Set;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;
import org.pgledger.files.FileProtection;
import org.pgledger.files.ServicePrincipalAcl;
import org.springframework.stereotype.Component;

/**
 * The one place that makes what only the service account may use, and reads it: the attachment directory, its sub folders and
 * files, the key directory and the key files. <b>Creation is owner-only</b>, chosen by what the file system supports and never by the
 * name of the operating system: POSIX permissions {@code rwx------} (directory) and {@code rw-------} (file), or on a file system
 * with ACLs an ACL with the entry of the account of this process (everything) and the entry of the service account LocalService
 * (read; the key files that the installing user made are read by the services, {@link ServicePrincipalAcl}); a file system with
 * neither view cannot hold a private file and is refused for a creation.
 *
 * <p><b>Reading and using what exists does not look at who may reach it.</b> The personal edition trusts the whole PC: broad
 * permissions (group and other bits, an ACL entry for Users or Authenticated Users, which a folder on a data volume or under the
 * root of the system drive inherits by default on Windows), an owner that is not the account of this process and a link are all
 * accepted, because a refusal at the start would block a personal user who did nothing wrong. Only what is not of the expected
 * kind is refused, with ARTIFACT_STORAGE_UNSAFE: a path that is not a regular file where a file belongs, or not a directory where a
 * folder belongs (a link is followed to what it points at). The checks of the owner and of the permissions that this class once
 * made at every read were removed with the rule; there is no setting to bring them back.
 *
 * <p>Delegates POSIX/ACL protection and {@code NOFOLLOW_LINKS} settlement to the shared {@code ledger-files} module
 * ({@link FileProtection}).
 */
@Component
@ConditionalOnAttachments
public class PrivateFiles {

    /**
     * The attributes a new directory or file is created with, so that it is private from its first moment.
     *
     * @param near      a path on the file system that the new one is made on
     * @param directory true for a directory
     */
    public FileAttribute<?>[] attributesFor(Path near, boolean directory) {
        FileProtection protection = protection(near.getFileSystem());
        return directory ? protection.directoryAttributes() : protection.fileAttributes();
    }

    /**
     * Opens the file for reading and writing and creates it, private from its first moment, when it is missing (the creation is one
     * atomic step of the file system, so of two first requests one creates it and the other opens it). A path that fails
     * {@link #requireFile} (not a regular file) is closed and refused; a link is followed. The caller closes the channel.
     */
    public FileChannel openReadWrite(Path file) throws IOException {
        boolean created = false;
        FileChannel channel;
        try {
            channel = FileChannel.open(file,
                    Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.READ, StandardOpenOption.WRITE),
                    attributesFor(file, false));
            created = true;
        } catch (FileAlreadyExistsException existing) {
            channel = FileChannel.open(file, Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE));
        }
        try {
            if (created) {
                settle(file);
            }
            requireFile(file);
            return channel;
        } catch (IOException | InfrastructureException failure) {
            channel.close();
            throw failure;
        }
    }

    /** Opens an existing regular file for reading (a link is followed). The caller closes the channel. */
    public FileChannel openRead(Path file) throws IOException {
        requireFile(file);
        return FileChannel.open(file, StandardOpenOption.READ);
    }

    /** Creates a new empty private file (it must not exist). */
    public void createFile(Path file) throws IOException {
        Files.createFile(file, attributesFor(file, false));
        settle(file);
        requireFile(file);
    }

    /**
     * Creates the directory, private from its first moment, when it is missing, and requires it to be a directory. A missing
     * parent (and its missing ancestors) is made first with the default attributes of the file system: the product makes its own
     * folders, for example the {@code data} folder of a fresh home, and such a folder holds no secret (only the directory of the
     * attachments or of the keys and what is below it is owner-only). An existing folder is used as it is: who else may reach it
     * is not looked at.
     */
    public void ensureDirectory(Path directory) {
        try {
            if (!Files.exists(directory)) {
                Path parent = directory.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.createDirectory(directory, attributesFor(directory, true));
                settle(directory);
            }
        } catch (IOException failure) {
            throw new InfrastructureException(InfrastructureCode.ARTIFACT_STORAGE_IO_ERROR,
                    List.of(failure.getClass().getSimpleName()), failure);
        }
        requireDirectory(directory);
    }

    /** Requires an existing directory (a link is followed to the directory it points at). Who may reach it is not looked at. */
    public void requireDirectory(Path directory) {
        requireKind(directory, true);
    }

    /** Requires an existing regular file (a link is followed to the file it points at). Who may reach it is not looked at. */
    public void requireFile(Path file) {
        requireKind(file, false);
    }

    private void requireKind(Path path, boolean directory) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
            if (directory ? !attributes.isDirectory() : !attributes.isRegularFile()) {
                throw unsafe(directory ? "a directory is expected, not a file" : "a regular file is expected, not a directory");
            }
        } catch (NoSuchFileException | NotDirectoryException missing) {
            // nothing is there, or a file stands in the way of the path: a defined answer, not a failure of the disk
            throw unsafe(directory ? "a directory is expected, but nothing is there" : "a regular file is expected, but nothing is there");
        } catch (IOException failure) {
            throw new InfrastructureException(InfrastructureCode.ARTIFACT_STORAGE_IO_ERROR,
                    List.of(failure.getClass().getSimpleName()), failure);
        }
    }

    private FileProtection protection(FileSystem fileSystem) {
        Set<String> views = fileSystem.supportedFileAttributeViews();
        if (views.contains("posix")) {
            return new FileProtection.Posix();
        }
        if (views.contains("acl")) {
            return new FileProtection.Acl(processUser(fileSystem), serviceAccount(fileSystem), true);
        }
        throw unsafe("the file system has neither POSIX permissions nor ACLs");
    }

    /**
     * Settles newly created protection (POSIX verification or inherited ACL replacement) using
     * {@link LinkOption#NOFOLLOW_LINKS} before bytes are written, refusing any remaining exposure.
     */
    private void settle(Path path) throws IOException {
        FileProtection protection = protection(path.getFileSystem());
        protection.settle(path, false).ifPresent(exposure -> {
            throw unsafe("newly created path is exposed: " + describe(exposure));
        });
    }

    private static String describe(FileProtection.Exposure exposure) {
        return switch (exposure) {
            case FileProtection.Exposure.EmptyAcl _ -> "an ACL without an entry";
            case FileProtection.Exposure.PosixMode(var mode) -> mode;
            case FileProtection.Exposure.AclPrincipal(var principal) -> "an ACL that allows " + principal;
        };
    }

    /** The service account as a principal of the file system. */
    private static UserPrincipal serviceAccount(FileSystem fileSystem) {
        try {
            return ServicePrincipalAcl.principal(fileSystem);
        } catch (UserPrincipalNotFoundException unknown) {
            throw unsafe("the service account is not known to the file system");
        } catch (IOException failure) {
            throw new InfrastructureException(InfrastructureCode.ARTIFACT_STORAGE_IO_ERROR,
                    List.of(failure.getClass().getSimpleName()), failure);
        }
    }

    /** The account of this process (JDK ProcessHandle) as a principal of the file system, so that it compares equal to a file owner. */
    private static UserPrincipal processUser(FileSystem fileSystem) {
        try {
            String name = ProcessHandle.current().info().user()
                    .orElseThrow(() -> unsafe("the account of this process is not known"));
            return fileSystem.getUserPrincipalLookupService().lookupPrincipalByName(name);
        } catch (UserPrincipalNotFoundException unknown) {
            // the account of the process is not known to the file system: an owner-only file cannot be made for it (not a disk failure)
            throw unsafe("the account of this process is not known to the file system");
        } catch (IOException failure) {
            throw new InfrastructureException(InfrastructureCode.ARTIFACT_STORAGE_IO_ERROR,
                    List.of(failure.getClass().getSimpleName()), failure);
        }
    }

    private static InfrastructureException unsafe(String what) {
        return new InfrastructureException(InfrastructureCode.ARTIFACT_STORAGE_UNSAFE, List.of(what));
    }
}
