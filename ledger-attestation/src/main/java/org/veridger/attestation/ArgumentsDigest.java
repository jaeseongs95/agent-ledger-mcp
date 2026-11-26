// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.attestation;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The digest of the arguments of a call that the attestation signs: SHA-256 of the RFC 8785 canonical form
 * ({@link JsonCanonicalizer}) of the arguments object without its top-level member {@code _hostAttestation}, in 64
 * lower-case hex characters. The client hook computes it for the arguments it hands the host (the {@code operationKey}
 * is in them, as the caller sent it), the server for the arguments it received, with this one class, so the two cannot disagree
 * about what "the same arguments" means: the same JSON value, whatever the order of the members and the white space.
 * One thing is left out: an object member whose value is {@code null} counts as not given, at every depth (the server side
 * of the MCP SDK drops such a member before the tool binding sees the arguments, so a digest that kept it would never
 * match the one of the hook). Nothing else is normalised; in particular the rules that make two requests the same request
 * for idempotency (for example empty text equals absent) are not applied, because the signature has to cover exactly what
 * was sent.
 *
 * <p>SHA-256 is {@code MessageDigest} of the JDK (FIPS 180-4); the canonical form is RFC 8785
 * (https://www.rfc-editor.org/rfc/rfc8785).
 */
public final class ArgumentsDigest {

    /** The argument that carries the attestation itself: it cannot be part of what it signs. */
    public static final String ATTESTATION_MEMBER = "_hostAttestation";

    private static final Pattern FORMAT = Pattern.compile("[0-9a-f]{64}");

    private ArgumentsDigest() {
    }

    /**
     * The digest of the arguments of a call.
     *
     * @param arguments the arguments object as a Java model of JSON (see {@link JsonCanonicalizer}); the member
     *                  {@code _hostAttestation} is ignored, in any form, at the top level only
     * @throws IllegalArgumentException for {@code null}, and for a value that is not JSON
     */
    public static String of(Map<?, ?> arguments) {
        if (arguments == null) {
            throw new IllegalArgumentException("the arguments of a call must be an object");
        }
        byte[] canonical = JsonCanonicalizer.canonicalizeArguments(arguments, ATTESTATION_MEMBER)
                .getBytes(StandardCharsets.UTF_8);
        return Sha256Hex.of(canonical);
    }

    /** Whether the text is a digest in the form {@link #of} writes: 64 lower-case hex characters. */
    public static boolean isWellFormed(String text) {
        return text != null && FORMAT.matcher(text).matches();
    }
}
