// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.cli.secret;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.UserPrincipal;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.FailureException;
import org.pgledger.files.FileProtection;
import org.pgledger.files.ServicePrincipalAcl;

/**
 * How the secret directory and its files are made usable by their owner alone on this file system. The file system decides
 * by the attribute views it supports, never the name of the operating system (rule ARC-033):
 *
 * <ul>
 * <li>{@link Posix}: the permissions {@code rwx------} (directory) and {@code rw-------} (file), given at creation; a path
 *     that was just created with any group or other permission is refused ({@link #settle}).
 * <li>{@link Acl} ({@link AclFileAttributeView}, the Windows file systems): an ACL with two ALLOW entries, given at creation
 *     (the {@code acl:acl} attribute of the Windows provider; no inherited entry is put in, the DACL is not marked protected): the owner (the installing user) with
 *     everything and the service account LocalService with read ({@link ServicePrincipalAcl}); what the parent hands down is
 *     replaced by that ACL right after creation ({@link #settle}). Why the service account reads: the services run as
 *     LocalService and must read the secrets that the installing user stores, and the installation has no step that hands the
 *     secrets over (the installing user stays the owner, no privilege to take ownership is used). SYSTEM and Administrators are not exempt: an
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
        FileProtection chosen = FileProtection.select(directory.getFileSystem(),
                fileSystem -> new FileProtection.Acl(processUser.current(fileSystem), ServicePrincipalAcl.principal(fileSystem),
                        false))
                .orElseThrow(() -> new FailureException(ErrorCode.NON_POSIX_FILESYSTEM, directory.toString()));
        return switch (chosen) {
            case FileProtection.Acl acl -> new Acl(acl.owner(), acl.reader());
            default -> new Posix();
        };
    }

    /** POSIX permissions. */
    record Posix() implements SecretProtection {

        private static final FileProtection.Posix DELEGATE = new FileProtection.Posix();

        @Override
        public FileAttribute<?>[] directoryAttributes() {
            return DELEGATE.directoryAttributes();
        }

        @Override
        public FileAttribute<?>[] fileAttributes() {
            return DELEGATE.fileAttributes();
        }

        @Override
        public void requireOwnerOnlyAccess(Path path) throws IOException {
            DELEGATE.openExposure(path).ifPresent(exposure -> {
                throw new FailureException(ErrorCode.LOOSE_PERMISSIONS, path.toString(), describe(exposure));
            });
        }
    }

    /**
     * An ACL with the entry of the owner (the account of this process, which creates the directory and the files) and the entry of
     * the service account (read).
     */
    record Acl(UserPrincipal owner, UserPrincipal service) implements SecretProtection {

        private FileProtection.Acl delegate() {
            return new FileProtection.Acl(owner, service, false);
        }

        @Override
        public FileAttribute<?>[] directoryAttributes() {
            return delegate().directoryAttributes();
        }

        @Override
        public FileAttribute<?>[] fileAttributes() {
            return delegate().fileAttributes();
        }

        @Override
        public void requireOwnerOnlyAccess(Path path) throws IOException {
            delegate().openExposure(path).ifPresent(exposure -> {
                throw new FailureException(ErrorCode.LOOSE_PERMISSIONS, path.toString(), describe(exposure));
            });
        }

        @Override
        public void settle(Path path) throws IOException {
            delegate().settle(path, true).ifPresent(exposure -> {
                throw new FailureException(ErrorCode.LOOSE_PERMISSIONS, path.toString(), describe(exposure));
            });
        }
    }

    private static String describe(FileProtection.Exposure exposure) {
        return switch (exposure) {
            case FileProtection.Exposure.EmptyAcl _ -> "an ACL without an entry";
            case FileProtection.Exposure.PosixMode(var mode) -> mode;
            case FileProtection.Exposure.AclPrincipal(var principal) -> "an ACL that allows " + principal;
        };
    }
}
