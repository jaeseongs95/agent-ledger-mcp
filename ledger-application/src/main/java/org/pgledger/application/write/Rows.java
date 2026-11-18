package org.pgledger.application.write;

import java.util.List;
import java.util.Optional;
import org.pgledger.application.gate.Resolution;
import org.pgledger.application.gate.Target;
import org.pgledger.database.repository.CriterionRepository;
import org.pgledger.database.repository.IssueRepository;
import org.pgledger.database.repository.ProjectRepository;
import org.pgledger.database.repository.SpecRepository;
import org.pgledger.database.repository.TaskRepository;
import org.pgledger.domain.error.ConflictCode;
import org.pgledger.domain.error.ConflictException;
import org.pgledger.domain.error.LookupCode;
import org.pgledger.domain.error.LookupException;
import org.pgledger.domain.model.Criterion;
import org.pgledger.domain.model.Issue;
import org.pgledger.domain.model.Project;
import org.pgledger.domain.model.Spec;
import org.pgledger.domain.model.Task;
import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.rule.Scope;
import org.pgledger.domain.value.CriterionId;
import org.pgledger.domain.value.IssueId;
import org.pgledger.domain.value.ProjectId;
import org.pgledger.domain.value.SpecId;
import org.pgledger.domain.value.TaskId;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

/**
 * Loads rows for write operations and performs optimistic-locking saves ({@code @Version}) without explicit locks.
 */
@Component
public class Rows {

    private final ProjectRepository projects;
    private final IssueRepository issues;
    private final SpecRepository specs;
    private final TaskRepository tasks;
    private final CriterionRepository criteria;

    public Rows(ProjectRepository projects, IssueRepository issues, SpecRepository specs, TaskRepository tasks,
            CriterionRepository criteria) {
        this.projects = projects;
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
