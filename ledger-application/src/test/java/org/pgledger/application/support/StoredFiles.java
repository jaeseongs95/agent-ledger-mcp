package org.pgledger.application.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The raw bytes of a stored file, as an attacker (or a failing disk) would change them: the file is read whole, taken apart into its
 * segments by their 4-byte length prefixes, changed, and written back. A finished file is read only, so it is made writable first.
 */
public final class StoredFiles {

    private StoredFiles() {
    }

    public static byte[] readAll(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** Replaces the whole content of the file (the file stays where it is, under its own name). */
    public static void replace(Path file, byte[] bytes) {
        try {
            if (!file.toFile().setWritable(true)) {
                throw new IllegalStateException("the file cannot be made writable: " + file.getFileName());
            }
            Files.write(file, bytes);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** Deletes the file (a finished file is read only, which is cleared first). */
    public static void delete(Path file) {
        try {
            if (!file.toFile().setWritable(true)) {
                throw new IllegalStateException("the file cannot be made writable: " + file.getFileName());
            }
            Files.delete(file);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** The segments of a stored file, each with its length prefix, in order. */
    public static List<byte[]> segments(byte[] file) {
        List<byte[]> found = new ArrayList<>();
        int position = 0;
        while (position < file.length) {
            int length = ByteBuffer.wrap(file, position, 4).getInt();
            found.add(Arrays.copyOfRange(file, position, position + 4 + length));
            position += 4 + length;
        }
        return found;
    }

    /** The segments joined in the given order. */
    public static byte[] join(List<byte[]> segments) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] segment : segments) {
            out.writeBytes(segment);
        }
        return out.toByteArray();
    }

    /** A copy of the bytes with the byte at {@code index} changed. */
    public static byte[] flip(byte[] bytes, int index) {
        byte[] copy = bytes.clone();
        copy[index] = (byte) (copy[index] ^ 0x01);
        return copy;
    }

    /** The position of the first occurrence of {@code needle} in {@code haystack}, or -1. */
    public static int indexOf(byte[] haystack, byte[] needle) {
        for (int start = 0; start + needle.length <= haystack.length; start++) {
            if (Arrays.equals(haystack, start, start + needle.length, needle, 0, needle.length)) {
                return start;
            }
        }
        return -1;
    }
}
