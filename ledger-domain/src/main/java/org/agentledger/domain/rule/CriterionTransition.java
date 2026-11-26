// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.domain.rule;

import org.agentledger.domain.value.ActorId;

/** One recorded criterion transition (from history), oldest first in {@link CriterionReports}. */
public record CriterionTransition(CriterionState from, CriterionState to, ActorId author) {
}
