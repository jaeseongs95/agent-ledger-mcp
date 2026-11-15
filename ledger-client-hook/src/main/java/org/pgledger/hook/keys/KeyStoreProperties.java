package org.pgledger.hook.keys;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The settings of the key store, the one place where the hook reads its environment (principle 3.5): Boot binds them from
 * the environment variable {@code LEDGER_HOOK_KEY_DIR} (relaxed binding of {@code ledger.hook.key-dir}, the name that
 * installed host configurations and the owner use), from a system property or from {@code application.properties}.
 *
 * <p>The values stay text, not {@code Path}: a value that is no usable folder (blank, relative, not a path) must not stop
 * the start of the application, it makes every key load refuse with {@link SessionKeyException.Reason#FOLDER_UNAVAILABLE}
 * and the hook answers the host ({@link KeyFolder}). There is nothing to validate here, so the record has no bean validation.
 *
 * @param keyDir   the key folder, an absolute path: {@code null} or blank if the variable is not set (then the home folder
 *                 decides)
 * @param userHome the home folder of the account that runs the hook ({@code user.home}, set in
 *                 {@code application.properties}): the key folder is {@code <home>/.pg-ledger-mcp/session-keys} when
 *                 {@code keyDir} is not set
 * @param userName the account that the owner-only entry of a new key folder and key file names, on a file system with ACLs
 *                 ({@code user.name}, set in {@code application.properties}); the owner of an existing one is not looked at
 */
@ConfigurationProperties("ledger.hook")
public record KeyStoreProperties(String keyDir, String userHome, String userName) {

    /** The key folder these settings name: {@link KeyFolder#resolve}. */
    public KeyFolder folder() {
        return KeyFolder.resolve(keyDir, userHome);
    }
}
