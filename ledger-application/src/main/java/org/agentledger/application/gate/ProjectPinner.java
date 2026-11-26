// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.application.gate;

import java.util.List;
import org.agentledger.database.repository.ProjectRepository;
import org.agentledger.domain.error.ConflictCode;
import org.agentledger.domain.error.ConflictException;
import org.agentledger.domain.error.LookupCode;
import org.agentledger.domain.error.LookupException;
import org.agentledger.domain.model.Project;
import org.agentledger.domain.value.ProjectId;
import org.springframework.stereotype.Component;

/**
 * Takes the lock of a project row for the transaction of the caller (the first lock of the order of the locks: project, then
 * the rows of the entities, then the grants, then the numbers). The row is locked by the conditional UPDATE that writes back the
 * version it read; when a concurrent write changed the version in between, the statement affects no row and the version is read
 * again, a bounded number of times. Used by every use case that pins a project: the writes and the grants of the owner.
 */
@Component
public class ProjectPinner {

    private static final int MAX_PIN_ATTEMPTS = 16;

    private final ProjectRepository projects;

    public ProjectPinner(ProjectRepository projects) {
        this.projects = projects;
    }

    /**
     * Pins the project at its current version without altering it.
     *
     * @throws LookupException   NOT_FOUND when the project does not exist
     * @throws ConflictException CONCURRENT_MODIFICATION when the version kept changing for every attempt
     */
    public void pin(ProjectId id) {
        for (int attempt = 0; attempt < MAX_PIN_ATTEMPTS; attempt++) {
            Project current = projects.findById(id)
                    .orElseThrow(() -> new LookupException(LookupCode.NOT_FOUND, List.of("project", id.value())));
            if (projects.pinVersion(id, current.version())) {
                return;
            }
        }
        throw new ConflictException(ConflictCode.CONCURRENT_MODIFICATION, List.of("project", id.value()));
    }
}
