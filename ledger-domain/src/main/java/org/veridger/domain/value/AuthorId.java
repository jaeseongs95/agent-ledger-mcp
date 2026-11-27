// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.domain.value;

import java.util.List;
import java.util.regex.Pattern;
import org.veridger.domain.error.TrustCode;
import org.veridger.domain.error.TrustException;

/**
 * A declared author: {@code <client>:<session-id>} (client lower case, 1..32 characters; session 1..128 of
 * {@code [A-Za-z0-9._-]}) or the owner {@code user:owner}. {@code null} means no caller session was resolved.
 */
public record AuthorId(String value) implements ActorId {

    private static final String OWNER_VALUE = "user:owner";

    private static final char SEPARATOR = ':';

    public static final AuthorId OWNER = new AuthorId(OWNER_VALUE);

    private static final Pattern SESSION_FORMAT = Pattern.compile("[a-z][a-z0-9-]{0,31}:[A-Za-z0-9._-]{1,128}");

    public AuthorId {
        if (value == null) {
            throw new TrustException(TrustCode.HOST_ATTESTATION_REQUIRED, List.of());
        }
        if (!matches(value)) {
            throw new TrustException(TrustCode.AUTHOR_ID_FORMAT, List.of(value));
        }
    }

    public static AuthorId of(String value) {
        return new AuthorId(value);
    }

    /** True for {@code user:owner} and for session authors; {@code user:} is reserved for the owner. */
    public static boolean matches(String value) {
        return value != null && (OWNER_VALUE.equals(value) || isSessionId(value));
    }

    /** True only for {@code <vendor>:<session-id>} session authors; rejects {@code user:owner} and any {@code user:*}. */
    public static boolean isSessionId(String value) {
        return value != null && SESSION_FORMAT.matcher(value).matches() && !value.startsWith("user:");
    }

    /** The client namespace part before the colon, or {@code user} for the owner. */
    public String client() {
        return value.substring(0, value.indexOf(SEPARATOR));
    }

    /** The part after the colon: the session id of a session author, {@code owner} for the owner. */
    public String session() {
        return value.substring(value.indexOf(SEPARATOR) + 1);
    }
}
