package org.pgledger.attestation;

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

    /** The digest of {@code bytes}, 64 lower-case hex characters. */
    public static String of(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(ALGORITHM).digest(bytes));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is mandatory on every JVM", failure);
        }
    }
}
