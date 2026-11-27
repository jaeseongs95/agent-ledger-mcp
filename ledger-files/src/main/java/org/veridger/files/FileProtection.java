// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.files;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileOwnerAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Capability-selected file and directory creation protection ({@code posix}, {@code acl}, or unprotected) shared by
 * attachments, secrets, and hook session keys. Every inspection and newly created settlement uses
 * {@link LinkOption#NOFOLLOW_LINKS}.
 */
public sealed interface FileProtection
        permits FileProtection.Unprotected, FileProtection.Posix, FileProtection.Acl {

    /** Exposure found when inspecting a newly created path. */
    sealed interface Exposure permits Exposure.EmptyAcl, Exposure.PosixMode, Exposure.AclPrincipal {

        /** An ACL view reported an empty ACL (grants owner nothing or mirrors a NULL DACL). */
        record EmptyAcl() implements Exposure {
        }

        /** Group or other POSIX bits present on the inspected mode string. */
        record PosixMode(String mode) implements Exposure {
        }

        /** An ACL ALLOW entry grants more than harmless metadata access to another principal. */
        record AclPrincipal(String principalName) implements Exposure {
        }
    }

    /** What a caller makes of an ACL file system: its {@link Acl} (the owner and the reader differ by caller). */
    @FunctionalInterface
    interface AclChoice {

        Acl on(FileSystem fileSystem) throws IOException;
    }

    /**
     * The protection of {@code fileSystem}, chosen by what it supports ({@link FileKind}): {@link Posix} where it has POSIX
     * permissions, else the {@link Acl} that {@code acl} makes where it has ACLs (only then is it asked, because an account is
     * looked up for it), else nothing. The three users of this module (the attachments, the secret store, the key store of the hook)
     * take the choice from here and decide for themselves what a file system with neither view means: the first two refuse it, the
     * hook leaves the protection of the user profile ({@link Unprotected}).
     */
    static Optional<FileProtection> select(FileSystem fileSystem, AclChoice acl) throws IOException {
        return switch (FileKind.of(fileSystem)) {
            case POSIX -> Optional.of(new Posix());
            case ACL -> Optional.of(acl.on(fileSystem));
            case OTHER -> Optional.empty();
        };
    }

    /** Attributes supplied when creating a private directory. */
    FileAttribute<?>[] directoryAttributes();

    /** Attributes supplied when creating a private regular file. */
    FileAttribute<?>[] fileAttributes();

    /**
     * Checks without following symbolic links whether another principal can access {@code path}. Returns empty when
     * owner-only (within policy) or when the file system supports neither POSIX nor ACL views.
     */
    Optional<Exposure> openExposure(Path path) throws IOException;

    /**
     * Settles newly created protection before sensitive bytes are written. On ACL file systems, optionally takes
     * ownership if {@code takeOwnershipIfNeeded} and replaces inherited permissive ACL entries with the policy ACL
     * using {@link LinkOption#NOFOLLOW_LINKS}, then returns any remaining exposure.
     */
    default Optional<Exposure> settle(Path path, boolean takeOwnershipIfNeeded) throws IOException {
        return openExposure(path);
    }

    /** No POSIX and no ACL view: relies on the default profile protection. */
    record Unprotected() implements FileProtection {

        @Override
        public FileAttribute<?>[] directoryAttributes() {
            return new FileAttribute<?>[0];
        }

        @Override
        public FileAttribute<?>[] fileAttributes() {
            return new FileAttribute<?>[0];
        }

        @Override
        public Optional<Exposure> openExposure(Path path) {
            return Optional.empty();
        }
    }

    /** POSIX permissions ({@code rwx------} directories, {@code rw-------} files). */
    record Posix() implements FileProtection {

        public static final Set<PosixFilePermission> DIRECTORY_MODE =
                Set.copyOf(PosixFilePermissions.fromString("rwx------"));
        public static final Set<PosixFilePermission> FILE_MODE =
                Set.copyOf(PosixFilePermissions.fromString("rw-------"));
        public static final Set<PosixFilePermission> GROUP_AND_OTHERS = Set.of(
                PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE);

        @Override
        public FileAttribute<?>[] directoryAttributes() {
            return new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(DIRECTORY_MODE)};
        }

        @Override
        public FileAttribute<?>[] fileAttributes() {
            return new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(FILE_MODE)};
        }

        @Override
        public Optional<Exposure> openExposure(Path path) throws IOException {
            Set<PosixFilePermission> mode = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
            if (!Collections.disjoint(mode, GROUP_AND_OTHERS)) {
                return Optional.of(new Exposure.PosixMode(PosixFilePermissions.toString(mode)));
            }
            return Optional.empty();
        }
    }

    /** {@code acl:acl} file creation attribute. */
    record AclAttribute(List<AclEntry> value) implements FileAttribute<List<AclEntry>> {

        private static final String NAME = "acl:acl";

        @Override
        public String name() {
            return NAME;
        }
    }

    /**
     * ACL protection granting {@code owner} full control, optionally allowing read-only {@code reader} (for example
     * LocalService) and optionally ignoring built-in administrative principals during inherited-ACL settlement.
     */
    record Acl(UserPrincipal owner, UserPrincipal reader, boolean allowAdministrativeReads)
            implements FileProtection {

        public Acl(UserPrincipal owner) {
            this(owner, null, false);
        }

        public List<AclEntry> entries(boolean directory) {
            return reader == null
                    ? ServicePrincipalAcl.ownerOnly(owner, directory)
                    : ServicePrincipalAcl.ownerAndService(owner, reader, directory);
        }

        @Override
        public FileAttribute<?>[] directoryAttributes() {
            return new FileAttribute<?>[] {new AclAttribute(entries(true))};
        }

        @Override
        public FileAttribute<?>[] fileAttributes() {
            return new FileAttribute<?>[] {new AclAttribute(entries(false))};
        }

        @Override
        public Optional<Exposure> openExposure(Path path) throws IOException {
            AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS);
            List<AclEntry> acl = view.getAcl();
            if (acl.isEmpty()) {
                return Optional.of(new Exposure.EmptyAcl());
            }
            for (AclEntry entry : acl) {
                boolean granting = entry.type() == AclEntryType.ALLOW
                        && !Objects.equals(entry.principal(), owner)
                        && (reader == null || !ServicePrincipalAcl.isServiceRead(entry, reader))
                        && (!allowAdministrativeReads || !ServicePrincipalAcl.isAdministrative(entry.principal()))
                        && !ServicePrincipalAcl.HARMLESS.containsAll(entry.permissions());
                if (granting) {
                    return Optional.of(new Exposure.AclPrincipal(entry.principal().getName()));
                }
            }
            return Optional.empty();
        }

        @Override
        public Optional<Exposure> settle(Path path, boolean takeOwnershipIfNeeded) throws IOException {
            FileOwnerAttributeView ownerView = Files.getFileAttributeView(path, FileOwnerAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (takeOwnershipIfNeeded && ownerView != null && !Objects.equals(ownerView.getOwner(), owner)) {
                ownerView.setOwner(owner);
            }
            if (openExposure(path).isPresent()) {
                boolean directory = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
                Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                        .setAcl(entries(directory));
            }
            return openExposure(path);
        }
    }
}
