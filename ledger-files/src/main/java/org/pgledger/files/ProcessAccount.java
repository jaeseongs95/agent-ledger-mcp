// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.files;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.attribute.UserPrincipal;
import java.util.Optional;

/**
 * The account this process runs as, the same way for the server, the command line tool and the hook: the JDK's
 * {@link ProcessHandle} names it (with its domain on Windows, {@code DOMAIN\name}), and the lookup service of the file system turns
 * the name into a principal that compares equal to the owner of a file. The system property {@code user.name} is not used: it
 * has no domain, so on a computer that has a domain account and a local account of one name it can name the other account.
 */
public final class ProcessAccount {

    private ProcessAccount() {
    }

    /** The name of the account of this process, or empty when the JDK cannot tell. */
    public static Optional<String> name() {
        return ProcessHandle.current().info().user();
    }

    /**
     * The account of this process as a principal of {@code fileSystem}.
     *
     * @throws java.nio.file.attribute.UserPrincipalNotFoundException when the account is not known to the file system, or the JDK
     *         cannot tell it (the name in the exception is then {@code <process>})
     */
    public static UserPrincipal principal(FileSystem fileSystem) throws IOException {
        String name = name().orElseThrow(() -> new java.nio.file.attribute.UserPrincipalNotFoundException("<process>"));
        return fileSystem.getUserPrincipalLookupService().lookupPrincipalByName(name);
    }
}
