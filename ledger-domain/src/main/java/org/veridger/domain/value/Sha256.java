// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.domain.value;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.veridger.attestation.Sha256Hex;
import org.veridger.domain.error.ValidationException;
import org.veridger.domain.error.ValidationReason;

/** A SHA-256 digest as 64 lower-case hex characters (evidence, payload and file digests). */
public record Sha256(String hex) {

    private static final Pattern FORMAT = Pattern.compile("[0-9a-f]{64}");

    public Sha256 {
        if (hex == null || !FORMAT.matcher(hex).matches()) {
            throw ValidationException.invalid("sha256", ValidationReason.MUST_BE_LOWER_CASE_HEX, "64");
        }
    }

    public static Sha256 of(String hex) {
        return new Sha256(hex);
    }

    public static Sha256 ofBytes(byte[] bytes) {
        return new Sha256(Sha256Hex.of(bytes));
    }

    public static Sha256 ofText(String text) {
        return ofBytes(text.getBytes(StandardCharsets.UTF_8));
    }
}
