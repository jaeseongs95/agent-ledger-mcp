// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.database.repository;

import org.agentledger.domain.value.CriterionId;
import org.agentledger.domain.value.ProjectId;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Spring Data fragment implementation of {@link CriterionPin}. */
public class CriterionPinImpl implements CriterionPin {

    private final JdbcClient jdbc;

    public CriterionPinImpl(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean pinProject(CriterionId criterion, ProjectId project) {
        return jdbc.sql("UPDATE milestone_criterion SET project_id = :project WHERE id = :id AND project_id = :project")
                .param("project", project.value()).param("id", criterion.value()).update() == 1;
    }
}
