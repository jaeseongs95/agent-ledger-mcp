// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.database.repository;

import java.util.List;
import java.util.Optional;
import org.veridger.domain.model.Project;
import org.veridger.domain.value.ProjectCode;
import org.veridger.domain.value.ProjectId;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;

/** Projects: add, read and rename. The counters are advanced by {@link IdAllocator}. */
public interface ProjectRepository extends Repository<Project, ProjectId>, Insertable<Project>, ProjectPin {

    Optional<Project> findById(ProjectId id);

    Optional<Project> findByCode(ProjectCode code);

    boolean existsById(ProjectId id);

    List<Project> findAllByOrderByCode();

    @Modifying
    @Query("UPDATE project SET display_name = :name, version = version + 1 WHERE id = :id AND version = :version")
    boolean rename(ProjectId id, String name, long version);
}
