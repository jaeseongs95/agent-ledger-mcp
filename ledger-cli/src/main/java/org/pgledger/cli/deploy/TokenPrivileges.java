package org.pgledger.cli.deploy;

import java.io.IOException;

/**
 * The privileges of the token of this process, enabled for a short time: what the Windows platform needs to take a file over
 * from another owner ({@link WindowsAccess}). Java has no API for it, so the one implementation ({@link WindowsPrivileges}) calls
 * the Windows API; this interface is what {@link WindowsAccess} depends on, so a test, or a platform that has no such
 * privileges, brings its own. Only the Windows platform asks for it, and only after the file system has said that it has ACLs.
 */
interface TokenPrivileges {

    String TAKE_OWNERSHIP = "SeTakeOwnershipPrivilege";
    String RESTORE = "SeRestorePrivilege";

    /**
     * Enables the privileges on the token of this process until the result is closed; closing puts the token back as it was.
     *
     * @param names the names of the privileges ({@link #TAKE_OWNERSHIP}, {@link #RESTORE})
     * @throws IOException when the token cannot be opened or a name is not a privilege of this system (not when the token
     *         merely does not hold one: see {@link Enabled#allHeld})
     */
    Enabled enable(String... names) throws IOException;

    /** The privileges, enabled until {@link #close}. Used on the thread that made it. */
    interface Enabled extends AutoCloseable {

        /**
         * Whether the token holds every privilege that was asked for. When it does not (the process is not elevated), the
         * ones it holds are enabled all the same and the operation that follows may still succeed for a file the process
         * owns; a refusal of an operation that needed a privilege is then the one to report as a missing privilege.
         */
        boolean allHeld();

        @Override
        void close();
    }
}
