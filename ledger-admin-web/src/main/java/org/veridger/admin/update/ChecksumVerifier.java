// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.admin.update;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Parses {@code SHA256SUMS} manifests and verifies downloaded artifacts with constant-time SHA-256 comparison. */
@Component
public final class ChecksumVerifier {

    private static final int BUFFER_SIZE = 16_384;
    private static final Pattern LINE = Pattern.compile("^([0-9a-fA-F]{64})\\s+\\*?([A-Za-z0-9._-]+)$");

    /** Parses a standard {@code sha256sum} manifest into a map of filename to lowercase 64-char hex digest. */
    public Map<String, String> parseManifest(String text) {
        Map<String, String> digests = new LinkedHashMap<>();
        if (text == null) {
            return Map.of();
        }
        for (String raw : text.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            Matcher matcher = LINE.matcher(line);
            if (!matcher.matches()) {
                throw new IllegalArgumentException("malformed line in SHA256SUMS manifest");
            }
            digests.put(matcher.group(2), matcher.group(1).toLowerCase(Locale.ROOT));
        }
        return Map.copyOf(digests);
    }

    /** Computes the lowercase SHA-256 hex digest of {@code file}. */
    public String sha256(Path file) throws IOException {
        MessageDigest digest = sha256Digest();
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Verifies {@code file} matches {@code expectedHex} in constant time; returns the verified lowercase hex digest. */
    public String verify(Path file, String expectedHex) throws IOException {
        if (expectedHex == null || expectedHex.length() != 64) {
            throw new IOException("expected SHA-256 digest is missing or malformed");
        }
        String actual = sha256(file);
        byte[] expectedBytes = expectedHex.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        byte[] actualBytes = actual.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expectedBytes, actualBytes)) {
            throw new IOException("SHA-256 checksum mismatch for " + file.getFileName());
        }
        return actual;
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 algorithm is not available", impossible);
        }
    }
}
