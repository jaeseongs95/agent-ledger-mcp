// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.cli.deploy;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.UserPrincipal;
import org.pgledger.files.WellKnownAccount;

/**
 * Boundary to the accounts of the host that an installation names: the service user and its group (Linux), the well-known
 * accounts of Windows by what they mean (the system, the administrators, the service account: {@link WellKnownAccount}, found by
 * their SID, so the name of the host's language is never written in the product). The product resolves a name with the lookup
 * service of the file system ({@link java.nio.file.attribute.UserPrincipalLookupService}); a test plays the accounts, because
 * only an administrator may give a file to another account.
 */
public interface HostAccounts {

    /** @throws java.nio.file.attribute.UserPrincipalNotFoundException when the host has no such account */
    UserPrincipal user(FileSystem fileSystem, String name) throws IOException;

    /**
     * A well-known account of Windows.
     *
     * @throws java.nio.file.attribute.UserPrincipalNotFoundException when the host has no such account
     */
    UserPrincipal wellKnown(FileSystem fileSystem, WellKnownAccount account) throws IOException;

    /** @throws java.nio.file.attribute.UserPrincipalNotFoundException when the host has no such group */
    GroupPrincipal group(FileSystem fileSystem, String name) throws IOException;
}
