// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.database.repository;

import org.pgledger.domain.value.ProjectId;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Spring Data fragment implementation of {@link ProjectPin}. */
public class ProjectPinImpl implements ProjectPin {

    private final JdbcClient jdbc;

    public ProjectPinImpl(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean pinVersion(ProjectId project, long version) {
        return jdbc.sql("UPDATE project SET version = :version WHERE id = :project AND version = :version")
                .param("project", project.value()).param("version", version).update() == 1;
    }
}
