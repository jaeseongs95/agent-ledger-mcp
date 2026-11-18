package org.pgledger.domain.value;

import java.util.Optional;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.pgledger.domain.error.ValidationException;

/**
 * Id of a specification ({@link EntityId#PATTERN}, any case); when omitted on creation, the server issues
 * {@code <CODE>-S-NNNN}. {@link #key()} is case-folded to upper case for case-insensitive uniqueness.
 */
public record SpecId(String value) {

    /** The form the server issues: project code, {@code -S-}, 1 to 18 digits. */
    private static final Pattern ISSUED = Pattern.compile("([A-Z][A-Z0-9_]{0,31})-S-([0-9]{1,"
            + IssueId.MAX_DIGITS + "})");

    public SpecId {
        EntityId.requireValid("spec_id", value);
    }

    /** The id of a text, or empty when the text is not a valid id; nothing is thrown. */
    public static Optional<SpecId> parse(String value) {
        return EntityId.isValid(value) ? Optional.of(new SpecId(value)) : Optional.empty();
    }

    /** The failure of a text that is not a spec id. */
    public static ValidationException malformed() {
        return EntityId.malformed("spec_id");
    }

    public static SpecId of(String value) {
        return new SpecId(value);
    }

    /** The unique case-insensitive key stored in {@code spec.id_key}. */
    public String key() {
        return EntityId.key(value);
    }

    /** The id the server issues for spec number {@code number} of a project. */
    public static SpecId issue(ProjectCode code, long number) {
        return new SpecId(code.value() + "-S-" + IssueId.zeroPadded(number));
    }

    public OptionalLong number() {
        Matcher matcher = ISSUED.matcher(key());
        return matcher.matches() ? OptionalLong.of(Long.parseLong(matcher.group(2))) : OptionalLong.empty();
    }

    public OptionalLong numberIn(ProjectCode code) {
        return key().startsWith(code.value() + "-S-") ? number() : OptionalLong.empty();
    }
}
