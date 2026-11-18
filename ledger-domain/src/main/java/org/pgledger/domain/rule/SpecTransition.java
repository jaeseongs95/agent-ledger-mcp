package org.pgledger.domain.rule;

import org.pgledger.domain.value.ActorId;

/** One recorded spec transition (from history), oldest first in {@link SpecReports}. */
public record SpecTransition(SpecStatus from, SpecStatus to, ActorId author) {
}
