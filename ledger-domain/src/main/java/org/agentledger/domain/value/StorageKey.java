// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.domain.value;

import java.util.regex.Pattern;

/**
 * The name of an attachment file on disk: 128 random bits as 32 lower-case hex characters. It carries nothing of the file (no
 * extension, date, original name, id or project). The first two characters name the sub folder. It is a stored name, not a
 * secret, but no response, page or log of the product shows it.
 */
public record StorageKey(String hex) {

    public static final int LENGTH = 32;
    private static final Pattern FORMAT = Pattern.compile("[0-9a-f]{" + LENGTH + "}");

    public StorageKey {
        if (hex == null || !FORMAT.matcher(hex).matches()) {
            throw new IllegalArgumentException("a storage key is 32 lower-case hex characters");
        }
    }

    public static StorageKey of(String hex) {
        return new StorageKey(hex);
    }

    /** The sub folder: the first two characters. */
    public String folder() {
        return hex.substring(0, 2);
    }

    /** True when the text has the format of a storage key (a file name found in a folder may not). */
    public static boolean isFormat(String text) {
        return text != null && FORMAT.matcher(text).matches();
    }

    /** The key is a stored name that no log line shows. */
    @Override
    public String toString() {
        return "StorageKey[hidden]";
    }
}
