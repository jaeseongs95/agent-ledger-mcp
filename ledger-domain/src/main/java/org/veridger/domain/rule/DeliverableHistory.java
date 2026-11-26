// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.domain.rule;

import java.util.List;
import org.veridger.domain.value.SessionKey;

/**
 * The sessions that participated in an issue, spec, or task via deliverables and creation/case reports:
 * independent verification must come from a session outside these participants.
 */
public record DeliverableHistory(List<SessionKey> contributors, List<SessionKey> reporters) {

    public static final DeliverableHistory NONE = new DeliverableHistory(List.of(), List.of());

    public DeliverableHistory {
        contributors = List.copyOf(contributors);
        reporters = List.copyOf(reporters);
    }

    public boolean hasDeliverable() {
        return !contributors.isEmpty();
    }

    /** True when {@code actor} recorded a deliverable, registered the item, or recorded an observation case on it. */
    public boolean involves(SessionKey actor) {
        return contributors.contains(actor) || reporters.contains(actor);
    }
}
