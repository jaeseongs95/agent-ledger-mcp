// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.database.repository;

import org.veridger.domain.value.ProjectId;
import org.veridger.domain.value.TaskId;

/**
 * Repository fragment of tasks: pins a task's current project within the caller's transaction so concurrent project
 * moves serialize with authorization and mutation checks without altering {@code version}.
 */
public interface TaskPin {

    /**
     * Pins {@code task} in {@code project} for this transaction.
     *
     * @return true when {@code task} still belongs to {@code project}, false if a committed move changed it
     */
    boolean pinProject(TaskId task, ProjectId project);
}
