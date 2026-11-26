// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.files;

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

    /**
     * Looks up the predefined service account principal ({@link WellKnownAccount#SERVICE}, which services run as when reading
     * secrets and attachment keys created by the installing user) on {@code fileSystem}, by the name this host gives it.
     */
    public static UserPrincipal principal(FileSystem fileSystem) throws IOException {
        return principal(fileSystem, WellKnownAccount.SERVICE, WellKnownAccounts.host());
    }

    /** Looks up a well-known account on {@code fileSystem} by the name that {@code accounts} gives it. */
    public static UserPrincipal principal(FileSystem fileSystem, WellKnownAccount account, WellKnownAccounts accounts)
            throws IOException {
        return fileSystem.getUserPrincipalLookupService().lookupPrincipalByName(accounts.nameOf(account));
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

    /**
     * Whether {@code principal} is a built-in administrative principal allowed by attachment runtime settlement: the system or the
     * group of the administrators ({@link WellKnownAccount}), by the name this host gives them.
     */
    public static boolean isAdministrative(UserPrincipal principal) throws IOException {
        return isAdministrative(principal, WellKnownAccounts.host());
    }

    /** As {@link #isAdministrative(UserPrincipal)}, with the names of the well-known accounts that {@code accounts} gives. */
    public static boolean isAdministrative(UserPrincipal principal, WellKnownAccounts accounts) throws IOException {
        String name = principal.getName();
        return name.equalsIgnoreCase(accounts.nameOf(WellKnownAccount.SYSTEM))
                || name.equalsIgnoreCase(accounts.nameOf(WellKnownAccount.ADMINISTRATORS));
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
