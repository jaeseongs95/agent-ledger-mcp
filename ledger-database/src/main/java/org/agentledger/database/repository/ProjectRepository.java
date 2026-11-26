// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.database.repository;

import java.util.List;
import java.util.Optional;
import org.agentledger.domain.model.Project;
import org.agentledger.domain.value.ProjectCode;
import org.agentledger.domain.value.ProjectId;
import org.springframework.data.repository.Repository;

/** Projects: insert and read. The counters are advanced by {@link IdAllocator}. */
public interface ProjectRepository extends Repository<Project, ProjectId>, Insertable<Project>, ProjectPin {

    Optional<Project> findById(ProjectId id);

    Optional<Project> findByCode(ProjectCode code);

    boolean existsById(ProjectId id);

    List<Project> findAllByOrderByCode();
}
