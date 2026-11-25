// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.files;

import java.io.IOException;

/**
 * What the host calls the well-known accounts ({@link WellKnownAccount}). The business code asks for a meaning (the service
 * account, the system, the administrators) and gets the name that the lookup service of the file system understands, so no name
 * of an account is written in the product.
 *
 * <p>The one implementation is the Windows API behind {@link #host()}; a test plays a host whose names are not English.
 */
@FunctionalInterface
public interface WellKnownAccounts {

    /**
     * The name of the account on this host, as {@link java.nio.file.attribute.UserPrincipalLookupService#lookupPrincipalByName}
     * takes it and as {@link java.nio.file.attribute.UserPrincipal#getName()} gives it ({@code DOMAIN\name}).
     *
     * @throws IOException when the host cannot tell the name (the status of the failed call is in the message)
     */
    String nameOf(WellKnownAccount account) throws IOException;

    /**
     * The accounts of this host. Nothing is loaded or called until a name is asked for, so a host that never asks (a POSIX file
     * system, or the client hook, which has no ACL of a service) never loads the Windows boundary class.
     */
    static WellKnownAccounts host() {
        return account -> new WindowsAccountNames().nameOf(account);
    }
}
