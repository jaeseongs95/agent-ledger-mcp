// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.application.write;

import java.util.List;
import java.util.Optional;
import org.agentledger.application.gate.ProjectPinner;
import org.agentledger.application.gate.Resolution;
import org.agentledger.application.gate.Target;
import org.agentledger.database.repository.CriterionRepository;
import org.agentledger.database.repository.IssueRepository;
import org.agentledger.database.repository.ProjectRepository;
import org.agentledger.database.repository.SpecRepository;
import org.agentledger.database.repository.TaskRepository;
import org.agentledger.domain.error.ConflictCode;
import org.agentledger.domain.error.ConflictException;
import org.agentledger.domain.error.LookupCode;
import org.agentledger.domain.error.LookupException;
import org.agentledger.domain.model.Criterion;
import org.agentledger.domain.model.Issue;
import org.agentledger.domain.model.Project;
import org.agentledger.domain.model.Spec;
import org.agentledger.domain.model.Task;
import org.agentledger.domain.rule.HistoryEntity;
import org.agentledger.domain.rule.Scope;
import org.agentledger.domain.value.CriterionId;
import org.agentledger.domain.value.IssueId;
import org.agentledger.domain.value.ProjectId;
import org.agentledger.domain.value.SpecId;
import org.agentledger.domain.value.TaskId;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

/**
 * Loads rows for write operations and performs optimistic-locking saves ({@code @Version}) without explicit locks.
 */
@Component
public class Rows {

    private static final int MAX_PIN_ATTEMPTS = 16;

    private final ProjectRepository projects;
    private final ProjectPinner projectPinner;
    private final IssueRepository issues;
    private final SpecRepository specs;
    private final TaskRepository tasks;
    private final CriterionRepository criteria;

    public Rows(ProjectRepository projects, ProjectPinner projectPinner, IssueRepository issues, SpecRepository specs,
            TaskRepository tasks, CriterionRepository criteria) {
        this.projects = projects;
        this.projectPinner = projectPinner;
        this.issues = issues;
        this.specs = specs;
        this.tasks = tasks;
        this.criteria = criteria;
    }

    public Optional<Issue> findIssue(IssueId id) {
        return issues.findById(id);
    }

    public Optional<Spec> findSpec(SpecId id) {
        return specs.findById(id);
    }

    public Optional<Task> findTask(TaskId id) {
        return tasks.findById(id);
    }

    public Optional<Criterion> findCriterion(CriterionId id) {
        return criteria.findById(id);
    }

    public Resolution issueTarget(IssueId id, Scope scope) {
        return issues.findById(id).map(row -> Resolution.of(new Target(row.projectId(), scope)))
                .orElseGet(() -> Resolution.unresolved(notFound(HistoryEntity.ISSUE.token(), id.value())));
    }

    public Resolution specTarget(SpecId id, Scope scope) {
        return specs.findById(id).map(row -> Resolution.of(new Target(row.projectId(), scope)))
                .orElseGet(() -> Resolution.unresolved(notFound(HistoryEntity.SPEC.token(), id.value())));
    }

    public Resolution taskTarget(TaskId id, Scope scope) {
        return tasks.findById(id).map(row -> Resolution.of(new Target(row.projectId(), scope)))
                .orElseGet(() -> Resolution.unresolved(notFound(HistoryEntity.TASK.token(), id.value())));
    }

    public Resolution criterionTarget(CriterionId id, Scope scope) {
        return criteria.findById(id).map(row -> Resolution.of(new Target(row.projectId(), scope)))
                .orElseGet(() -> Resolution.unresolved(notFound(HistoryEntity.CRITERION.token(), id.value())));
    }

    public Project project(ProjectId id) {
        return projects.findById(id).orElseThrow(() -> notFound("project", id.value()));
    }

    public Issue issue(IssueId id) {
        return issues.findById(id).orElseThrow(() -> notFound(HistoryEntity.ISSUE.token(), id.value()));
    }

    public Spec spec(SpecId id) {
        return specs.findById(id).orElseThrow(() -> notFound(HistoryEntity.SPEC.token(), id.value()));
    }

    public Task task(TaskId id) {
        return tasks.findById(id).orElseThrow(() -> notFound(HistoryEntity.TASK.token(), id.value()));
    }

    public Criterion criterion(CriterionId id) {
        return criteria.findById(id).orElseThrow(() -> notFound(HistoryEntity.CRITERION.token(), id.value()));
    }

    /** Pins a project row at its current version in a bounded loop to serialize spec hierarchy mutations. */
    public void pinProject(ProjectId id) {
        projectPinner.pin(id);
    }

    /** Pins an existing issue row for the transaction without altering version or updated_at. */
    public boolean pinIssue(IssueId id) {
        return issues.pinRow(id);
    }

    /** Pins a spec in its current project in a bounded loop and returns the pinned project id. */
    public ProjectId pinSpec(SpecId id) {
        for (int attempt = 0; attempt < MAX_PIN_ATTEMPTS; attempt++) {
            ProjectId current = spec(id).projectId();
            if (specs.pinProject(id, current)) {
                return current;
            }
        }
        throw new ConflictException(ConflictCode.CONCURRENT_MODIFICATION,
                List.of(HistoryEntity.SPEC.token(), id.value()));
    }

    /** Attempts once to pin a spec in {@code project}, returning false if a concurrent move changed it. */
    public boolean pinSpecInProject(SpecId id, ProjectId project) {
        return specs.pinProject(id, project);
    }

    /** Pins a task in its current project in a bounded loop and returns the pinned project id. */
    public ProjectId pinTask(TaskId id) {
        for (int attempt = 0; attempt < MAX_PIN_ATTEMPTS; attempt++) {
            ProjectId current = task(id).projectId();
            if (tasks.pinProject(id, current)) {
                return current;
            }
        }
        throw new ConflictException(ConflictCode.CONCURRENT_MODIFICATION,
                List.of(HistoryEntity.TASK.token(), id.value()));
    }

    /** Pins a milestone criterion in its current project in a bounded loop and returns the pinned project id. */
    public ProjectId pinCriterion(CriterionId id) {
        for (int attempt = 0; attempt < MAX_PIN_ATTEMPTS; attempt++) {
            ProjectId current = criterion(id).projectId();
            if (criteria.pinProject(id, current)) {
                return current;
            }
        }
        throw new ConflictException(ConflictCode.CONCURRENT_MODIFICATION,
                List.of(HistoryEntity.CRITERION.token(), id.value()));
    }

    /** Guards an issue entering MERGED via its owning {@link IssueRepository}. */
    public boolean guardMergeSource(IssueId id) {
        return issues.guardMergeSource(id);
    }

    /** Guards an issue receiving a MERGED link via its owning {@link IssueRepository}. */
    public boolean guardMergeTarget(IssueId id) {
        return issues.guardMergeTarget(id);
    }

    /** Guards an issue entering VERIFIED via its owning {@link IssueRepository}. */
    public boolean guardVerifyCandidate(IssueId id) {
        return issues.guardVerifyCandidate(id);
    }

    public Issue issueForWrite(IssueId id, Long expectedVersion) {
        Issue row = issues.findById(id).orElseThrow(() -> notFound(HistoryEntity.ISSUE.token(), id.value()));
        requireVersion(HistoryEntity.ISSUE.token(), id.value(), expectedVersion, row.version());
        return row;
    }

    public Spec specForWrite(SpecId id, Long expectedVersion) {
        Spec row = specs.findById(id).orElseThrow(() -> notFound(HistoryEntity.SPEC.token(), id.value()));
        requireVersion(HistoryEntity.SPEC.token(), id.value(), expectedVersion, row.version());
        return row;
    }

    public Task taskForWrite(TaskId id, Long expectedVersion) {
        Task row = tasks.findById(id).orElseThrow(() -> notFound(HistoryEntity.TASK.token(), id.value()));
        requireVersion(HistoryEntity.TASK.token(), id.value(), expectedVersion, row.version());
        return row;
    }

    public Criterion criterionForWrite(CriterionId id, Long expectedVersion) {
        Criterion row = criteria.findById(id)
                .orElseThrow(() -> notFound(HistoryEntity.CRITERION.token(), id.value()));
        requireVersion(HistoryEntity.CRITERION.token(), id.value(), expectedVersion, row.version());
        return row;
    }

    public Issue saveIssue(Issue after) {
        try {
            return issues.save(after);
        } catch (OptimisticLockingFailureException e) {
            throw versionConflict(HistoryEntity.ISSUE.token(), after.id().value(), after.version(), issues
                    .findById(after.id()).map(Issue::version)
                    .orElseThrow(() -> notFound(HistoryEntity.ISSUE.token(), after.id().value())), e);
        }
    }

    public Spec saveSpec(Spec after) {
        try {
            return specs.save(after);
        } catch (OptimisticLockingFailureException e) {
            throw versionConflict(HistoryEntity.SPEC.token(), after.id().value(), after.version(), specs
                    .findById(after.id()).map(Spec::version)
                    .orElseThrow(() -> notFound(HistoryEntity.SPEC.token(), after.id().value())), e);
        }
    }

    public Task saveTask(Task after) {
        try {
            return tasks.save(after);
        } catch (OptimisticLockingFailureException e) {
            throw versionConflict(HistoryEntity.TASK.token(), after.id().value(), after.version(), tasks
                    .findById(after.id()).map(Task::version)
                    .orElseThrow(() -> notFound(HistoryEntity.TASK.token(), after.id().value())), e);
        }
    }

    public Criterion saveCriterion(Criterion after) {
        try {
            return criteria.save(after);
        } catch (OptimisticLockingFailureException e) {
            throw versionConflict(HistoryEntity.CRITERION.token(), after.id().value(), after.version(), criteria
                    .findById(after.id()).map(Criterion::version)
                    .orElseThrow(() -> notFound(HistoryEntity.CRITERION.token(), after.id().value())), e);
        }
    }

    private static ConflictException versionConflict(String entity, String id, long expected, long current,
            Throwable cause) {
        return new ConflictException(ConflictCode.VERSION_CONFLICT,
                List.of(entity, id, Long.toString(expected), Long.toString(current)), cause);
    }

    public static LookupException notFound(String what, String id) {
        return new LookupException(LookupCode.NOT_FOUND, List.of(what, id));
    }

    private static void requireVersion(String entity, String id, Long expected, long current) {
        if (expected != null && expected != current) {
            throw new ConflictException(ConflictCode.VERSION_CONFLICT,
                    List.of(entity, id, Long.toString(expected), Long.toString(current)));
        }
    }
}
