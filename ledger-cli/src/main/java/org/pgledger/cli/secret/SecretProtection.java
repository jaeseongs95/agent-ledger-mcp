package org.pgledger.cli.secret;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.pgledger.application.attachment.ServiceAccountAccess;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.FailureException;

/**
 * How the secret directory and its files are made usable by their owner alone on this file system. The file system decides
 * by the attribute views it supports, never the name of the operating system (rule ARC-033):
 *
 * <ul>
 * <li>{@link Posix}: the permissions {@code rwx------} (directory) and {@code rw-------} (file), given at creation; a path
 *     that was just created with any group or other permission is refused ({@link #settle}).
 * <li>{@link Acl} ({@link AclFileAttributeView}, the Windows file systems): an ACL with two ALLOW entries and nothing inherited,
 *     given at creation (the {@code acl:acl} attribute of the Windows provider): the owner (the installing user) with
 *     everything and the service account LocalService with read ({@link ServiceAccountAccess}); what the parent hands down is
 *     replaced by that ACL right after creation ({@link #settle}). Why the service account reads: the services run as
 *     LocalService and must read the secrets that the installing user stores, and the installation has no step that hands the
 *     secrets over (no change of owner, no privilege to take ownership). SYSTEM and Administrators are not exempt: an
 *     administrator can take ownership of any file whatever its ACL, and an entry for them would add exposure only. The key
 *     store of the hook ({@code ledger-client-hook}, {@code SessionKeyStore}) keeps the owner alone: its keys are read by the
 *     owner's own processes. Reference:
 *     https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/attribute/AclFileAttributeView.html
 * </ul>
 *
 * <p>The protection is made and verified for what this process <b>creates</b>; an existing path is never judged by it (the
 * personal edition trusts the whole PC, and a folder on a data volume inherits Authenticated Users on Windows by default).
 * Reading an existing secret asks only for the kind of the path ({@link SecretStore}).
 */
public sealed interface SecretProtection {

    /** The attributes a new directory is created with. */
    FileAttribute<?>[] directoryAttributes();

    /** The attributes a new file is created with. */
    FileAttribute<?>[] fileAttributes();

    /**
     * Refuses a path that another principal can use. It judges what this process has just created ({@link #settle}), never a
     * path that existed before.
     *
     * @throws FailureException LOOSE_PERMISSIONS naming the path and what is open
     */
    void requireOwnerOnlyAccess(Path path) throws IOException;

    /** For a path this process has just created, before a byte of a secret is written: makes the protection certain. */
    default void settle(Path path) throws IOException {
        requireOwnerOnlyAccess(path);
    }

    /**
     * The protection of the file system of the secret directory.
     *
     * @throws FailureException NON_POSIX_FILESYSTEM naming the directory when the file system has neither POSIX
     *         permissions nor ACLs
     */
    static SecretProtection of(Path directory, ProcessUser processUser) throws IOException {
        FileSystem fileSystem = directory.getFileSystem();
        Set<String> views = fileSystem.supportedFileAttributeViews();
        if (views.contains("posix")) {
            return new Posix();
        }
        if (views.contains("acl")) {
            return new Acl(processUser.current(fileSystem), ServiceAccountAccess.principal(fileSystem));
        }
        throw new FailureException(ErrorCode.NON_POSIX_FILESYSTEM, directory.toString());
    }

    /** POSIX permissions. */
    record Posix() implements SecretProtection {

        private static final Set<PosixFilePermission> DIRECTORY_MODE = PosixFilePermissions.fromString("rwx------");
        private static final Set<PosixFilePermission> FILE_MODE = PosixFilePermissions.fromString("rw-------");
        private static final Set<PosixFilePermission> GROUP_AND_OTHERS = PosixFilePermissions.fromString("---rwxrwx");

        @Override
        public FileAttribute<?>[] directoryAttributes() {
            return new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(DIRECTORY_MODE)};
        }

        @Override
        public FileAttribute<?>[] fileAttributes() {
            return new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(FILE_MODE)};
        }

        @Override
        public void requireOwnerOnlyAccess(Path path) throws IOException {
            Set<PosixFilePermission> mode = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
            if (!Collections.disjoint(mode, GROUP_AND_OTHERS)) {
                throw new FailureException(ErrorCode.LOOSE_PERMISSIONS, path.toString(), PosixFilePermissions.toString(mode));
            }
        }
    }

    /** The {@code acl:acl} attribute of the Windows file system provider: the DACL a new file or directory gets. */
    record AclAttribute(List<AclEntry> value) implements FileAttribute<List<AclEntry>> {

        @Override
        public String name() {
            return "acl:acl";
        }
    }

    /**
     * An ACL with the entry of the owner (the account of this process, which creates the directory and the files) and the entry of
     * the service account (read).
     */
    record Acl(UserPrincipal owner, UserPrincipal service) implements SecretProtection {

        /** What another principal may hold without reaching a secret: its attributes, its ACL, the wait for the handle. */
        private static final Set<AclEntryPermission> HARMLESS = EnumSet.of(AclEntryPermission.READ_ATTRIBUTES,
                AclEntryPermission.READ_ACL, AclEntryPermission.SYNCHRONIZE);

        /** The owner with everything and the service account with read; a directory's entries are inherited by what it holds. */
        private List<AclEntry> ownerAndService(boolean directory) {
            return ServiceAccountAccess.ownerAndService(owner, service, directory);
        }

        @Override
        public FileAttribute<?>[] directoryAttributes() {
            return new FileAttribute<?>[] {new AclAttribute(ownerAndService(true))};
        }

        @Override
        public FileAttribute<?>[] fileAttributes() {
            return new FileAttribute<?>[] {new AclAttribute(ownerAndService(false))};
        }

        @Override
        public void requireOwnerOnlyAccess(Path path) throws IOException {
            whatIsOpen(path).ifPresent(open -> {
                throw new FailureException(ErrorCode.LOOSE_PERMISSIONS, path.toString(), open);
            });
        }

        /** What the ACL of the path opens to another principal than the owner and the service account (read): a query, not a failure. */
        private Optional<String> whatIsOpen(Path path) throws IOException {
            List<AclEntry> acl = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                    .getAcl();
            if (acl.isEmpty()) {
                // an ACL without any entry grants the owner nothing, and a NULL DACL (which grants everybody everything)
                // may be reported the same way: neither is the "owner alone" that was asked for
                return Optional.of("an ACL without an entry");
            }
            for (AclEntry entry : acl) {
                // only an ALLOW entry grants anything; DENY, AUDIT and ALARM entries do not
                boolean granting = entry.type() == AclEntryType.ALLOW && !Objects.equals(entry.principal(), owner)
                        && !ServiceAccountAccess.isServiceRead(entry, service) && !HARMLESS.containsAll(entry.permissions());
                if (granting) {
                    return Optional.of("an ACL that allows " + entry.principal().getName());
                }
            }
            return Optional.empty();
        }

        /**
         * The ACL given at creation may have been merged with the entries the parent hands down: when it was, it is replaced
         * by the ACL of the owner and the service account before a secret is written. This is the one place where a protection is changed after
         * creation, and only ever to the narrow one.
         */
        @Override
        public void settle(Path path) throws IOException {
            // A process with an elevated token (the installer) creates objects that the group Administrators owns, which is
            // Windows' default for its members; the owner check wants the account of the process, so it takes the ownership
            // of what it has just created (it may: the creator's entry holds the right to change the owner).
            if (!Objects.equals(Files.getOwner(path, LinkOption.NOFOLLOW_LINKS), owner)) {
                Files.setOwner(path, owner);
            }
            if (whatIsOpen(path).isPresent()) {
                Files.getFileAttributeView(path, AclFileAttributeView.class).setAcl(ownerAndService(Files.isDirectory(path)));
            }
            requireOwnerOnlyAccess(path);
        }
    }
}
