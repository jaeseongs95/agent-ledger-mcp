// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.domain.rule;

import org.veridger.domain.value.ActorId;

/** One recorded spec transition (from history), oldest first in {@link SpecReports}. */
public record SpecTransition(SpecStatus from, SpecStatus to, ActorId author) {
}
