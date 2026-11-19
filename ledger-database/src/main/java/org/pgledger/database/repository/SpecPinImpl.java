// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.database.repository;

import org.pgledger.domain.value.ProjectId;
import org.pgledger.domain.value.SpecId;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Spring Data fragment implementation of {@link SpecPin}. */
public class SpecPinImpl implements SpecPin {

    private final JdbcClient jdbc;

    public SpecPinImpl(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean pinProject(SpecId spec, ProjectId project) {
        return jdbc.sql("UPDATE spec SET project_id = :project WHERE id = :id AND project_id = :project")
                .param("project", project.value()).param("id", spec.value()).update() == 1;
    }
}
