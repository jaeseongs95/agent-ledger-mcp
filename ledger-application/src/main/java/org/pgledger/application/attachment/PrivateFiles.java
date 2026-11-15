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
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;
import org.springframework.stereotype.Component;

/**
 * The one place that makes and checks what only the service account may use: the attachment directory, its sub folders and files,
 * the key directory and the key files. The protection is chosen by what the file system supports, never by the name of the
 * operating system: POSIX permissions {@code rwx------} (directory) and {@code rw-------} (file), or on a file system with ACLs
 * an ACL with one entry for the account of this process (an entry for SYSTEM and Administrators is accepted: an administrator can
 * take ownership of any file whatever its ACL). A link, a path of another type, another owner (POSIX) and any access of another
 * account are refused with ATTACHMENT_STORAGE_UNSAFE, never repaired. A file system with neither view is refused.
 *
 * <p>The module of the command line has a store of secrets with the same rule ({@code SecretStore}); the server cannot depend on
 * that module, so the attachment storage has its own copy of the rule, here only.
 */
@Component
@ConditionalOnAttachments
public class PrivateFiles {

    /** What another principal may hold without reaching the content: its attributes, its ACL, the wait for the handle. */
    private static final Set<AclEntryPermission> HARMLESS = EnumSet.of(AclEntryPermission.READ_ATTRIBUTES,
            AclEntryPermission.READ_ACL, AclEntryPermission.SYNCHRONIZE);
    private static final Set<PosixFilePermission> DIRECTORY_MODE = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_MODE = PosixFilePermissions.fromString("rw-------");
    private static final Set<PosixFilePermission> GROUP_AND_OTHERS = PosixFilePermissions.fromString("---rwxrwx");

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
     * atomic step of the file system, so of two first requests one creates it and the other opens it). A file that fails
     * {@link #requireFile} is closed and refused. The caller closes the channel.
     */
    public FileChannel openReadWrite(Path file) throws IOException {
        Set<OpenOption> options = Set.of(StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
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

    /** Opens an existing private file for reading. The caller closes the channel. */
    public FileChannel openRead(Path file) throws IOException {
        requireFile(file);
        return FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
    }

    /** Creates a new empty private file (it must not exist). */
    public void createFile(Path file) throws IOException {
        Files.createFile(file, attributesFor(file, false));
        settle(file);
        requireFile(file);
    }

    /** Creates the directory (its parent has to exist) when it is missing, and requires it to be private. */
    public void ensureDirectory(Path directory) {
        try {
            if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectory(directory, attributesFor(directory, true));
                settle(directory);
            }
        } catch (IOException failure) {
            throw new InfrastructureException(InfrastructureCode.ATTACHMENT_STORAGE_FAILED,
                    List.of(failure.getClass().getSimpleName()), failure);
        }
        requireDirectory(directory);
    }

    /** Requires an existing directory (no link) that only this account may use. */
    public void requireDirectory(Path directory) {
        requirePrivate(directory, true);
    }

    /** Requires an existing regular file (no link) that only this account may use. */
    public void requireFile(Path file) {
        requirePrivate(file, false);
    }

    private void requirePrivate(Path path, boolean directory) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (directory ? !attributes.isDirectory() : !attributes.isRegularFile()) {
                throw unsafe(directory ? "a directory is expected, not a link or a file" : "a regular file is expected, not a link or a directory");
            }
            FileSystem fileSystem = path.getFileSystem();
            Set<String> views = fileSystem.supportedFileAttributeViews();
            if (views.contains("posix")) {
                UserPrincipal owner = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS);
                if (!Objects.equals(owner, processUser(fileSystem))) {
                    throw unsafe("the owner is not the account of this process");
                }
                Set<PosixFilePermission> mode = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
                if (!Collections.disjoint(mode, GROUP_AND_OTHERS)) {
                    throw unsafe("group or others have access");
                }
            } else if (views.contains("acl")) {
                if (opensToOthers(path, processUser(fileSystem))) {
                    throw unsafe("another account has access");
                }
            } else {
                throw unsafe("the file system has neither POSIX permissions nor ACLs");
            }
        } catch (IOException failure) {
            throw new InfrastructureException(InfrastructureCode.ATTACHMENT_STORAGE_FAILED,
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
        } catch (IOException failure) {
            throw new InfrastructureException(InfrastructureCode.ATTACHMENT_STORAGE_FAILED,
                    List.of(failure.getClass().getSimpleName()), failure);
        }
    }

    private static InfrastructureException unsafe(String what) {
        return new InfrastructureException(InfrastructureCode.ATTACHMENT_STORAGE_UNSAFE, List.of(what));
    }
}
