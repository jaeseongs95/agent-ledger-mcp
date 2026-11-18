package org.pgledger.application.read;

import java.time.Instant;
import java.util.List;
import org.pgledger.database.repository.CriterionQueryRepository;
import org.pgledger.database.repository.IssueQueryRepository;
import org.pgledger.database.repository.IssueSummary;
import org.pgledger.database.repository.SpecQueryRepository;
import org.pgledger.database.repository.SpecSummary;
import org.pgledger.database.repository.TaskQueryRepository;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationReason;
import org.pgledger.domain.rule.AttentionKind;
import org.pgledger.domain.rule.CriterionState;
import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.rule.IssueStatus;
import org.pgledger.domain.rule.SpecStatus;
import org.pgledger.domain.rule.TaskState;
import org.pgledger.domain.value.CodeValue;
import org.pgledger.domain.value.CriterionId;
import org.pgledger.domain.value.IssueId;
import org.pgledger.domain.value.MaxRows;
import org.pgledger.domain.value.PageCursor;
import org.pgledger.domain.value.ProjectId;
import org.pgledger.domain.value.SpecId;
import org.pgledger.domain.value.TaskId;
import org.pgledger.domain.value.TextRule;
import org.pgledger.domain.value.Tokens;
import org.pgledger.domain.value.UtcInstant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Project-scoped keyset lists of issues, specifications, tasks, and milestone criteria.
 */
@Service
@Transactional(readOnly = true)
public class ListService {

    public record IssuesQuery(String project, String status, String severity, String category, String createdFrom,
            String createdTo, String owner, String attention, String order, Number maxRows, String cursor) {

        public IssuesQuery(String project, String status, String severity, String category, String owner,
                String attention, Number maxRows, String cursor) {
            this(project, status, severity, category, null, null, owner, attention, null, maxRows, cursor);
        }

        public IssuesQuery {
            project = Texts.nullIfBlank(project);
            status = Texts.nullIfBlank(status);
            severity = Texts.nullIfBlank(severity);
            category = Texts.nullIfBlank(category);
            createdFrom = Texts.nullIfBlank(createdFrom);
            createdTo = Texts.nullIfBlank(createdTo);
            owner = Texts.nullIfBlank(owner);
            attention = Texts.nullIfBlank(attention);
            order = Texts.nullIfBlank(order);
        }
    }

    public record SpecsQuery(String project, String status, String parentId, String priority, String owner,
            String attention, Number maxRows, String cursor) {

        public SpecsQuery {
            project = Texts.nullIfBlank(project);
            status = Texts.nullIfBlank(status);
            parentId = Texts.nullIfBlank(parentId);
            priority = Texts.nullIfBlank(priority);
            owner = Texts.nullIfBlank(owner);
            attention = Texts.nullIfBlank(attention);
        }
    }

    public record TasksQuery(String project, String state, String owner, String attention, Number maxRows,
            String cursor) {

        public TasksQuery {
            project = Texts.nullIfBlank(project);
            state = Texts.nullIfBlank(state);
            owner = Texts.nullIfBlank(owner);
            attention = Texts.nullIfBlank(attention);
        }
    }

    public record CriteriaQuery(String project, String state, String milestone, Number maxRows, String cursor) {

        public CriteriaQuery {
            project = Texts.nullIfBlank(project);
            state = Texts.nullIfBlank(state);
            milestone = Texts.nullIfBlank(milestone);
        }
    }

    public record IssueItem(String id, String title, String category, String severity, String status,
            String mergedInto, String owner, long version, Instant createdAt, Instant updatedAt) {
    }

    public record SpecItem(String id, String parentId, String title, String status, String priority,
            String ownerActorId, long version, Instant createdAt, Instant updatedAt) {
    }

    public record TaskItem(String id, String title, String state, String ownerActorId, long version,
            Instant createdAt, Instant updatedAt) {
    }

    public record CriterionItem(String id, String milestoneName, String goalLabel, String code, String criterionText,
            String state, long version) {
    }

    private static final int MAX_OWNER_CHARS = 200;

    private final IssueQueryRepository issues;
    private final SpecQueryRepository specs;
    private final TaskQueryRepository tasks;
    private final CriterionQueryRepository criteria;
    private final Cursors cursors;

    public ListService(IssueQueryRepository issues, SpecQueryRepository specs, TaskQueryRepository tasks,
            CriterionQueryRepository criteria, Cursors cursors) {
        this.issues = issues;
        this.specs = specs;
        this.tasks = tasks;
        this.criteria = criteria;
        this.cursors = cursors;
    }

    public Page<IssueItem> issues(IssuesQuery q) {
        ProjectId project = requireProject(q.project());
        IssueStatus status = q.status() == null ? null : Tokens.parseName("status", q.status(), IssueStatus.values());
        CodeValue severity = q.severity() == null ? null : CodeValue.of(q.severity());
        CodeValue category = q.category() == null ? null : CodeValue.of(q.category());
        Instant from = q.createdFrom() == null ? null : UtcInstant.parse("created_from", q.createdFrom()).value();
        Instant to = q.createdTo() == null ? null : UtcInstant.parse("created_to", q.createdTo()).value();
        String owner = checkOwner(q.owner());
        AttentionKind attention = parseAttention(q.attention(), HistoryEntity.ISSUE);
        IssueQueryRepository.Order order = q.order() == null ? IssueQueryRepository.Order.NEWEST_FIRST
                : Tokens.parseName("order", q.order(), IssueQueryRepository.Order.values());
        MaxRows maxRows = cursors.maxRows(q.maxRows());
        IssueQueryRepository.Filter filter = new IssueQueryRepository.Filter(status, severity, category, from, to,
                owner, attention);
        Object criteriaKey = List.of(project.value(), String.valueOf(filter), order.name());
        var page = cursors.decode(q.cursor(), PageCursor.Kind.ISSUES, criteriaKey)
                .map(ListService::issuePosition)
                .map(after -> issues.page(project, filter, maxRows, order, after))
                .orElseGet(() -> issues.page(project, filter, maxRows, order, null));
        return cursors.page(page, PageCursor.Kind.ISSUES, criteriaKey,
                ListService::toIssueItem,
                r -> new UtcInstant(r.createdAt()).toString(),
                r -> r.id().value());
    }

    public Page<SpecItem> specs(SpecsQuery q) {
        ProjectId project = requireProject(q.project());
        SpecStatus status = q.status() == null ? null : Tokens.parseName("status", q.status(), SpecStatus.values());
        SpecId parentId = q.parentId() == null ? null : SpecId.of(q.parentId());
        String priority = q.priority() == null ? null : TextRule.PRIORITY.required(q.priority());
        String owner = checkOwner(q.owner());
        AttentionKind attention = parseAttention(q.attention(), HistoryEntity.SPEC);
        MaxRows maxRows = cursors.maxRows(q.maxRows());
        SpecQueryRepository.Filter filter = new SpecQueryRepository.Filter(status, parentId, priority, owner,
                attention);
        Object criteriaKey = List.of(project.value(), String.valueOf(filter));
        var page = cursors.decode(q.cursor(), PageCursor.Kind.SPECS, criteriaKey)
                .map(ListService::specPosition)
                .map(after -> specs.page(project, filter, maxRows, after))
                .orElseGet(() -> specs.page(project, filter, maxRows, null));
        return cursors.page(page, PageCursor.Kind.SPECS, criteriaKey,
                ListService::toSpecItem,
                r -> new UtcInstant(r.createdAt()).toString(),
                r -> r.id().value());
    }

    public Page<TaskItem> tasks(TasksQuery q) {
        ProjectId project = requireProject(q.project());
        TaskState state = q.state() == null ? null : Tokens.parseName("state", q.state(), TaskState.values());
        String owner = checkOwner(q.owner());
        AttentionKind attention = parseAttention(q.attention(), HistoryEntity.TASK);
        MaxRows maxRows = cursors.maxRows(q.maxRows());
        TaskQueryRepository.Filter filter = new TaskQueryRepository.Filter(state, owner, attention);
        Object criteriaKey = List.of(project.value(), String.valueOf(filter));
        var page = cursors.decode(q.cursor(), PageCursor.Kind.TASKS, criteriaKey)
                .map(ListService::taskPosition)
                .map(after -> tasks.page(project, filter, maxRows, after))
                .orElseGet(() -> tasks.page(project, filter, maxRows, null));
        return cursors.page(page, PageCursor.Kind.TASKS, criteriaKey,
                r -> new TaskItem(r.id().value(), r.title(), r.state().name(), r.ownerActorId(), r.version(),
                        r.createdAt(), r.updatedAt()),
                r -> new UtcInstant(r.createdAt()).toString(),
                r -> r.id().value());
    }

    public Page<CriterionItem> criteria(CriteriaQuery q) {
        ProjectId project = requireProject(q.project());
        CriterionState state = q.state() == null ? null
                : Tokens.parseName("state", q.state(), CriterionState.values());
        MaxRows maxRows = cursors.maxRows(q.maxRows());
        CriterionQueryRepository.Filter filter = new CriterionQueryRepository.Filter(state, q.milestone());
        Object criteriaKey = List.of(project.value(), String.valueOf(filter));
        var page = cursors.decode(q.cursor(), PageCursor.Kind.CRITERIA, criteriaKey)
                .map(ListService::criterionPosition)
                .map(after -> criteria.page(project, filter, maxRows, after))
                .orElseGet(() -> criteria.page(project, filter, maxRows, null));
        return cursors.page(page, PageCursor.Kind.CRITERIA, criteriaKey,
                r -> new CriterionItem(r.id().value(), r.milestone(), r.goalLabel(), r.code(),
                        Texts.orEmpty(r.criterionText()), r.state().name(), r.version()),
                r -> null,
                r -> r.id().value());
    }

    private static ProjectId requireProject(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ValidationException.invalid("project", ValidationReason.REQUIRED);
        }
        return ProjectId.of(raw);
    }

    private static String checkOwner(String owner) {
        if (owner != null && owner.codePointCount(0, owner.length()) > MAX_OWNER_CHARS) {
            throw ValidationException.invalid("owner", ValidationReason.MUST_BE_AT_MOST_CHARS,
                    Integer.toString(MAX_OWNER_CHARS));
        }
        return owner;
    }

    private static AttentionKind parseAttention(String raw, HistoryEntity entity) {
        if (raw == null) {
            return null;
        }
        List<AttentionKind> allowedKinds = java.util.Arrays.stream(AttentionKind.values())
                .filter(k -> k.entity() == entity)
                .toList();
        AttentionKind parsed = Tokens.parseName("attention", raw, AttentionKind.values());
        if (parsed.entity() != entity) {
            throw ValidationException.invalid("attention", ValidationReason.UNKNOWN_VALUE,
                    allowedKinds.stream().map(Enum::name).toList(), raw);
        }
        return parsed;
    }

    private static IssueItem toIssueItem(IssueSummary r) {
        return new IssueItem(r.id().value(), r.title(), r.category().value(), r.severity().value(), r.status().name(),
                r.mergedInto() == null ? null : r.mergedInto().value(), r.owner(), r.version(), r.createdAt(),
                r.updatedAt());
    }

    private static SpecItem toSpecItem(SpecSummary r) {
        return new SpecItem(r.id().value(), r.parentId() == null ? null : r.parentId().value(), r.title(),
                r.status().name(), r.priority(), r.ownerActorId(), r.version(), r.createdAt(), r.updatedAt());
    }

    private static IssueQueryRepository.Position issuePosition(Cursors.Position pos) {
        try {
            if (pos.sortKey() == null) {
                throw ValidationException.invalid("cursor", ValidationReason.CURSOR_DAMAGED);
            }
            return new IssueQueryRepository.Position(UtcInstant.parse("cursor", pos.sortKey()).value(),
                    IssueId.of(pos.id()));
        } catch (ValidationException invalid) {
            throw ValidationException.invalid("cursor", ValidationReason.CURSOR_DAMAGED, invalid);
        }
    }

    private static SpecQueryRepository.Position specPosition(Cursors.Position pos) {
        try {
            if (pos.sortKey() == null) {
                throw ValidationException.invalid("cursor", ValidationReason.CURSOR_DAMAGED);
            }
            return new SpecQueryRepository.Position(UtcInstant.parse("cursor", pos.sortKey()).value(),
                    SpecId.of(pos.id()));
        } catch (ValidationException invalid) {
            throw ValidationException.invalid("cursor", ValidationReason.CURSOR_DAMAGED, invalid);
        }
    }

    private static TaskQueryRepository.Position taskPosition(Cursors.Position pos) {
        try {
            if (pos.sortKey() == null) {
                throw ValidationException.invalid("cursor", ValidationReason.CURSOR_DAMAGED);
            }
            return new TaskQueryRepository.Position(UtcInstant.parse("cursor", pos.sortKey()).value(),
                    TaskId.of(pos.id()));
        } catch (ValidationException invalid) {
            throw ValidationException.invalid("cursor", ValidationReason.CURSOR_DAMAGED, invalid);
        }
    }

    private static CriterionId criterionPosition(Cursors.Position pos) {
        if (pos.sortKey() != null) {
            throw ValidationException.invalid("cursor", ValidationReason.CURSOR_DAMAGED);
        }
        try {
            return CriterionId.of(pos.id());
        } catch (ValidationException invalid) {
            throw ValidationException.invalid("cursor", ValidationReason.CURSOR_DAMAGED, invalid);
        }
    }
}
