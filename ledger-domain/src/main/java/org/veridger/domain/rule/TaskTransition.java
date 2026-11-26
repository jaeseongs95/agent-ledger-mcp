// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.domain.rule;

import org.veridger.domain.value.ActorId;

/** One recorded task transition (from the history), oldest first in a {@link TaskReports}. */
public record TaskTransition(TaskState from, TaskState to, ActorId author) {
}
