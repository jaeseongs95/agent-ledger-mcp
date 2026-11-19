// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.database.repository;

import org.pgledger.domain.value.ProjectId;

/**
 * Repository fragment of projects: pins a project row within the caller's transaction to serialize spec parent/move
 * hierarchy mutations without altering {@code version}.
 */
public interface ProjectPin {

    /**
     * Pins {@code project} at {@code version} for this transaction.
     *
     * @return true when the row exists at {@code version}, false if a concurrent project edit moved it
     */
    boolean pinVersion(ProjectId project, long version);
}
