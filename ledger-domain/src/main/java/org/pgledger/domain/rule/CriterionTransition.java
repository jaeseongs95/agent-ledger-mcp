package org.pgledger.domain.rule;

import org.pgledger.domain.value.ActorId;

/** One recorded criterion transition (from history), oldest first in {@link CriterionReports}. */
public record CriterionTransition(CriterionState from, CriterionState to, ActorId author) {
}
