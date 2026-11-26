// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.domain.model;

import org.agentledger.domain.rule.HistoryEntity;
import org.agentledger.domain.rule.LinkKind;
import org.agentledger.domain.value.ActorId;

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
