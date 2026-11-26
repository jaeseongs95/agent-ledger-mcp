// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.attestation;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 (FIPS 180-4) of bytes as 64 lower-case hex characters: the one SHA-256 helper of the product. This module is
 * the lowest one (JDK only), so the client hook, the domain ({@code Sha256}, the value type with its format check) and
 * the seed ({@code SeedDigest}) all digest through it.
 */
public final class Sha256Hex {

    private static final String ALGORITHM = "SHA-256";

    private Sha256Hex() {
    }

    /**
     * A digest that is fed piece by piece (a file that is read in chunks), the streaming form of {@link #of}: the same algorithm,
     * the same 64 lower-case hex characters. It is not thread safe and is used once.
     */
    public static Running running() {
        try {
            return new Running(MessageDigest.getInstance(ALGORITHM));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is mandatory on every JVM", failure);
        }
    }

    /** The state of a digest that is being fed. */
    public static final class Running {

        private final MessageDigest digest;

        private Running(MessageDigest digest) {
            this.digest = digest;
        }

        /** Adds {@code length} bytes of {@code bytes} from {@code offset}. */
        public void update(byte[] bytes, int offset, int length) {
            digest.update(bytes, offset, length);
        }

        /** The digest of everything added, 64 lower-case hex characters; the object is finished then. */
        public String finish() {
            return HexFormat.of().formatHex(digest.digest());
        }
    }

    /** The digest of {@code bytes}, 64 lower-case hex characters. */
    public static String of(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(ALGORITHM).digest(bytes));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is mandatory on every JVM", failure);
        }
    }
}
