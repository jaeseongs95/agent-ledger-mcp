// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.domain.value;

import java.util.Locale;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationReason;

/**
 * Length and normalisation rule of a text field. Lengths count characters (code points), not bytes. An optional
 * field that is blank becomes {@code null}; a required field that is blank is rejected. A column never holds
 * an empty string. Fields marked trimmed are stored stripped.
 */
public enum TextRule {
    TITLE(300, false),
    SYMPTOM(4000, false),
    ROOT_CAUSE(4000, false),
    SUMMARY(4000, false),
    ACCEPTANCE_CRITERIA(4000, false),
    PRIORITY(32, true),
    PURPOSE(4000, false),
    SUCCESS_CONDITION(4000, false),
    /**
     * 20000: {@code origin_ref} is a long text column. The write limit leaves room for a long imported value so that it can be
     * saved again; the request size of the server bounds the rest.
     */
    ORIGIN_REF(20000, false),
    ORIGIN_QUOTE(2000, false),
    MILESTONE_NAME(100, false),
    GOAL_LABEL(200, false),
    /** 200: room for the long codes of imported milestone criteria. */
    CRITERION_CODE(200, false),
    CRITERION_TEXT(4000, false),
    OBSERVATION(4000, true),
    EVIDENCE(2000, true),
    SOURCE_QUOTE(2000, false),
    GRANT_NOTE(500, false),
    DELIVERABLE_REF(300, true),
    LOCATION(300, true),
    DELIVERABLE_SUMMARY(1000, true),
    /** 2000: room for external check references (review report links, pipeline URLs, approval sheets). */
    CHECK_REF(2000, true),
    EVIDENCE_URI(2000, true),
    PROJECT_TITLE(200, true),
    CODE_NAME(200, true),
    CODE_DESCRIPTION(500, false);

    private final int max;
    private final boolean trimmed;

    TextRule(int max, boolean trimmed) {
        this.max = max;
        this.trimmed = trimmed;
    }

    public String path() {
        return name().toLowerCase(Locale.ROOT);
    }

    public int max() {
        return max;
    }

    /** The value, or a validation failure when it is blank or too long. */
    public String required(String raw) {
        String value = optional(raw);
        if (value == null) {
            throw ValidationException.invalid(path(), ValidationReason.REQUIRED);
        }
        return value;
    }

    /**
     * The value of a field that has to be given and may be empty (purpose, success condition, symptom, root cause,
     * summary, acceptance criteria, criterion text): {@code null} fails with REQUIRED; blank becomes {@code ""},
     * which the row stores as NULL via {@link #emptyToNull}.
     */
    public String givenOrEmpty(String raw) {
        if (raw == null) {
            throw ValidationException.invalid(path(), ValidationReason.REQUIRED);
        }
        return raw.isBlank() ? "" : optional(raw);
    }

    /** Stores NULL for an empty string (portability across DBMSes that distinguish '' vs NULL). */
    public static String emptyToNull(String text) {
        return text == null || text.isEmpty() ? null : text;
    }

    /** The value, or {@code null} when it is absent or blank; too long fails validation. */
    public String optional(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = trimmed ? raw.strip() : raw;
        if (value.codePointCount(0, value.length()) > max) {
            throw ValidationException.invalid(path(), ValidationReason.MUST_BE_AT_MOST_CHARS, String.valueOf(max));
        }
        return value;
    }
}
