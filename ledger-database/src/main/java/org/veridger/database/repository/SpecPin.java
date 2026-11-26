// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.database.repository;

import org.veridger.domain.value.ProjectId;
import org.veridger.domain.value.SpecId;

/**
 * Repository fragment of specifications: pins a spec's current project within the caller's transaction so concurrent
 * project moves serialize with authorization and mutation checks without altering {@code version}.
 */
public interface SpecPin {

    /**
     * Pins {@code spec} in {@code project} for this transaction.
     *
     * @return true when {@code spec} still belongs to {@code project}, false if a committed move changed it
     */
    boolean pinProject(SpecId spec, ProjectId project);
}
