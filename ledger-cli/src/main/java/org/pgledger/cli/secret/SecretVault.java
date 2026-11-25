// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.cli.secret;

import java.nio.file.Path;
import org.springframework.stereotype.Component;

/**
 * The secrets of the installation as the commands see them: a secret is stored, looked for and read by its name, and the
 * folder it belongs in is decided in one place ({@link SecretFolder#of}), so no command takes a folder option and none can
 * put a secret where a process that must not read it would. Each folder is a {@link SecretStore} (the same owner-only
 * protection and the same read rule as ever) below the secret directory; a folder is made when its first secret is stored.
 * A name that belongs to no folder is refused with {@code UNKNOWN_SECRET_NAME} before anything is asked or written.
 */
@Component
public class SecretVault {

    private final Path root;
    private final ProcessUser processUser;

    SecretVault(SecretsProperties properties, ProcessUser processUser) {
        this.root = properties.directory();
        this.processUser = processUser;
    }

    /**
     * The store of the folder the name belongs in.
     *
     * @throws org.pgledger.cli.FailureException UNKNOWN_SECRET_NAME
     */
    SecretStore folderOf(SecretName name) {
        return new SecretStore(root.resolve(SecretFolder.of(name).directory()), root.getParent(), processUser);
    }

    /** Refuses early (before the user types anything) when a secret cannot be stored; creates nothing. See {@link SecretStore#check}. */
    public void check(SecretName name) {
        folderOf(name).check(name);
    }

    /** Whether the secret is stored already. See {@link SecretStore#exists}. */
    public boolean exists(SecretName name) {
        return folderOf(name).exists(name);
    }

    /** The stored value, to store it under a second name; the caller wipes it. See {@link SecretStore#load}. */
    public char[] load(SecretName name) {
        return folderOf(name).load(name);
    }

    /** Writes the value into the folder of the name and returns the file; the caller wipes {@code value}. See {@link SecretStore#store}. */
    public Path store(SecretName name, char[] value) {
        return folderOf(name).store(name, value);
    }
}
