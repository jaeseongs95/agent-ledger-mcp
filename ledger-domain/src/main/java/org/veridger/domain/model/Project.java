// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.domain.model;

import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.ReadOnlyProperty;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.InsertOnlyProperty;
import org.springframework.data.relational.core.mapping.Table;
import org.veridger.domain.value.ActorId;
import org.veridger.domain.value.ProjectCode;
import org.veridger.domain.value.ProjectId;
import org.veridger.domain.value.TextRule;

/**
 * A project or workstream in the ledger. Sequential counters are advanced only by {@code IdAllocator}.
 */
@Table("project")
public record Project(@Id ProjectId id, ProjectCode code, String title, @ReadOnlyProperty long nextIssueNo,
        @ReadOnlyProperty long nextSpecNo, @ReadOnlyProperty long nextTaskNo, @Version long version,
        @CreatedBy @InsertOnlyProperty ActorId createdBy) {

    public Project {
        title = TextRule.PROJECT_TITLE.required(title);
    }

    /** A new project; the counters start at 0 in the database. */
    public static Project open(ProjectId id, ProjectCode code, String title) {
        return new Project(id, code, title, 0, 0, 0, 0, null);
    }
}
