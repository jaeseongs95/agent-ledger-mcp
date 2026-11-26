// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.database.repository;

import org.agentledger.domain.value.CriterionId;
import org.agentledger.domain.value.ProjectId;

/**
 * Repository fragment of milestone criteria: pins a criterion's current project within the caller's transaction so
 * concurrent project moves serialize with authorization and mutation checks without altering {@code version}.
 */
public interface CriterionPin {

    /**
     * Pins {@code criterion} in {@code project} for this transaction.
     *
     * @return true when {@code criterion} still belongs to {@code project}, false if a committed move changed it
     */
    boolean pinProject(CriterionId criterion, ProjectId project);
}
