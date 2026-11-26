// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application.gate;

import org.veridger.domain.rule.Scope;
import org.veridger.domain.value.ProjectId;

/** One project and the scope a write needs on it. */
public record Target(ProjectId project, Scope scope) {
}
