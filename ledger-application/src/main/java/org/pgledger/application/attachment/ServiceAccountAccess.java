package org.pgledger.application.attachment;

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
 * The ACL that a secret folder or file of a Windows installation is <b>created</b> with: the installing user (the owner) with
 * everything, the service account with read, and nothing else. No inherited entry is put in when the path is made (the entries
 * that a parent hands down are replaced right after the creation; the DACL is not marked protected). The services run as the
 * account LocalService ({@link #WINDOWS_SERVICE_ACCOUNT}: no person's account, no password) and must read the secrets and the
 * attachment keys that the installing user created; the installation has no step that hands the secrets over to the service
 * account (the installing user stays the owner, nothing is handed over, no privilege to take ownership is used), so the service
 * account is given the read right when the path is made.
 * The personal edition has one user, who owns what the installer made and trusts the whole installation target.
 *
 * <p>This is the one place of the rule, used by the store of secrets of the command line tool, by the installer of the folders
 * and by {@link PrivateFiles} (the module of the command line depends on this one). The file system decides by the attribute
 * views it supports and never by the name of the operating system; the account name is the name of an account, as the one
 * service account of the layout of the installation.
 */
public final class ServiceAccountAccess {

    /**
     * The one service account of a Windows installation: the predefined local account LocalService, which "has minimum
     * privileges on the local computer and presents anonymous credentials on the network"
     * (https://learn.microsoft.com/en-us/windows/win32/services/localservice-account).
     */
    public static final String WINDOWS_SERVICE_ACCOUNT = "NT AUTHORITY\\LocalService";

    /** Read of a file: its data, its attributes and extended attributes, its ACL, and the wait for the handle (icacls {@code R}). */
    public static final Set<AclEntryPermission> READ_FILE = Collections.unmodifiableSet(EnumSet.of(
            AclEntryPermission.READ_DATA, AclEntryPermission.READ_NAMED_ATTRS, AclEntryPermission.READ_ATTRIBUTES,
            AclEntryPermission.READ_ACL, AclEntryPermission.SYNCHRONIZE));

    /** Read of a folder: {@link #READ_FILE} (the listing) and the right to traverse it. */
    public static final Set<AclEntryPermission> READ_FOLDER;

    static {
        Set<AclEntryPermission> folder = EnumSet.copyOf(READ_FILE);
        folder.add(AclEntryPermission.EXECUTE);
        READ_FOLDER = Collections.unmodifiableSet(folder);
    }

    private ServiceAccountAccess() {
    }

    /**
     * The service account as a principal of the file system.
     *
     * @throws java.nio.file.attribute.UserPrincipalNotFoundException when the file system does not know the account
     */
    public static UserPrincipal principal(FileSystem fileSystem) throws IOException {
        return fileSystem.getUserPrincipalLookupService().lookupPrincipalByName(WINDOWS_SERVICE_ACCOUNT);
    }

    /**
     * The ACL of a new secret folder or file: ALLOW for the owner with everything and, unless the service account is the owner
     * itself (the service makes its own files), ALLOW for the service account with read. A folder's entries are inherited by
     * what it holds.
     */
    public static List<AclEntry> ownerAndService(UserPrincipal owner, UserPrincipal service, boolean directory) {
        List<AclEntry> acl = new ArrayList<>();
        acl.add(entry(owner, EnumSet.allOf(AclEntryPermission.class), directory));
        if (!Objects.equals(owner, service)) {
            acl.add(entry(service, directory ? READ_FOLDER : READ_FILE, directory));
        }
        return List.copyOf(acl);
    }

    /** Whether the entry is an ALLOW of the service account with nothing but the read of this class. */
    public static boolean isServiceRead(AclEntry entry, UserPrincipal service) {
        return entry.type() == AclEntryType.ALLOW && Objects.equals(entry.principal(), service)
                && READ_FOLDER.containsAll(entry.permissions());
    }

    private static AclEntry entry(UserPrincipal who, Set<AclEntryPermission> permissions, boolean directory) {
        AclEntry.Builder entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(who).setPermissions(permissions);
        if (directory) {
            entry.setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT);
        }
        return entry.build();
    }
}
