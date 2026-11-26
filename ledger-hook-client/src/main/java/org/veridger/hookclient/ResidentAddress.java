// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.hookclient;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Where the running resident program is and how to prove to it who is calling: the content of the address file.
 *
 * <p>{@code processStart} is the start time of the process in epoch milliseconds (0 where the platform does not tell it); with
 * {@code pid} it names one process for good, because a pid alone is reused after the process ends. {@code build} names the
 * program file the process runs ({@link ResidentJar#build}): a client of another build replaces the process. {@code secret} is
 * a new random value of every start, in the address file only: whoever can read the file may call the program, and the file
 * belongs to its owner alone ({@link OwnerOnlyFiles}).
 *
 * <p>The secret never leaves this record in text: {@link #toString} hides it, so a log line or a message that names the
 * address cannot carry it.
 */
public record ResidentAddress(int port, long pid, long processStart, String build, String secret) {

    private static final int SECRET_BYTES = 32;

    /** A new secret: 256 random bits as URL-safe base64 text. */
    public static String newSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Override
    public String toString() {
        return "ResidentAddress[port=" + port + ", pid=" + pid + ", build=" + build + "]";
    }
}
