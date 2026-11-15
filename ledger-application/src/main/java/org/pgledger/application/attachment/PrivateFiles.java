package org.pgledger.application.attachment;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.FileSystem;
import java.nio.file.StandardOpenOption;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalNotFoundException;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;
import org.springframework.stereotype.Component;

/**
 * The one place that makes what only the service account may use, and reads it: the attachment directory, its sub folders and
 * files, the key directory and the key files. <b>Creation is owner-only</b>, chosen by what the file system supports and never by the
 * name of the operating system: POSIX permissions {@code rwx------} (directory) and {@code rw-------} (file), or on a file system
 * with ACLs an ACL with one entry for the account of this process; a file system with neither view cannot hold a private file and
 * is refused for a creation.
 *
 * <p><b>Reading and using what exists does not look at who may reach it.</b> The personal edition trusts the whole PC: broad
 * permissions (group and other bits, an ACL entry for Users or Authenticated Users, which a folder on a data volume or under the
 * root of the system drive inherits by default on Windows), an owner that is not the account of this process and a link are all
 * accepted, because a refusal at the start would block a personal user who did nothing wrong. Only what is not of the expected
 * kind is refused, with ARTIFACT_STORAGE_UNSAFE: a path that is not a regular file where a file belongs, or not a directory where a
 * folder belongs (a link is followed to what it points at). The checks of the owner and of the permissions that this class once
 * made at every read were removed with the rule; there is no setting to bring them back.
 *
 * <p>The module of the command line has a store of secrets with the same rule ({@code SecretStore}); the server cannot depend on
 * that module, so the attachment storage has its own copy of the rule, here only.
 */
@Component
@ConditionalOnAttachments
public class PrivateFiles {

    /**
     * What another principal may hold without reaching the content: its attributes, its ACL, the wait for the handle. Used only by
     * {@link #settle}, right after a creation, to find the entries that the parent handed down.
     */
    private static final Set<AclEntryPermission> HARMLESS = EnumSet.of(AclEntryPermission.READ_ATTRIBUTES,
            AclEntryPermission.READ_ACL, AclEntryPermission.SYNCHRONIZE);
    private static final Set<PosixFilePermission> DIRECTORY_MODE = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_MODE = PosixFilePermissions.fromString("rw-------");

    /** The attribute of the Windows file system provider that gives a new file or directory its ACL. */
    private record AclAttribute(List<AclEntry> value) implements FileAttribute<List<AclEntry>> {

        @Override
        public String name() {
            return "acl:acl";
        }
    }

    /**
     * The attributes a new directory or file is created with, so that it is private from its first moment.
     *
     * @param near      a path on the file system that the new one is made on
     * @param directory true for a directory
     */
    public FileAttribute<?>[] attributesFor(Path near, boolean directory) {
        FileSystem fileSystem = near.getFileSystem();
        Set<String> views = fileSystem.supportedFileAttributeViews();
        if (views.contains("posix")) {
            return new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(directory ? DIRECTORY_MODE : FILE_MODE)};
        }
        if (views.contains("acl")) {
            return new FileAttribute<?>[] {new AclAttribute(ownerOnly(processUser(fileSystem), directory))};
        }
        throw unsafe("the file system has neither POSIX permissions nor ACLs");
    }

    /**
     * Opens the file for reading and writing and creates it, private from its first moment, when it is missing (the creation is one
     * atomic step of the file system, so of two first requests one creates it and the other opens it). A path that fails
     * {@link #requireFile} (not a regular file) is closed and refused; a link is followed. The caller closes the channel.
     */
    public FileChannel openReadWrite(Path file) throws IOException {
        Set<OpenOption> options = Set.of(StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        FileChannel channel = FileChannel.open(file, options, attributesFor(file, false));
        try {
            settle(file);
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
        } catch (IOException failure) {
            throw new InfrastructureException(InfrastructureCode.ARTIFACT_STORAGE_IO_ERROR,
                    List.of(failure.getClass().getSimpleName()), failure);
        }
    }

    /**
     * An ACL that was merged with what the parent hands down is replaced by the owner-only one right after creation, before
     * anything is written: the only place where a protection is changed after creation, and only to the narrow one.
     */
    private void settle(Path path) throws IOException {
        FileSystem fileSystem = path.getFileSystem();
        if (fileSystem.supportedFileAttributeViews().contains("acl") && !fileSystem.supportedFileAttributeViews().contains("posix")) {
            UserPrincipal user = processUser(fileSystem);
            if (opensToOthers(path, user)) {
                Files.getFileAttributeView(path, AclFileAttributeView.class).setAcl(ownerOnly(user, Files.isDirectory(path)));
            }
        }
    }

    private static boolean opensToOthers(Path path, UserPrincipal user) throws IOException {
        List<AclEntry> acl = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).getAcl();
        if (acl.isEmpty()) {
            return true;
        }
        for (AclEntry entry : acl) {
            if (entry.type() == AclEntryType.ALLOW && !Objects.equals(entry.principal(), user) && !isAdministrative(entry.principal())
                    && !HARMLESS.containsAll(entry.permissions())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAdministrative(UserPrincipal principal) {
        String name = principal.getName();
        return name.endsWith("\\SYSTEM") || name.endsWith("\\Administrators");
    }

    private static List<AclEntry> ownerOnly(UserPrincipal owner, boolean directory) {
        Set<AclEntryFlag> flags = directory ? EnumSet.of(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT)
                : EnumSet.noneOf(AclEntryFlag.class);
        return List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class)).setFlags(flags).build());
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
