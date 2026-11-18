// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.hook.keys;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.UserPrincipal;
import java.util.Set;
import org.pgledger.files.FileProtection;
import org.pgledger.hook.keys.SessionKeyException.Reason;

/**
 * How a key folder and a key file are protected on a file system, and how that is checked. Chosen by the capability of
 * the file system and never by the operating system name:
 * <ul>
 * <li>POSIX attribute view ({@link Posix}): a folder gets {@code rwx------} and a file {@code rw-------}, both given at
 *     creation and never widened; a path that was just created with any group or other permission bit is refused
 *     ({@link Reason#TOO_OPEN}).
 * <li>ACL attribute view ({@link Acl}; {@link AclFileAttributeView}, the Windows file systems;
 *     https://docs.oracle.com/en/java/javase/25/docs/api/java/nio/file/attribute/AclFileAttributeView.html): a folder
 *     or file is given, at creation (the {@code acl:acl} attribute of
 *     {@link Files#createDirectories(Path, FileAttribute[])} and {@link Files#newByteChannel(Path, java.util.Set,
 *     FileAttribute[])}), an ACL with one ALLOW entry that grants the current user everything and nothing else; the ACL
 *     is read back before any key material is written, and replaced by that ACL if the system added inherited entries.
 *     A path just created whose ACL has no entry at all (an empty ACL; a NULL DACL may read the same) or allows anything
 *     beyond reading its attributes and its ACL to a principal other than the current user is replaced by that ACL
 *     ({@link #settle}). SYSTEM and Administrators are not exempt: an administrator can take ownership of any file
 *     whatever its ACL, and an entry for them would add nothing but exposure. The current user is the account name of the
 *     setting {@code ledger.hook.user-name} ({@code user.name}, see {@link KeyStoreProperties}), resolved by the file
 *     system's {@code UserPrincipalLookupService}; it is needed for the ACL entry only.
 * <li>Neither view ({@link Unprotected}): nothing is set, the default protection of the user profile applies.
 * </ul>
 *
 * <p>The protection is made, and verified, for what the hook <b>creates</b>. An existing folder or key file is never judged
 * by it: its owner, its permission bits and its ACL are not looked at, and a link is followed (the personal edition trusts
 * the whole PC; a folder on a data volume or under the root of the system drive inherits Authenticated Users by default on
 * Windows, so a refusal at the first call would block a personal user). Reading asks only that the key file is a regular
 * file, of a plausible size and in the stored format ({@link KeyFileReader}, {@link SessionKeyFile}).
 */
sealed interface Protection permits Protection.Posix, Protection.Acl, Protection.Unprotected {

    /** The attributes that a folder is created with. */
    FileAttribute<?>[] folderAttributes();

    /** The attributes that a key file is created with. */
    FileAttribute<?>[] fileAttributes();

    /**
     * Refuses a path that another principal can use; changes nothing. It judges what this process has just created
     * ({@link #settle}), never a folder or a file that existed before.
     */
    void requireOwnerOnly(Path path) throws IOException;

    /** For a path this class has just created: the same check, after the protection was made certain. */
    default void settle(Path path) throws IOException {
        requireOwnerOnly(path);
    }

    /**
     * The protection of the file system for the account {@code userName}.
     *
     * @throws SessionKeyException with {@link Reason#OWNER_UNKNOWN} where the file system has ACLs and no account name was given
     * @throws IOException         if the account is not known to the file system
     */
    static Protection of(FileSystem system, String userName) throws IOException {
        Set<String> views = system.supportedFileAttributeViews();
        if (views.contains("posix")) {
            return new Posix();
        }
        if (!views.contains("acl")) {
            return new Unprotected();
        }
        if (userName == null || userName.isBlank()) {
            throw new SessionKeyException(Reason.OWNER_UNKNOWN);
        }
        return new Acl(system.getUserPrincipalLookupService().lookupPrincipalByName(userName));
    }

    /** Neither view: nothing to set and nothing to check. */
    record Unprotected() implements Protection {

        private static final FileProtection.Unprotected DELEGATE = new FileProtection.Unprotected();

        @Override
        public FileAttribute<?>[] folderAttributes() {
            return DELEGATE.directoryAttributes();
        }

        @Override
        public FileAttribute<?>[] fileAttributes() {
            return DELEGATE.fileAttributes();
        }

        @Override
        public void requireOwnerOnly(Path path) {
            // no POSIX and no ACL view: the default protection of the user profile applies
        }
    }

    /** The POSIX attribute view: modes (checked with {@code NOFOLLOW_LINKS} via {@code ledger-files}). */
    record Posix() implements Protection {

        private static final FileProtection.Posix DELEGATE = new FileProtection.Posix();

        @Override
        public FileAttribute<?>[] folderAttributes() {
            return DELEGATE.directoryAttributes();
        }

        @Override
        public FileAttribute<?>[] fileAttributes() {
            return DELEGATE.fileAttributes();
        }

        @Override
        public void requireOwnerOnly(Path path) throws IOException {
            DELEGATE.openExposure(path).ifPresent(_ -> {
                throw new SessionKeyException(Reason.TOO_OPEN);
            });
        }
    }

    /** The ACL attribute view: entries (inspected and settled with {@code NOFOLLOW_LINKS} via {@code ledger-files}). */
    record Acl(UserPrincipal current) implements Protection {

        private FileProtection.Acl delegate() {
            return new FileProtection.Acl(current);
        }

        @Override
        public FileAttribute<?>[] folderAttributes() {
            return delegate().directoryAttributes();
        }

        @Override
        public FileAttribute<?>[] fileAttributes() {
            return delegate().fileAttributes();
        }

        @Override
        public void requireOwnerOnly(Path path) throws IOException {
            delegate().openExposure(path).ifPresent(_ -> {
                throw new SessionKeyException(Reason.TOO_OPEN);
            });
        }

        /** The ACL given at creation may have been merged with entries inherited from the parent: make it certain. */
        @Override
        public void settle(Path path) throws IOException {
            delegate().settle(path, true).ifPresent(_ -> {
                throw new SessionKeyException(Reason.TOO_OPEN);
            });
        }
    }
}
