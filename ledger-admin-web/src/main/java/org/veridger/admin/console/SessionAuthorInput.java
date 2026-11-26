// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.admin.console;

import java.util.List;

/**
 * Splits and combines the host and session ID inputs of the {@code /grants} UI while keeping the stored {@code V11}
 * {@code author_id} format ({@code <host>:<session_id>}) and single-field HTTP requests unchanged.
 */
public final class SessionAuthorInput {

    /** Selector token that reveals the free-text custom host input. */
    public static final String CUSTOM_HOST = "custom";

    /** Decomposed form state for rendering or recovering the split host and session ID fields. */
    public record Split(String hostSelect, String customHost, String sessionId) {

        public boolean isCustom() {
            return CUSTOM_HOST.equals(hostSelect);
        }
    }

    private SessionAuthorInput() {
    }

    /**
     * Combines the split {@code host} / {@code customHost} / {@code sessionId} inputs into {@code <host>:<session_id>},
     * falling back to {@code combinedAuthor} when {@code sessionId} is omitted (for session-scoped actions and
     * single-field callers). Returns {@code null} when neither input supplies a non-blank value so missing-author
     * validation reports {@code VALIDATION_FAILED} rather than a format error.
     */
    public static String resolve(String host, String customHost, String sessionId, String combinedAuthor) {
        String trimmedCombined = trimToEmpty(combinedAuthor);
        String trimmedSession = trimToEmpty(sessionId);
        if (!trimmedSession.isEmpty()) {
            String effectiveHost = CUSTOM_HOST.equals(trimToEmpty(host))
                    ? trimToEmpty(customHost)
                    : trimToEmpty(host);
            if (!effectiveHost.isEmpty()) {
                String prefix = effectiveHost + ":";
                if (trimmedSession.startsWith(prefix)) {
                    trimmedSession = trimmedSession.substring(prefix.length());
                }
                return effectiveHost + ":" + trimmedSession;
            }
            return trimmedCombined.isEmpty() ? trimmedSession : trimmedCombined;
        }
        return trimmedCombined.isEmpty() ? null : trimmedCombined;
    }

    /** Splits a stored {@code <host>:<session_id>} author into selector state for form prefill and 4xx recovery. */
    public static Split split(String author, List<String> knownHosts) {
        String defaultHost = defaultHost(knownHosts);
        String text = trimToEmpty(author);
        if (text.isEmpty()) {
            return new Split(defaultHost, "", "");
        }
        int colon = text.indexOf(':');
        if (colon <= 0) {
            return new Split(defaultHost, "", text);
        }
        String host = text.substring(0, colon);
        String session = text.substring(colon + 1);
        if (knownHosts != null && knownHosts.contains(host)) {
            return new Split(host, "", session);
        }
        return new Split(CUSTOM_HOST, host, session);
    }

    /** Returns the first configured host preset, or {@link #CUSTOM_HOST} when no presets are configured. */
    public static String defaultHost(List<String> knownHosts) {
        return knownHosts == null || knownHosts.isEmpty() ? CUSTOM_HOST : knownHosts.getFirst();
    }

    /** Extracts the host prefix before the first colon, or empty string when absent. */
    public static String hostPart(String author) {
        if (author == null) {
            return "";
        }
        int colon = author.indexOf(':');
        return colon > 0 ? author.substring(0, colon) : "";
    }

    /** Extracts the session ID after the first colon, or the full text when absent. */
    public static String sessionPart(String author) {
        if (author == null) {
            return "";
        }
        int colon = author.indexOf(':');
        return colon > 0 ? author.substring(colon + 1) : author;
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
