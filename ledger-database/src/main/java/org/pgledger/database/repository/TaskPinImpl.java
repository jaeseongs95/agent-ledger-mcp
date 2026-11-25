// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.database.repository;

import org.pgledger.domain.value.ProjectId;
import org.pgledger.domain.value.TaskId;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Spring Data fragment implementation of {@link TaskPin}. */
public class TaskPinImpl implements TaskPin {

    private final JdbcClient jdbc;

    public TaskPinImpl(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean pinProject(TaskId task, ProjectId project) {
        return jdbc.sql("UPDATE task SET project_id = :project WHERE id = :id AND project_id = :project")
                .param("project", project.value()).param("id", task.value()).update() == 1;
    }
}
