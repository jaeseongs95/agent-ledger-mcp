// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.testing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The tests of the read rule of the secret files (cases SFR-001 to SFR-028): the personal edition reads and uses an existing secret
 * file or folder whatever other accounts may do with it, so the tests have to make files that others can reach, and to say whether
 * a file that the product made is owner-only. The file system decides how, by the attribute views it has and never by the name of
 * the operating system: POSIX permission bits (group and other), or an ACL (an ALLOW entry for a principal that is neither the
 * owner nor SYSTEM nor Administrators, such as {@code Authenticated Users}, which a folder on a data volume or under the root of the
 * system drive inherits by default on Windows).
 */
public final class BroadAccess {

    /** The names tried for the broad principal of a Windows file system, the first one the file system knows is used. */
    private static final List<String> BROAD_PRINCIPALS = List.of("NT AUTHORITY\\Authenticated Users", "Authenticated Users",
            "BUILTIN\\Users", "Everyone");

    private BroadAccess() {
    }

    /** True when the file system has the POSIX view or the ACL view for the path (a file system with neither cannot be made broad). */
    public static boolean supported(Path path) {
        return Files.getFileAttributeView(path, PosixFileAttributeView.class) != null
                || Files.getFileAttributeView(path, AclFileAttributeView.class) != null;
    }

    /**
     * Lets other accounts read, and for a folder enter, the file or folder (POSIX {@code r--} for group and others, {@code r-x} for a
     * folder; an ACL: one more ALLOW entry of {@code Authenticated Users} with read and write rights, inherited by a folder), and
     * returns the path. The existing entries stay.
     */
    public static Path open(Path path) {
        try {
            boolean directory = Files.isDirectory(path);
            PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class);
            if (posix != null) {
                Set<PosixFilePermission> mode = new HashSet<>(posix.readAttributes().permissions());
                mode.addAll(PosixFilePermissions.fromString(directory ? "rwxrwxrwx" : "rw-rw-rw-"));
                posix.setPermissions(mode);
                return path;
            }
            AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
            if (acl == null) {
                throw new IllegalStateException("the file system of " + path + " has neither POSIX permissions nor ACLs");
            }
            Set<AclEntryFlag> flags = directory ? EnumSet.of(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT)
                    : EnumSet.noneOf(AclEntryFlag.class);
            List<AclEntry> entries = new ArrayList<>(acl.getAcl());
            entries.add(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(broadPrincipal(path))
                    .setPermissions(EnumSet.of(AclEntryPermission.READ_DATA, AclEntryPermission.WRITE_DATA,
                            AclEntryPermission.READ_ATTRIBUTES, AclEntryPermission.READ_ACL, AclEntryPermission.SYNCHRONIZE,
                            AclEntryPermission.EXECUTE))
                    .setFlags(flags).build());
            acl.setAcl(entries);
            return path;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot open " + path + " to other accounts", e);
        }
    }

    /** True when another account than the owner has access: a group or other bit, or an ALLOW entry beyond the owner, SYSTEM and Administrators. */
    public static boolean reachedByOthers(Path path) {
        try {
            PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class);
            if (posix != null) {
                return posix.readAttributes().permissions().stream().anyMatch(BroadAccess::groupOrOther);
            }
            AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
            if (acl == null) {
                return false;
            }
            UserPrincipal owner = Files.getOwner(path);
            for (AclEntry entry : acl.getAcl()) {
                String name = entry.principal().getName();
                if (entry.type() == AclEntryType.ALLOW && !entry.principal().equals(owner) && !name.endsWith("\\SYSTEM")
                        && !name.endsWith("\\Administrators")) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the protection of " + path, e);
        }
    }

    /**
     * True when the file or folder is owner-only as the product makes it: POSIX permissions without any group or other bit, or an
     * ACL that is not empty and whose every entry is an ALLOW entry of the owner.
     */
    public static boolean ownerOnly(Path path) {
        try {
            PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class);
            if (posix != null) {
                return posix.readAttributes().permissions().stream().noneMatch(BroadAccess::groupOrOther);
            }
            AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
            if (acl == null) {
                throw new IllegalStateException("the file system of " + path + " has neither POSIX permissions nor ACLs");
            }
            UserPrincipal owner = Files.getOwner(path);
            List<AclEntry> entries = acl.getAcl();
            return !entries.isEmpty() && entries.stream()
                    .allMatch(entry -> entry.type() == AclEntryType.ALLOW && entry.principal().equals(owner));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the protection of " + path, e);
        }
    }

    /**
     * True when the file or folder is as the product makes a secret of the services (PCS-030, PCS-033): POSIX permissions without
     * any group or other bit, or an ACL that is not empty and whose every entry is an ALLOW entry of the owner or an ALLOW entry of
     * the service account LocalService with nothing but read rights (data, attributes, ACL, wait, and for a folder the traverse).
     * A file system whose ACL view does not know that account has no such entry.
     */
    public static boolean ownerAndServiceRead(Path path) {
        try {
            PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class);
            if (posix != null) {
                return posix.readAttributes().permissions().stream().noneMatch(BroadAccess::groupOrOther);
            }
            AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
            if (acl == null) {
                throw new IllegalStateException("the file system of " + path + " has neither POSIX permissions nor ACLs");
            }
            // the expected owner is the account of the process, looked up the way the product does it (ProcessHandle and the lookup
            // service of the file system), never Files.getOwner: the group Administrators owns what an elevated process creates
            UserPrincipal owner = path.getFileSystem().getUserPrincipalLookupService()
                    .lookupPrincipalByName(ProcessHandle.current().info().user().orElseThrow());
            UserPrincipal service = path.getFileSystem().getUserPrincipalLookupService()
                    .lookupPrincipalByName("NT AUTHORITY\\LocalService");
            Set<AclEntryPermission> read = EnumSet.of(AclEntryPermission.READ_DATA, AclEntryPermission.READ_NAMED_ATTRS,
                    AclEntryPermission.READ_ATTRIBUTES, AclEntryPermission.READ_ACL, AclEntryPermission.SYNCHRONIZE,
                    AclEntryPermission.EXECUTE);
            List<AclEntry> entries = acl.getAcl();
            return !entries.isEmpty() && entries.stream().allMatch(entry -> entry.type() == AclEntryType.ALLOW
                    && (entry.principal().equals(owner) || (entry.principal().equals(service) && read.containsAll(entry.permissions()))));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the protection of " + path, e);
        }
    }

    /**
     * Makes a symbolic link to the target; false when this process may not make links (Windows without the privilege or the
     * developer mode, a file system without links), so that a test can skip itself with an assumption.
     */
    public static boolean link(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            return false;
        }
    }

    private static boolean groupOrOther(PosixFilePermission permission) {
        return switch (permission) {
            case GROUP_READ, GROUP_WRITE, GROUP_EXECUTE, OTHERS_READ, OTHERS_WRITE, OTHERS_EXECUTE -> true;
            default -> false;
        };
    }

    private static UserPrincipal broadPrincipal(Path path) throws IOException {
        IOException last = null;
        for (String name : BROAD_PRINCIPALS) {
            try {
                return path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(name);
            } catch (IOException e) {
                last = e;
            }
        }
        throw new IOException("no broad principal is known to the file system", last);
    }
}
