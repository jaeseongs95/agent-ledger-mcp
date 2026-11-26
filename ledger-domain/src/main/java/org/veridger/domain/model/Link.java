// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.domain.model;

import org.veridger.domain.rule.HistoryEntity;
import org.veridger.domain.rule.LinkKind;
import org.veridger.domain.value.ActorId;

/**
 * A link between two entities. Every variant has a canonical, unambiguous history id and the structural kind;
 * links are the only rows that are deleted (an UNLINK), and the history keeps the story.
 */
public sealed interface Link permits IssueTaskLink, IssueSpecLink, SpecTaskLink, IssueRelation, CriterionLink {

    LinkKind kind();

    HistoryEntity historyEntity();

    /** The {@code entity_id} of the history rows of this link. */
    String historyId();

    ActorId createdBy();
}
