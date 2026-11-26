// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.application.gate;

import org.agentledger.domain.rule.Scope;
import org.agentledger.domain.value.ProjectId;

/** One project and the scope a write needs on it. */
public record Target(ProjectId project, Scope scope) {
}
