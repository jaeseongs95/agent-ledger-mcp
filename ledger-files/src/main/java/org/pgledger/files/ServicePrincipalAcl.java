package org.pgledger.files;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Shared ACL permission sets and principal helpers for owner-only and service-readable directories and files on ACL
 * file systems. Pure JDK, selected by file system capability and never by operating system name.
 */
public final class ServicePrincipalAcl {

    /**
     * The predefined local account that services run as when reading secrets and attachment keys created by the
     * installing user ({@code NT AUTHORITY\LocalService}).
     */
    public static final String WINDOWS_SERVICE_ACCOUNT = "NT AUTHORITY\\LocalService";

    /** Permissions that allow another principal to inspect metadata or wait on a handle without reading file content. */
    public static final Set<AclEntryPermission> HARMLESS = Collections.unmodifiableSet(EnumSet.of(
            AclEntryPermission.READ_ATTRIBUTES, AclEntryPermission.READ_ACL, AclEntryPermission.SYNCHRONIZE));

    /** Read of a file: data, attributes, ACL, and handle wait without execute. */
    public static final Set<AclEntryPermission> READ_FILE = Collections.unmodifiableSet(EnumSet.of(
            AclEntryPermission.READ_DATA, AclEntryPermission.READ_NAMED_ATTRS, AclEntryPermission.READ_ATTRIBUTES,
            AclEntryPermission.READ_ACL, AclEntryPermission.SYNCHRONIZE));

    /** Read of a folder: listing and traversal. */
    public static final Set<AclEntryPermission> READ_FOLDER;

    static {
        Set<AclEntryPermission> folder = EnumSet.copyOf(READ_FILE);
        folder.add(AclEntryPermission.EXECUTE);
        READ_FOLDER = Collections.unmodifiableSet(folder);
    }

    private ServicePrincipalAcl() {
    }

    /** Looks up the predefined service account principal on {@code fileSystem}. */
    public static UserPrincipal principal(FileSystem fileSystem) throws IOException {
        return fileSystem.getUserPrincipalLookupService().lookupPrincipalByName(WINDOWS_SERVICE_ACCOUNT);
    }

    /** Single-owner full-control ACL without extra readers. */
    public static List<AclEntry> ownerOnly(UserPrincipal owner, boolean directory) {
        return List.of(entry(owner, EnumSet.allOf(AclEntryPermission.class), directory));
    }

    /** Owner full-control plus read-only access for {@code service} (when distinct from {@code owner}). */
    public static List<AclEntry> ownerAndService(UserPrincipal owner, UserPrincipal service, boolean directory) {
        List<AclEntry> acl = new ArrayList<>();
        acl.add(entry(owner, EnumSet.allOf(AclEntryPermission.class), directory));
        if (!Objects.equals(owner, service)) {
            acl.add(entry(service, directory ? READ_FOLDER : READ_FILE, directory));
        }
        return List.copyOf(acl);
    }

    /** Whether {@code entry} grants {@code service} at most folder-read permissions. */
    public static boolean isServiceRead(AclEntry entry, UserPrincipal service) {
        return entry.type() == AclEntryType.ALLOW && Objects.equals(entry.principal(), service)
                && READ_FOLDER.containsAll(entry.permissions());
    }

    /** Whether {@code principal} is a built-in administrative principal allowed by attachment runtime settlement. */
    public static boolean isAdministrative(UserPrincipal principal) {
        String name = principal.getName();
        return name.endsWith("\\SYSTEM") || name.endsWith("\\Administrators");
    }

    private static AclEntry entry(UserPrincipal who, Set<AclEntryPermission> permissions, boolean directory) {
        AclEntry.Builder builder = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(who)
                .setPermissions(permissions);
        if (directory) {
            builder.setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT);
        }
        return builder.build();
    }
}
