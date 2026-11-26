// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application.history;

import org.veridger.domain.model.HistoryEntry;
import org.veridger.domain.rule.HistoryEntity;
import org.veridger.domain.value.Sha256;

/**
 * Extracts the owning entity id from a history row's {@code entity_id}.
 *
 * <p>Deliverables use {@code <targetEntity>:<targetId>@<kind>:<ref>}, links and cases use {@code |} composition.
 */
public record HistoryKey(HistoryEntity entity, String id) {

    private static final char DELIVERABLE_SEPARATOR = '@';
    private static final char ENTITY_PREFIX_SEPARATOR = ':';
    private static final char LINK_SEPARATOR = '|';
    private static final String HASH_SEPARATOR = "#";

    /** The primary owning entity id of this history row. */
    public String ownerId() {
        return switch (entity) {
            case ISSUE, SPEC, TASK, CRITERION -> id;
            case DELIVERABLE -> deliverableTargetId(id);
            case ISSUE_TASK, ISSUE_SPEC, SPEC_TASK, ISSUE_RELATION, ISSUE_CASE, CRITERION_LINK ->
                before(LINK_SEPARATOR);
        };
    }

    /**
     * Formats the composite history id of a deliverable row, {@code <target>:<id>@<kind>:<ref>}. The column holds
     * {@link HistoryEntry#MAX_ENTITY_ID_LENGTH} characters and a reference may be longer than what is left of them after
     * the prefix (target, id and kind take up to 160 of the 300, the reference up to 300 itself): a composite that fits
     * is kept as it is (every row written before is one), and one that does not keeps the prefix, the first characters
     * of the reference, {@code #} and the SHA-256 of the whole reference, so that it is still one key per deliverable
     * (the same function names it when it is read) and ends at the length of the column. The whole reference is in
     * the {@code changes} of the row.
     */
    public static String deliverableEntityId(String targetEntityToken, String targetId, String kind,
            String deliverableRef) {
        String prefix = targetEntityToken + ENTITY_PREFIX_SEPARATOR + targetId + DELIVERABLE_SEPARATOR + kind
                + ENTITY_PREFIX_SEPARATOR;
        int prefixLength = prefix.codePointCount(0, prefix.length());
        if (prefixLength + deliverableRef.codePointCount(0, deliverableRef.length()) <= HistoryEntry.MAX_ENTITY_ID_LENGTH) {
            return prefix + deliverableRef;
        }
        String digest = Sha256.ofText(deliverableRef).hex();
        int room = HistoryEntry.MAX_ENTITY_ID_LENGTH - prefixLength - HASH_SEPARATOR.length() - digest.length();
        return prefix + deliverableRef.substring(0, deliverableRef.offsetByCodePoints(0, room)) + HASH_SEPARATOR
                + digest;
    }

    private static String deliverableTargetId(String raw) {
        int at = raw.indexOf(DELIVERABLE_SEPARATOR);
        String left = at < 0 ? raw : raw.substring(0, at);
        int colon = left.indexOf(ENTITY_PREFIX_SEPARATOR);
        return colon < 0 ? left : left.substring(colon + 1);
    }

    private String before(char separator) {
        int at = id.indexOf(separator);
        return at < 0 ? id : id.substring(0, at);
    }
}
