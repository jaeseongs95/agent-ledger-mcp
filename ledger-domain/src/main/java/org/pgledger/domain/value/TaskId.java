package org.pgledger.domain.value;

import java.util.Optional;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.pgledger.domain.error.ValidationException;

/**
 * Id of a task: free text ({@link EntityId#PATTERN}, any case); the server issues {@code <CODE>-T-NNNN}. The number of
 * an issued id has at most 18 digits (as in {@link IssueId}), so it fits a {@code long}; a free id that does not have
 * that form has no number.
 */
public record TaskId(String value) {

    /** The form the server issues: the project code, {@code -T-}, 1 to 18 digits. */
    private static final Pattern ISSUED = Pattern.compile("([A-Z][A-Z0-9_]{0,31})-T-([0-9]{1,"
            + IssueId.MAX_DIGITS + "})");

    public TaskId {
        EntityId.requireValid("task_id", value);
    }

    /** The id of a text, or none when the text is not an id; nothing is thrown. */
    public static Optional<TaskId> parse(String value) {
        return EntityId.isValid(value) ? Optional.of(new TaskId(value)) : Optional.empty();
    }

    /** The failure of a text that is not a task id. */
    public static ValidationException malformed() {
        return EntityId.malformed("task_id");
    }

    public static TaskId of(String value) {
        return new TaskId(value);
    }

    /** The key under the UNIQUE constraint of the table: the id in upper case ({@link EntityId#key}). */
    public String key() {
        return EntityId.key(value);
    }

    /** The id the server issues for task number {@code number} of a project. */
    public static TaskId issue(ProjectCode code, long number) {
        return new TaskId(code.value() + "-T-" + IssueId.zeroPadded(number));
    }

    /**
     * The number of an id in the form the server issues, or none for any other free id; nothing is thrown. The form is judged
     * by the key of the id (its upper case, the column that makes the id unique): {@code e2e-t-0005} and {@code E2E-T-0005}
     * are the same id, so both have the number 5 and neither passes for a free text.
     */
    public OptionalLong number() {
        Matcher matcher = ISSUED.matcher(key());
        return matcher.matches() ? OptionalLong.of(Long.parseLong(matcher.group(2))) : OptionalLong.empty();
    }

    /** The number of this id when it is an issued id of the project {@code code} (in any case), otherwise none. */
    public OptionalLong numberIn(ProjectCode code) {
        return key().startsWith(code.value() + "-T-") ? number() : OptionalLong.empty();
    }
}
