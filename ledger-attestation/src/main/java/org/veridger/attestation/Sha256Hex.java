// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.attestation;

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

    /** Sink receiving raw bytes for streaming SHA-256 computation. */
    @FunctionalInterface
    public interface ChunkSink {
        void update(byte[] bytes, int offset, int length);
    }

    /** Source that feeds chunks into a local {@link MessageDigest} inside {@link #stream(StreamSource)}. */
    @FunctionalInterface
    public interface StreamSource<E extends Exception> {
        void feed(ChunkSink sink) throws E;
    }

    /**
     * Streams chunks through a fresh local SHA-256 {@link MessageDigest} and returns the 64 lower-case hex characters.
     * The digest never escapes this call frame, so it cannot be shared across threads or reused after finishing.
     */
    public static <E extends Exception> String stream(StreamSource<E> source) throws E {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(ALGORITHM);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is mandatory on every JVM", failure);
        }
        source.feed(digest::update);
        return HexFormat.of().formatHex(digest.digest());
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
