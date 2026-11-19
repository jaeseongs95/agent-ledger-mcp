// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.application.write;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongFunction;
import java.util.function.Supplier;
import org.pgledger.application.gate.Lookup;
import org.pgledger.application.gate.Target;
import org.pgledger.application.gate.WriteGate;
import org.pgledger.application.history.ChangeSet;
import org.pgledger.application.history.HistoryWriter;
import org.pgledger.application.history.Snapshot;
import org.pgledger.application.receipt.ReceiptService;
import org.pgledger.application.write.WriteResult.SaveResult;
import org.pgledger.database.repository.CriterionRepository;
import org.pgledger.database.repository.IdAllocator;
import org.pgledger.database.repository.IssueRepository;
import org.pgledger.database.repository.LinkRepository;
import org.pgledger.database.repository.SpecRepository;
import org.pgledger.database.repository.TaskRepository;
import org.pgledger.domain.error.ConflictCode;
import org.pgledger.domain.error.ConflictException;
import org.pgledger.domain.error.TransitionCode;
import org.pgledger.domain.error.TransitionException;
import org.pgledger.domain.error.ValidationCode;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationIssue;
import org.pgledger.domain.error.ValidationReason;
import org.pgledger.domain.model.Criterion;
import org.pgledger.domain.model.CriterionFields;
import org.pgledger.domain.model.EntityFields;
import org.pgledger.domain.model.Issue;
import org.pgledger.domain.model.IssueFields;
import org.pgledger.domain.model.Project;
import org.pgledger.domain.model.ReceiptOperation;
import org.pgledger.domain.model.Spec;
import org.pgledger.domain.model.SpecFields;
import org.pgledger.domain.model.Task;
import org.pgledger.domain.model.TaskFields;
import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.rule.HistoryOp;
import org.pgledger.domain.rule.Scope;
import org.pgledger.domain.value.AuthorId;
import org.pgledger.domain.value.CanonicalPayload;
import org.pgledger.domain.value.CriterionId;
import org.pgledger.domain.value.IssueId;
import org.pgledger.domain.value.ProjectId;
import org.pgledger.domain.value.SpecId;
import org.pgledger.domain.value.TaskId;
import org.pgledger.domain.value.Tokens;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/** Creates (expected version 0) or edits an issue, specification, task, or milestone criterion. */
@Component
public class SaveOperation extends WriteOperation<SaveCommand, SaveResult> {

    private static final String CLEARED = "\u0000";

    private final WriteGate gate;
    private final EntityEditors editors;
    private final Rows rows;
    private final CodeRules codeRules;
    private final IssueRepository issues;
    private final SpecRepository specs;
    private final TaskRepository tasks;
    private final CriterionRepository criteria;
    private final LinkRepository links;
    private final IdAllocator ids;
    private final HistoryWriter history;
    private final Clock clock;

    public SaveOperation(WriteGate gate, ReceiptService receipts, EntityEditors editors, Rows rows,
            CodeRules codeRules, IssueRepository issues, SpecRepository specs, TaskRepository tasks,
            CriterionRepository criteria, LinkRepository links, IdAllocator ids, HistoryWriter history, Clock clock) {
        super(gate, receipts, ReceiptOperation.SAVE, SaveResult.class);
        this.gate = gate;
        this.editors = editors;
        this.rows = rows;
        this.codeRules = codeRules;
        this.issues = issues;
        this.specs = specs;
        this.tasks = tasks;
        this.criteria = criteria;
        this.links = links;
        this.ids = ids;
        this.history = history;
        this.clock = clock;
    }

    @Override
    protected Lookup resolve(SaveCommand c) {
        return Lookup.afterActor(() -> targets(c));
    }

    private List<Target> targets(SaveCommand c) {
        HistoryEntity entity = entity(c.entity());
        Scope scope = entity.writeScope();
        if (c.isCreate()) {
            String project = c.mentionedProject();
            if (project == null || project.isBlank()) {
                throw ValidationException.invalid("project_id", ValidationReason.REQUIRED);
            }
            ProjectId projectId = ProjectId.of(project);
            rows.project(projectId);
            return List.of(new Target(projectId, scope));
        }
        String id = c.requireId();
        ProjectId current = switch (entity) {
            case ISSUE -> rows.issue(IssueId.of(id)).projectId();
            case SPEC -> rows.spec(SpecId.of(id)).projectId();
            case TASK -> rows.task(TaskId.of(id)).projectId();
            case CRITERION -> rows.criterion(CriterionId.of(id)).projectId();
            case DELIVERABLE, ISSUE_TASK, ISSUE_SPEC, SPEC_TASK, ISSUE_RELATION, CRITERION_LINK, ISSUE_CASE ->
                throw new IllegalStateException("entity() refused it");
        };
        String moved = c.mentionedProject();
        if (entity == HistoryEntity.ISSUE || moved == null || moved.isBlank() || Objects.equals(moved, current.value())) {
            return List.of(new Target(current, scope));
        }
        ProjectId target = ProjectId.of(moved);
        rows.project(target);
        return List.of(new Target(current, scope), new Target(target, scope));
    }

    private static HistoryEntity entity(String token) {
        return Tokens.parse("entity", token, EntityEditors.SAVEABLE);
    }

    @Override
    protected Prepared<SaveResult> prepare(SaveCommand c) {
        HistoryEntity entity = entity(c.entity());
        boolean create = c.isCreate();
        EntityFields fields = editors.parse(entity, c.fields());
        if (create) {
            fields.requireCreatable();
        } else {
            fields.requireEditable();
            if (fields instanceof IssueFields i && i.projectId().mentioned()) {
                throw new TransitionException(TransitionCode.PROJECT_MOVE_FORBIDDEN, List.of());
            }
        }
        Map<String, String> mentioned = editors.mentioned(fields);
        CanonicalPayload.Fields payloadFields = CanonicalPayload.Fields.empty();
        for (Map.Entry<String, String> field : mentioned.entrySet()) {
            String value = field.getValue() == null ? (create ? null : CLEARED) : field.getValue();
            payloadFields = payloadFields.text(field.getKey(), value);
        }
        CanonicalPayload.Fields payload = CanonicalPayload.Fields.empty().text("entity", entity.token())
                .text("id", c.id() == null || c.id().isBlank() ? null : c.id())
                .text("v", Long.toString(c.expectedVersion() == null ? 0 : c.expectedVersion()))
                .with("fields", payloadFields);
        return new Prepared<>(payload, ctx -> create ? create(ctx, entity, fields, c.id(), mentioned)
                : edit(ctx, entity, fields, c.requireId(), c.expectedVersion(), mentioned));
    }

    private SaveResult create(WriteContext ctx, HistoryEntity entity, EntityFields fields, String explicitId,
            Map<String, String> mentioned) {
        Instant now = clock.instant();
        Project project = rows.project(fields.projectId().orNull());
        String id;
        long version;
        switch (fields) {
            case IssueFields f -> {
                IssueId issueId = issueId(project, explicitId);
                codeRules.checkIssue(null, f.category().orNull(), f.severity().orNull());
                Issue saved = inserted(() -> issues.save(Issue.open(issueId, f, now)), HistoryEntity.ISSUE,
                        issueId.value());
                id = saved.id().value();
                version = saved.version();
            }
            case SpecFields f -> {
                SpecId specId = specId(project, explicitId);
                if (f.parentId().orNull() != null) {
                    pinProject(project.id());
                }
                validateSpecParent(specId, project.id(), f.parentId().orNull());
                Spec saved = inserted(() -> specs.save(Spec.create(specId, f, now)), HistoryEntity.SPEC,
                        specId.value());
                id = saved.id().value();
                version = saved.version();
            }
            case TaskFields f -> {
                TaskId taskId = taskId(project, explicitId);
                Task saved = inserted(() -> tasks.save(Task.plan(taskId, f, now)), HistoryEntity.TASK,
                        taskId.value());
                id = saved.id().value();
                version = saved.version();
            }
            case CriterionFields f -> {
                if (explicitId == null || explicitId.isBlank()) {
                    throw ValidationException.invalid("id", ValidationReason.REQUIRED_FOR_CRITERION);
                }
                CriterionId criterionId = CriterionId.of(explicitId);
                criteria.findByIdKey(criterionId.key()).ifPresent(existing -> {
                    throw duplicate(HistoryEntity.CRITERION, existing.id().value());
                });
                if (criteria.findByProjectIdAndMilestoneAndCode(project.id(), f.milestone().orNull(),
                        f.code().orNull()).isPresent()) {
                    throw duplicate(HistoryEntity.CRITERION, criterionId.value());
                }
                Criterion saved = inserted(() -> criteria.save(Criterion.open(criterionId, f)),
                        HistoryEntity.CRITERION, criterionId.value());
                id = saved.id().value();
                version = saved.version();
            }
        }
        ChangeSet changes = ChangeSet.empty();
        for (Map.Entry<String, String> field : mentioned.entrySet()) {
            if (!"".equals(field.getValue())) {
                changes = changes.with(field.getKey(), null, field.getValue());
            }
        }
        long historyId = history.append(ctx, HistoryWriter.Entry.of(entity, id, HistoryOp.CREATE, changes));
        return new SaveResult(WriteStatus.COMMITTED, entity.token(), id, version, historyId, ctx.author().value(),
                ctx.login());
    }

    private IssueId issueId(Project project, String explicit) {
        if (explicit == null || explicit.isBlank()) {
            IssueId candidate;
            do {
                candidate = IssueId.of(project.code(), ids.nextIssueNo(project.code()));
            } while (issues.existsById(candidate));
            return candidate;
        }
        IssueId candidate = IssueId.of(explicit);
        if (!Objects.equals(candidate.projectCode(), project.code())) {
            String prefix = project.code().value() + "-I-";
            throw new ValidationException(ValidationCode.ID_PREFIX_MISMATCH, List.of(explicit, prefix),
                    List.of(new ValidationIssue("id", ValidationReason.MUST_START_WITH, List.of(prefix))), Map.of());
        }
        requireCanonical(explicit, candidate.number(), number -> IssueId.of(project.code(), number).value());
        if (issues.existsById(candidate)) {
            throw duplicate(HistoryEntity.ISSUE, explicit);
        }
        ids.takeIssueNo(project.code(), candidate.number()).ifPresent(next -> {
            throw idAhead(explicit, IssueId.of(project.code(), next).value());
        });
        return candidate;
    }

    private SpecId specId(Project project, String explicit) {
        if (explicit == null || explicit.isBlank()) {
            SpecId candidate;
            do {
                candidate = SpecId.issue(project.code(), ids.nextSpecNo(project.code()));
            } while (specs.findByIdKey(candidate.key()).isPresent());
            return candidate;
        }
        SpecId candidate = SpecId.of(explicit);
        if (candidate.number().isPresent() && candidate.numberIn(project.code()).isEmpty()) {
            String prefix = project.code().value() + "-S-";
            throw new ValidationException(ValidationCode.ID_PREFIX_MISMATCH, List.of(explicit, prefix),
                    List.of(new ValidationIssue("id", ValidationReason.MUST_START_WITH, List.of(prefix))), Map.of());
        }
        candidate.numberIn(project.code()).ifPresent(number -> requireCanonical(explicit, number,
                numberOfId -> SpecId.issue(project.code(), numberOfId).value()));
        specs.findByIdKey(candidate.key()).ifPresent(existing -> {
            throw duplicate(HistoryEntity.SPEC, existing.id().value());
        });
        candidate.numberIn(project.code()).ifPresent(number -> ids.takeSpecNo(project.code(), number).ifPresent(next -> {
            throw idAhead(explicit, SpecId.issue(project.code(), next).value());
        }));
        return candidate;
    }

    private TaskId taskId(Project project, String explicit) {
        if (explicit == null || explicit.isBlank()) {
            TaskId candidate;
            do {
                candidate = TaskId.issue(project.code(), ids.nextTaskNo(project.code()));
            } while (tasks.findByIdKey(candidate.key()).isPresent());
            return candidate;
        }
        TaskId candidate = TaskId.of(explicit);
        if (candidate.number().isPresent() && candidate.numberIn(project.code()).isEmpty()) {
            String prefix = project.code().value() + "-T-";
            throw new ValidationException(ValidationCode.ID_PREFIX_MISMATCH, List.of(explicit, prefix),
                    List.of(new ValidationIssue("id", ValidationReason.MUST_START_WITH, List.of(prefix))), Map.of());
        }
        candidate.numberIn(project.code()).ifPresent(number -> requireCanonical(explicit, number,
                numberOfId -> TaskId.issue(project.code(), numberOfId).value()));
        tasks.findByIdKey(candidate.key()).ifPresent(existing -> {
            throw duplicate(HistoryEntity.TASK, existing.id().value());
        });
        candidate.numberIn(project.code()).ifPresent(number -> ids.takeTaskNo(project.code(), number).ifPresent(next -> {
            throw idAhead(explicit, TaskId.issue(project.code(), next).value());
        }));
        return candidate;
    }

    private void validateSpecParent(SpecId selfId, ProjectId projectId, SpecId parentId) {
        if (parentId == null) {
            return;
        }
        if (Objects.equals(selfId.key(), parentId.key())) {
            throw ValidationException.invalid("parent_id", ValidationReason.MUST_DIFFER_FROM, "id");
        }
        Spec parent = rows.spec(parentId);
        if (!Objects.equals(parent.projectId(), projectId)) {
            throw ValidationException.invalid("parent_id", ValidationReason.MUST_MATCH, projectId.value());
        }
        Set<String> seen = new HashSet<>();
        seen.add(selfId.key());
        Spec cursor = parent;
        while (true) {
            if (!seen.add(cursor.id().key())) {
                throw ValidationException.invalid("parent_id", ValidationReason.MUST_DIFFER_FROM, "id");
            }
            if (cursor.parentId() == null) {
                break;
            }
            Optional<Spec> next = specs.findById(cursor.parentId());
            if (next.isEmpty()) {
                break;
            }
            cursor = next.orElseThrow();
        }
    }

    // ceiling: project-level ANSI row lock serializes spec parent/move mutations (lock order: project -> spec).
    private void pinProject(ProjectId project) {
        while (true) {
            Project current = rows.project(project);
            if (links.pinProject(project, current.version())) {
                return;
            }
        }
    }

    private void pinSpecHierarchy(AuthorId author, SpecId spec, ProjectId targetProject, Long expectedVersion) {
        ProjectId current = rows.spec(spec).projectId();
        gate.authorize(author, new Target(current, HistoryEntity.SPEC.writeScope()));
        if (targetProject != null && !Objects.equals(targetProject, current)) {
            if (current.value().compareTo(targetProject.value()) <= 0) {
                pinProject(current);
                pinProject(targetProject);
            } else {
                pinProject(targetProject);
                pinProject(current);
            }
        } else {
            pinProject(current);
        }
        if (!links.pinSpecProject(spec, current)) {
            Spec moved = rows.spec(spec);
            gate.authorize(author, new Target(moved.projectId(), HistoryEntity.SPEC.writeScope()));
            rows.specForWrite(spec, expectedVersion);
            throw new ConflictException(ConflictCode.VERSION_CONFLICT, List.of(HistoryEntity.SPEC.token(),
                    spec.value(), Long.toString(expectedVersion == null ? -1L : expectedVersion),
                    Long.toString(moved.version())));
        }
    }

    private void pinSpec(AuthorId author, SpecId spec) {
        while (true) {
            ProjectId project = rows.spec(spec).projectId();
            gate.authorize(author, new Target(project, HistoryEntity.SPEC.writeScope()));
            if (links.pinSpecProject(spec, project)) {
                return;
            }
        }
    }

    private void pinTask(AuthorId author, TaskId task) {
        while (true) {
            ProjectId project = rows.task(task).projectId();
            gate.authorize(author, new Target(project, HistoryEntity.TASK.writeScope()));
            if (links.pinTaskProject(task, project)) {
                return;
            }
        }
    }

    private void pinCriterion(AuthorId author, CriterionId criterion) {
        while (true) {
            ProjectId project = rows.criterion(criterion).projectId();
            gate.authorize(author, new Target(project, HistoryEntity.CRITERION.writeScope()));
            if (links.pinCriterionProject(criterion, project)) {
                return;
            }
        }
    }

    private static void requireCanonical(String explicit, long number, LongFunction<String> canonical) {
        if (number < 1) {
            throw ValidationException.invalid("id", ValidationReason.MUST_BE_POSITIVE);
        }
        String written = canonical.apply(number);
        if (!Objects.equals(written, explicit)) {
            throw ValidationException.invalid("id", ValidationReason.ID_NOT_CANONICAL, written);
        }
    }

    private static ValidationException idAhead(String explicit, String nextId) {
        return new ValidationException(ValidationCode.ID_NUMBER_AHEAD, List.of(explicit, nextId),
                List.of(new ValidationIssue("id", ValidationReason.NUMBER_ABOVE_NEXT, List.of(nextId))), Map.of());
    }

    private SaveResult edit(WriteContext ctx, HistoryEntity entity, EntityFields fields, String id,
            Long expectedVersion, Map<String, String> mentioned) {
        Instant now = clock.instant();
        Map<String, String> beforeColumns;
        Map<String, String> afterColumns;
        Snapshot before;
        long version;
        switch (fields) {
            case IssueFields f -> {
                Issue row = rows.issueForWrite(IssueId.of(id), expectedVersion);
                row.requireNotVerified();
                Issue after = row.edited(f, now);
                if (after.category() == null || after.severity() == null) {
                    codeRules.checkIssue(row, after.category(), after.severity());
                }
                beforeColumns = editors.columns(row);
                afterColumns = editors.columns(after);
                requireChange(mentioned, beforeColumns, afterColumns);
                codeRules.checkIssue(row, after.category(), after.severity());
                before = Snapshot.IssueRow.of(row);
                version = rows.saveIssue(after).version();
            }
            case SpecFields f -> {
                SpecId specId = SpecId.of(id);
                if (f.projectId().mentioned() || f.parentId().mentioned()) {
                    pinSpecHierarchy(ctx.author(), specId, f.projectId().orNull(), expectedVersion);
                } else {
                    pinSpec(ctx.author(), specId);
                }
                Spec row = rows.specForWrite(specId, expectedVersion);
                row.requireNotVerified();
                Spec after = row.edited(f, now);
                if (!Objects.equals(row.projectId(), after.projectId())) {
                    if (row.id().number().isPresent() || !specs.findByParentId(row.id()).isEmpty()) {
                        throw new TransitionException(TransitionCode.PROJECT_MOVE_FORBIDDEN, List.of());
                    }
                    gate.authorize(ctx.author(), new Target(after.projectId(), HistoryEntity.SPEC.writeScope()));
                }
                validateSpecParent(row.id(), after.projectId(), after.parentId());
                beforeColumns = editors.columns(row);
                afterColumns = editors.columns(after);
                requireChange(mentioned, beforeColumns, afterColumns);
                before = Snapshot.SpecRow.of(row);
                version = rows.saveSpec(after).version();
            }
            case TaskFields f -> {
                pinTask(ctx.author(), TaskId.of(id));
                Task row = rows.taskForWrite(TaskId.of(id), expectedVersion);
                row.requireNotVerified();
                Task after = row.edited(f, now);
                if (!Objects.equals(row.projectId(), after.projectId())) {
                    if (row.id().number().isPresent()) {
                        throw new TransitionException(TransitionCode.PROJECT_MOVE_FORBIDDEN, List.of());
                    }
                    gate.authorize(ctx.author(), new Target(after.projectId(), HistoryEntity.TASK.writeScope()));
                }
                beforeColumns = editors.columns(row);
                afterColumns = editors.columns(after);
                requireChange(mentioned, beforeColumns, afterColumns);
                before = Snapshot.TaskRow.of(row);
                version = rows.saveTask(after).version();
            }
            case CriterionFields f -> {
                pinCriterion(ctx.author(), CriterionId.of(id));
                Criterion row = rows.criterionForWrite(CriterionId.of(id), expectedVersion);
                row.requireOpenForEdit();
                Criterion after = row.edited(f);
                if (!Objects.equals(row.projectId(), after.projectId())) {
                    gate.authorize(ctx.author(), new Target(after.projectId(), HistoryEntity.CRITERION.writeScope()));
                }
                beforeColumns = editors.columns(row);
                afterColumns = editors.columns(after);
                requireChange(mentioned, beforeColumns, afterColumns);
                if (!Objects.equals(row.projectId(), after.projectId())
                        || !Objects.equals(row.milestone(), after.milestone())
                        || !Objects.equals(row.code(), after.code())) {
                    criteria.findByProjectIdAndMilestoneAndCode(after.projectId(), after.milestone(), after.code())
                            .filter(existing -> !Objects.equals(existing.id(), row.id()))
                            .ifPresent(existing -> {
                                throw duplicate(HistoryEntity.CRITERION, after.id().value());
                            });
                }
                before = Snapshot.CriterionRow.of(row);
                version = rows.saveCriterion(after).version();
            }
        }
        ChangeSet changes = ChangeSet.empty();
        for (String key : mentioned.keySet()) {
            changes = changes.withIfChanged(key, beforeColumns.get(key), afterColumns.get(key));
        }
        long historyId = history.append(ctx,
                HistoryWriter.Entry.of(entity, id, HistoryOp.EDIT, changes).withBefore(before));
        return new SaveResult(WriteStatus.COMMITTED, entity.token(), id, version, historyId, ctx.author().value(),
                ctx.login());
    }

    private static void requireChange(Map<String, String> mentioned, Map<String, String> before,
            Map<String, String> after) {
        boolean changed = mentioned.keySet().stream()
                .anyMatch(k -> !Objects.equals(before.get(k), after.get(k)));
        if (!changed) {
            throw new TransitionException(TransitionCode.NOTHING_CHANGES, List.of());
        }
    }

    private static <T> T inserted(Supplier<T> insert, HistoryEntity entity, String id) {
        try {
            return insert.get();
        } catch (DuplicateKeyException e) {
            throw new ConflictException(ConflictCode.DUPLICATE_KEY, List.of(entity.token() + " " + id), e);
        }
    }

    private static ConflictException duplicate(HistoryEntity entity, String id) {
        return new ConflictException(ConflictCode.DUPLICATE_KEY, List.of(entity.token() + " " + id));
    }
}
