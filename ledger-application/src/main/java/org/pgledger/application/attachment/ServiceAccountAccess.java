package org.pgledger.application.attachment;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.List;
import java.util.Set;
import org.pgledger.files.ServicePrincipalAcl;

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
 * <p>Delegates the POSIX/ACL policy constants and ACL entry helpers to the pure-JDK {@code ledger-files} module
 * ({@link ServicePrincipalAcl}).
 */
public final class ServiceAccountAccess {

    /**
     * The one service account of a Windows installation: the predefined local account LocalService, which "has minimum
     * privileges on the local computer and presents anonymous credentials on the network"
     * (https://learn.microsoft.com/en-us/windows/win32/services/localservice-account).
     */
    public static final String WINDOWS_SERVICE_ACCOUNT = ServicePrincipalAcl.WINDOWS_SERVICE_ACCOUNT;

    /** Read of a file: its data, its attributes and extended attributes, its ACL, and the wait for the handle (icacls {@code R}). */
    public static final Set<AclEntryPermission> READ_FILE = ServicePrincipalAcl.READ_FILE;

    /** Read of a folder: {@link #READ_FILE} (the listing) and the right to traverse it. */
    public static final Set<AclEntryPermission> READ_FOLDER = ServicePrincipalAcl.READ_FOLDER;

    private ServiceAccountAccess() {
    }

    /**
     * The service account as a principal of the file system.
     *
     * @throws java.nio.file.attribute.UserPrincipalNotFoundException when the file system does not know the account
     */
    public static UserPrincipal principal(FileSystem fileSystem) throws IOException {
        return ServicePrincipalAcl.principal(fileSystem);
    }

    /**
     * The ACL of a new secret folder or file: ALLOW for the owner with everything and, unless the service account is the owner
     * itself (the service makes its own files), ALLOW for the service account with read. A folder's entries are inherited by
     * what it holds.
     */
    public static List<AclEntry> ownerAndService(UserPrincipal owner, UserPrincipal service, boolean directory) {
        return ServicePrincipalAcl.ownerAndService(owner, service, directory);
    }

    /** Whether the entry is an ALLOW of the service account with nothing but the read of this class. */
    public static boolean isServiceRead(AclEntry entry, UserPrincipal service) {
        return ServicePrincipalAcl.isServiceRead(entry, service);
    }
}
