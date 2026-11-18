package org.pgledger.domain.value;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationReason;

/**
 * {@code <PROJECT_CODE>-I-<number>} (also accepts imported {@code -D-} ids): zero-padded to at least 4 digits
 * and at most {@value #MAX_DIGITS} digits so every number fits a {@code long}.
 */
public record IssueId(String value) {

    /** The most digits of a number: 18 digits always fit a {@code long}. */
    static final int MAX_DIGITS = 18;

    /** The highest number an id can hold. */
    static final long MAX_NUMBER = 999_999_999_999_999_999L;

    /** Accepts native {@code -I-} ids and previously imported {@code -D-} ids without throwing on lookup. */
    public static final Pattern FORMAT = Pattern.compile("([A-Z][A-Z0-9_]{0,31})-(?:I|D)-([0-9]{4," + MAX_DIGITS + "})");

    public IssueId {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw malformed();
        }
    }

    /** The id of a text, or empty when the text is not an issue id; nothing is thrown. */
    public static Optional<IssueId> parse(String value) {
        return value != null && FORMAT.matcher(value).matches() ? Optional.of(new IssueId(value)) : Optional.empty();
    }

    /** The failure of a text that is not an issue id. */
    public static ValidationException malformed() {
        return ValidationException.invalid("issue_id", ValidationReason.ISSUE_ID_FORMAT);
    }

    public static IssueId of(String value) {
        return new IssueId(value);
    }

    public static IssueId of(ProjectCode code, long number) {
        return new IssueId(code.value() + "-I-" + zeroPadded(number));
    }

    public ProjectCode projectCode() {
        return new ProjectCode(matcher().group(1));
    }

    public long number() {
        return Long.parseLong(matcher().group(2));
    }

    private Matcher matcher() {
        Matcher m = FORMAT.matcher(value);
        if (!m.matches()) {
            throw new IllegalStateException("validated in the constructor");
        }
        return m;
    }

    /** Zero-padded sequential id suffix (at least 4 digits). */
    public static String zeroPadded(long number) {
        if (number < 1) {
            throw ValidationException.invalid("number", ValidationReason.MUST_BE_POSITIVE);
        }
        if (number > MAX_NUMBER) {
            throw ValidationException.invalid("number", ValidationReason.OUT_OF_RANGE, "1",
                    Long.toString(MAX_NUMBER));
        }
        String digits = Long.toString(number);
        return "0".repeat(Math.max(0, 4 - digits.length())) + digits;
    }
}
