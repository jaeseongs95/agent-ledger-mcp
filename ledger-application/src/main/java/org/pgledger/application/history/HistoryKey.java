// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.application.history;

import org.pgledger.domain.rule.HistoryEntity;

/**
 * Extracts the owning entity id from a history row's {@code entity_id}.
 *
 * <p>Deliverables use {@code <targetEntity>:<targetId>@<kind>:<ref>}, links and cases use {@code |} composition.
 */
public record HistoryKey(HistoryEntity entity, String id) {

    private static final char DELIVERABLE_SEPARATOR = '@';
    private static final char ENTITY_PREFIX_SEPARATOR = ':';
    private static final char LINK_SEPARATOR = '|';

    /** The primary owning entity id of this history row. */
    public String ownerId() {
        return switch (entity) {
            case ISSUE, SPEC, TASK, CRITERION -> id;
            case DELIVERABLE -> deliverableTargetId(id);
            case ISSUE_TASK, ISSUE_SPEC, SPEC_TASK, ISSUE_RELATION, ISSUE_CASE, CRITERION_LINK ->
                before(LINK_SEPARATOR);
        };
    }

    /** Formats the composite history id of a deliverable row. */
    public static String deliverableEntityId(String targetEntityToken, String targetId, String kind,
            String deliverableRef) {
        return targetEntityToken + ENTITY_PREFIX_SEPARATOR + targetId + DELIVERABLE_SEPARATOR + kind
                + ENTITY_PREFIX_SEPARATOR + deliverableRef;
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
