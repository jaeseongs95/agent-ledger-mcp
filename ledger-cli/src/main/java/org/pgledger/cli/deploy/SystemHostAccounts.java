// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.cli.deploy;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.UserPrincipal;
import org.pgledger.files.ServicePrincipalAcl;
import org.pgledger.files.WellKnownAccount;
import org.pgledger.files.WellKnownAccounts;
import org.springframework.stereotype.Component;

/** The accounts of this host, by the lookup service of the file system. */
@Component
class SystemHostAccounts implements HostAccounts {

    private final WellKnownAccounts names = WellKnownAccounts.host();

    @Override
    public UserPrincipal user(FileSystem fileSystem, String name) throws IOException {
        return fileSystem.getUserPrincipalLookupService().lookupPrincipalByName(name);
    }

    @Override
    public UserPrincipal wellKnown(FileSystem fileSystem, WellKnownAccount account) throws IOException {
        return ServicePrincipalAcl.principal(fileSystem, account, names);
    }

    @Override
    public GroupPrincipal group(FileSystem fileSystem, String name) throws IOException {
        return fileSystem.getUserPrincipalLookupService().lookupPrincipalByGroupName(name);
    }
}
