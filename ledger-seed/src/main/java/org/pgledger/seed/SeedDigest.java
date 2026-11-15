package org.pgledger.seed;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.pgledger.attestation.Sha256Hex;

/**
 * The SHA-256 digest of a set of codes: the one way the seed and the database are compared (counts and digest). The codes are
 * put in the order of group and code first, so the digest does not depend on the order they come in, and every field is part of
 * it, so a change of one character, the state or the sort order changes the digest. The hash itself is {@link Sha256Hex}, the
 * one SHA-256 helper of the product.
 */
public final class SeedDigest {

    /** Between the fields of a code; no text of a code holds it. */
    private static final char FIELD = '\u001f';
    private static final Comparator<SeedCode> ORDER = Comparator.comparing(SeedCode::group).thenComparing(SeedCode::code);

    private SeedDigest() {
    }

    /** The digest as 64 lower-case hex characters. */
    public static String of(Collection<SeedCode> codes) {
        List<SeedCode> ordered = codes.stream().sorted(ORDER).toList();
        StringBuilder lines = new StringBuilder();
        for (SeedCode code : ordered) {
            lines.append(String.join(String.valueOf(FIELD), code.group(), code.code(), text(code.nameKo()),
                    text(code.nameEn()), text(code.descriptionKo()), text(code.descriptionEn()),
                    Integer.toString(code.sortOrder()), code.state())).append('\n');
        }
        return Sha256Hex.of(lines.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** An absent text is the empty text. */
    private static String text(String value) {
        return Objects.toString(value, "");
    }
}
