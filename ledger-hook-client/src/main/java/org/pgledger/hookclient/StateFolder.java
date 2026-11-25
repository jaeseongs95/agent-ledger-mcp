// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.hookclient;

import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The folder where one resident hook program keeps what the clients need to find and to start it: the address file, the two
 * lock files and its log. There is one resident program per key folder, so the folder is named after the key folder text that
 * the environment of the client gives ({@code LEDGER_HOOK_KEY_DIR}, empty where it is not set): a client with another key
 * folder finds another program, and the program that holds the keys of one folder is never asked to use another. The folder is
 * under the home folder of the account, {@code <home>/.pg-ledger-mcp/resident/<16 hex digits of SHA-256>}, and is owner-only.
 *
 * @param directory the state folder
 */
public record StateFolder(Path directory) {

    private static final String ROOT = ".pg-ledger-mcp";
    private static final String RESIDENT = "resident";
    private static final int NAME_BYTES = 8;

    /**
     * The state folder for this home folder and this key folder text.
     *
     * @param userHome      the home folder of the account (an absolute path)
     * @param keyFolderText the value of the key folder variable, {@code null} if it is not set
     * @throws IllegalArgumentException if the home folder is missing or not an absolute path
     */
    public static StateFolder locate(String userHome, String keyFolderText) {
        if (userHome == null || userHome.isBlank()) {
            throw new IllegalArgumentException("the home folder of the account is not known");
        }
        try {
            Path home = Path.of(userHome);
            if (!home.isAbsolute()) {
                throw new IllegalArgumentException("the home folder of the account is not an absolute path");
            }
            return new StateFolder(home.resolve(ROOT).resolve(RESIDENT).resolve(nameOf(keyFolderText)));
        } catch (InvalidPathException invalid) {
            throw new IllegalArgumentException("the home folder of the account is not a path", invalid);
        }
    }

    private static String nameOf(String keyFolderText) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((keyFolderText == null ? "" : keyFolderText).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, NAME_BYTES);
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("SHA-256 is part of every JDK", missing);
        }
    }

    /** The address file: where the program is and the secret that calls it ({@link AddressFile}). */
    public Path addressFile() {
        return directory.resolve("address");
    }

    /** The lock that the one client that starts the program holds while it does ({@link ResidentLauncher}). */
    public Path startupLock() {
        return directory.resolve("startup.lock");
    }

    /** The lock that the running program holds for its whole life: a second program on the same folder ends at once. */
    public Path instanceLock() {
        return directory.resolve("instance.lock");
    }

    /** What the resident program wrote on its standard output and error (no secret is ever written there). */
    public Path logFile() {
        return directory.resolve("resident.log");
    }
}
