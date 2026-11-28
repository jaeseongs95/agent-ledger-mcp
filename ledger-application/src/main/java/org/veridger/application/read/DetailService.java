// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application.read;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.veridger.database.repository.CriterionRepository;
import org.veridger.database.repository.DeliverableRepository;
import org.veridger.database.repository.EntityLookupRepository;
import org.veridger.database.repository.HistoryQueryRepository;
import org.veridger.database.repository.IssueCaseRepository;
import org.veridger.database.repository.IssueRepository;
import org.veridger.database.repository.LinkRepository;
import org.veridger.database.repository.MergeChainRepository;
import org.veridger.database.repository.ProjectVersionRepository;
import org.veridger.database.repository.SpecRepository;
import org.veridger.database.repository.TaskRepository;
import org.veridger.domain.error.LookupCode;
import org.veridger.domain.error.LookupException;
import org.veridger.domain.model.Criterion;
import org.veridger.domain.model.CriterionLink;
import org.veridger.domain.model.Deliverable;
import org.veridger.domain.model.Issue;
import org.veridger.domain.model.IssueCase;
import org.veridger.domain.model.IssueTaskLink;
import org.veridger.domain.model.Spec;
import org.veridger.domain.model.Task;
import org.veridger.domain.rule.DeliverableTarget;
import org.veridger.domain.rule.HistoryEntity;
import org.veridger.domain.rule.HistoryOp;
import org.veridger.domain.rule.RelationEnd;
import org.veridger.domain.rule.SpecReports;
import org.veridger.domain.rule.SpecStatus;
import org.veridger.domain.rule.SpecTransition;
import org.veridger.domain.rule.TaskReports;
import org.veridger.domain.rule.TaskState;
import org.veridger.domain.rule.TaskTransition;
import org.veridger.domain.value.CriterionId;
import org.veridger.domain.value.IssueId;
import org.veridger.domain.value.SpecId;
import org.veridger.domain.value.TaskId;
import org.veridger.domain.value.Tokens;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Full details of one issue, one specification, one task, or one milestone criterion, including deliverables, links,
 * observations, and recent history.
 */
@Service
@Transactional(readOnly = true)
public class DetailService {

    public static final int RECENT_CHANGES = 5;

    public record LastChange(Instant at, String author, String login, String assurance, String op, String reason) {
    }

    public record DeliverableView(long id, String kind, String deliverableRef, String location, String summary,
            String checkRef, String checkResult, Instant checkedAt, String attachmentId, String by, Instant at) {
    }

    public record TaskRef(String task, String state, String owner) {
    }

    public record CaseView(String source, String issue, Long caseId, String observation, String evidence,
            String sourceQuote, Instant observedAt, String by, Instant at) {
    }

    public record RelationView(String kind, String other, RelationEnd end) {
    }

    public record ChangeSummary(long id, Instant at, String author, String op, String reason) {
    }

    public record IssueDetail(String id, String projectId, String title, String category, String severity,
            String status, String mergedInto, String symptom, String rootCause, String ownerActorId, long version,
            Instant updatedAt, String createdBy, String resolvedBy, Instant resolvedAt, LastChange lastChange,
            List<DeliverableView> deliverables, List<TaskRef> tasks, List<String> specs, List<String> criteria,
            List<CaseView> cases, List<RelationView> relations, List<ChangeSummary> recentChanges,
            List<String> projectVersions) {
    }

    public record SpecDetail(String id, String projectId, String parentId, String title, String summary,
            String acceptanceCriteria, String priority, String status, String ownerActorId, String originRef,
            String originQuote, long version, Instant createdAt, Instant updatedAt, String createdBy,
            String completedBy, LastChange lastChange, List<DeliverableView> deliverables, List<String> childSpecs,
            List<String> tasks, List<String> issues, List<String> criteria, List<ChangeSummary> recentChanges) {
    }

    public record TaskDetail(String id, String projectId, String title, String purpose, String successCondition,
            String originRef, String originQuote, String state, String ownerActorId, long version, Instant createdAt,
            Instant updatedAt, String createdBy, String completedBy, List<DeliverableView> deliverables,
            List<String> issues, List<String> specs, List<String> criteria) {
    }

    public record CriterionDetail(String id, String projectId, String milestoneName, String goalLabel, String code,
            String criterionText, String state, long version, String createdBy, LastChange lastChange,
            List<String> issues, List<String> specs, List<String> tasks, List<ChangeSummary> recentChanges) {
    }

    private final IssueRepository issues;
    private final SpecRepository specs;
    private final TaskRepository tasks;
    private final CriterionRepository criteria;
    private final DeliverableRepository deliverables;
    private final IssueCaseRepository cases;
    private final LinkRepository links;
    private final MergeChainRepository mergeChain;
    private final HistoryQueryRepository historyQueries;
    private final EntityLookupRepository entityLookup;
    private final ProjectVersionRepository projectVersions;

    public DetailService(IssueRepository issues, SpecRepository specs, TaskRepository tasks,
            CriterionRepository criteria, DeliverableRepository deliverables, IssueCaseRepository cases,
            LinkRepository links, MergeChainRepository mergeChain, HistoryQueryRepository historyQueries,
            EntityLookupRepository entityLookup, ProjectVersionRepository projectVersions) {
        this.issues = issues;
        this.specs = specs;
        this.tasks = tasks;
        this.criteria = criteria;
        this.deliverables = deliverables;
        this.cases = cases;
        this.links = links;
        this.mergeChain = mergeChain;
        this.historyQueries = historyQueries;
        this.entityLookup = entityLookup;
        this.projectVersions = projectVersions;
    }

    private record HistoryDigest(LastChange lastChange, List<ChangeSummary> recentChanges) {
    }

    public record Details(List<IssueDetail> issues, List<SpecDetail> specs, List<TaskDetail> tasks,
            List<CriterionDetail> criteria) {
    }

    public Details details(List<GetService.Item> items) {
        return new Details(detailsOf(items, HistoryEntity.ISSUE, this::issue),
                detailsOf(items, HistoryEntity.SPEC, this::spec), detailsOf(items, HistoryEntity.TASK, this::task),
                detailsOf(items, HistoryEntity.CRITERION, this::criterion));
    }

    private static <T> List<T> detailsOf(List<GetService.Item> items, HistoryEntity entity,
            Function<String, T> read) {
        return items.stream().filter(item -> Objects.equals(entity.token(), item.entity()))
                .map(item -> read.apply(item.id())).toList();
    }

    public IssueDetail issue(String rawId) {
        IssueId id = IssueId.of(rawId);
        Issue i = issues.findById(id).orElseThrow(() -> new LookupException(LookupCode.NOT_FOUND,
                List.of(HistoryEntity.ISSUE.token(), rawId)));
        List<Deliverable> delivRows = deliverables.findByTargetEntityAndTargetIdOrderById(
                DeliverableTarget.ISSUE, rawId);
        Deliverable lastDeliv = delivRows.isEmpty() ? null : delivRows.getLast();
        List<TaskRef> taskRefs = taskRefsOf(links.tasksOf(id));
        List<RelationView> relations = links.relationsOf(id).stream().map(r -> new RelationView(r.relation().value(),
                (Objects.equals(r.a(), id) ? r.b() : r.a()).value(),
                Objects.equals(r.a(), id) ? RelationEnd.A : RelationEnd.B)).toList();
        HistoryDigest digest = recentDigest(HistoryEntity.ISSUE, rawId);
        return new IssueDetail(rawId, i.projectId().value(), i.title(), i.category().value(), i.severity().value(),
                i.status().name(), i.mergedInto() == null ? null : i.mergedInto().value(), Texts.orEmpty(i.symptom()),
                Texts.orEmpty(i.rootCause()), i.ownerActorId() != null ? i.ownerActorId().value()
                        : lastDeliv == null ? null : lastDeliv.recordedBy().value(),
                i.version(), i.updatedAt(), i.createdBy().value(),
                lastDeliv == null ? null : lastDeliv.recordedBy().value(),
                lastDeliv == null ? null : lastDeliv.recordedAt(), digest.lastChange(),
                toDeliverableViews(delivRows), taskRefs, links.specsOfIssue(id), links.criteriaOfIssue(id),
                cases(i), relations, digest.recentChanges(),
                projectVersions.namesForIssue(i.projectId().value(), id.value()));
    }

    public SpecDetail spec(String rawId) {
        SpecId id = SpecId.of(rawId);
        Spec s = specs.findById(id).orElseThrow(() -> new LookupException(LookupCode.NOT_FOUND,
                List.of(HistoryEntity.SPEC.token(), rawId)));
        List<Deliverable> delivRows = deliverables.findByTargetEntityAndTargetIdOrderById(
                DeliverableTarget.SPEC, rawId);
        List<SpecTransition> transitions = historyQueries.transitionsOfEntity(HistoryEntity.SPEC, rawId).stream()
                .flatMap(row -> Tokens.find(row.fromState(), SpecStatus.values())
                        .flatMap(from -> Tokens.find(row.toState(), SpecStatus.values())
                                .map(to -> new SpecTransition(from, to, row.authorId())))
                        .stream())
                .toList();
        String completedBy = new SpecReports(transitions)
                .latestCompletion()
                .map(t -> t.author().value())
                .orElse(delivRows.isEmpty() ? null : delivRows.getLast().recordedBy().value());
        HistoryDigest digest = recentDigest(HistoryEntity.SPEC, rawId);
        List<String> children = specs.findByParentId(id).stream().map(child -> child.id().value()).sorted().toList();
        return new SpecDetail(rawId, s.projectId().value(), s.parentId() == null ? null : s.parentId().value(),
                s.title(), Texts.orEmpty(s.summary()), Texts.orEmpty(s.acceptanceCriteria()), s.priority(),
                s.status().name(), s.ownerActorId() == null ? null : s.ownerActorId().value(), s.originRef(),
                s.originQuote(), s.version(), s.createdAt(), s.updatedAt(), s.createdBy().value(), completedBy,
                digest.lastChange(), toDeliverableViews(delivRows), children,
                links.tasksOfSpec(id), links.issuesOfSpec(id), links.criteriaOfSpec(id),
                digest.recentChanges());
    }

    public TaskDetail task(String rawId) {
        TaskId id = TaskId.of(rawId);
        Task t = tasks.findById(id).orElseThrow(() -> new LookupException(LookupCode.NOT_FOUND,
                List.of(HistoryEntity.TASK.token(), rawId)));
        List<Deliverable> delivRows = deliverables.findByTargetEntityAndTargetIdOrderById(
                DeliverableTarget.TASK, rawId);
        List<TaskTransition> transitions = historyQueries.transitionsOfEntity(HistoryEntity.TASK, rawId).stream()
                .flatMap(row -> Tokens.find(row.fromState(), TaskState.values())
                        .flatMap(from -> Tokens.find(row.toState(), TaskState.values())
                                .map(to -> new TaskTransition(from, to, row.authorId())))
                        .stream())
                .toList();
        String completedBy = new TaskReports(transitions)
                .latestReport()
                .map(report -> report.author().value())
                .orElse(delivRows.isEmpty() ? null : delivRows.getLast().recordedBy().value());
        return new TaskDetail(rawId, t.projectId().value(), t.title(), Texts.orEmpty(t.purpose()),
                Texts.orEmpty(t.successCondition()), t.originRef(), t.originQuote(), t.state().name(),
                t.ownerActorId() == null ? null : t.ownerActorId().value(), t.version(), t.createdAt(),
                t.updatedAt(), t.createdBy().value(), completedBy, toDeliverableViews(delivRows),
                links.issuesOfTask(id), links.specsOfTask(id), links.criteriaOfTask(id));
    }

    public CriterionDetail criterion(String rawId) {
        CriterionId id = CriterionId.of(rawId);
        Criterion c = criteria.findById(id).orElseThrow(() -> new LookupException(LookupCode.NOT_FOUND,
                List.of(HistoryEntity.CRITERION.token(), rawId)));
        List<CriterionLink> linked = links.linksOf(id);
        List<String> linkedIssues = linked.stream().filter(l -> l.issueId() != null)
                .map(l -> l.issueId().value()).sorted().toList();
        List<String> linkedSpecs = linked.stream().filter(l -> l.specId() != null)
                .map(l -> l.specId().value()).sorted().toList();
        List<String> linkedTasks = linked.stream().filter(l -> l.taskId() != null)
                .map(l -> l.taskId().value()).sorted().toList();
        HistoryDigest digest = recentDigest(HistoryEntity.CRITERION, rawId);
        return new CriterionDetail(rawId, c.projectId().value(), c.milestone(), c.goalLabel(), c.code(),
                Texts.orEmpty(c.criterionText()), c.state().name(), c.version(), c.createdBy().value(),
                digest.lastChange(), linkedIssues, linkedSpecs, linkedTasks, digest.recentChanges());
    }

    private List<TaskRef> taskRefsOf(List<IssueTaskLink> taskLinks) {
        if (taskLinks.isEmpty()) {
            return List.of();
        }
        if (entityLookup != null) {
            List<String> ids = taskLinks.stream().map(link -> link.taskId().value()).toList();
            Map<String, EntityLookupRepository.TaskSummaryRow> byId = new HashMap<>();
            for (EntityLookupRepository.TaskSummaryRow row : entityLookup.taskSummaries(ids)) {
                byId.put(row.id(), row);
            }
            List<TaskRef> refs = new ArrayList<>(taskLinks.size());
            for (IssueTaskLink link : taskLinks) {
                EntityLookupRepository.TaskSummaryRow row = byId.get(link.taskId().value());
                if (row != null) {
                    refs.add(new TaskRef(row.id(), row.state(), row.ownerActorId()));
                }
            }
            return refs;
        }
        return taskLinks.stream().map(link -> tasks.findById(link.taskId()))
                .flatMap(java.util.Optional::stream).map(t -> new TaskRef(t.id().value(), t.state().name(),
                        t.ownerActorId() == null ? null : t.ownerActorId().value())).toList();
    }

    private static List<DeliverableView> toDeliverableViews(List<Deliverable> rows) {
        return rows.stream().map(d -> new DeliverableView(d.id(), d.kind().value(), d.deliverableRef(), d.location(),
                d.summary(), d.checkRef(), d.checkResult() == null ? null : d.checkResult().token(), d.checkedAt(),
                d.attachmentId(), d.recordedBy().value(), d.recordedAt())).toList();
    }

    private HistoryDigest recentDigest(HistoryEntity entity, String rawId) {
        List<HistoryQueryRepository.SummaryRow> rows = historyQueries.newestSummariesOfEntity(entity, rawId, false,
                RECENT_CHANGES);
        if (rows.isEmpty()) {
            return new HistoryDigest(null, List.of());
        }
        List<ChangeSummary> recent = new ArrayList<>(rows.size());
        LastChange last = null;
        for (HistoryQueryRepository.SummaryRow row : rows) {
            recent.add(summary(row));
            if (last == null && row.op() != HistoryOp.IMPORT) {
                last = lastChange(row);
            }
        }
        if (last == null && rows.size() == RECENT_CHANGES) {
            List<HistoryQueryRepository.SummaryRow> newestNonImport = historyQueries.newestSummariesOfEntity(entity,
                    rawId, true, 1);
            if (!newestNonImport.isEmpty()) {
                last = lastChange(newestNonImport.getFirst());
            }
        }
        return new HistoryDigest(last, recent);
    }

    private List<CaseView> cases(Issue root) {
        List<CaseView> out = new ArrayList<>();
        List<IssueId> sources = new ArrayList<>(List.of(root.id()));
        sources.addAll(mergeChain.mergedInto(root.id()));
        for (IssueId source : sources) {
            for (IssueCase c : cases.findByIssueIdOrderById(source)) {
                out.add(new CaseView("case", source.value(), c.id(), c.observation(), c.evidence(), c.sourceQuote(),
                        c.observedAt(), c.recordedBy().value(), c.recordedAt()));
            }
            if (!Objects.equals(source, root.id())) {
                Issue merged = issues.findById(source).orElseThrow();
                out.add(new CaseView("merged_symptom", source.value(), null, Texts.orEmpty(merged.symptom()), null,
                        null, null, null, merged.createdAt()));
            }
        }
        out.sort(Comparator.comparing((CaseView c) -> c.observedAt() != null ? c.observedAt() : c.at())
                .thenComparing(c -> c.caseId() == null ? Long.MIN_VALUE : c.caseId())
                .thenComparing(CaseView::issue));
        return out;
    }

    private static LastChange lastChange(HistoryQueryRepository.SummaryRow h) {
        return new LastChange(h.at(), h.authorId().value(), h.login(), h.actorAssurance().name(), h.op().name(),
                h.reason());
    }

    private static ChangeSummary summary(HistoryQueryRepository.SummaryRow h) {
        return new ChangeSummary(h.id(), h.at(), h.authorId().value(), h.op().name(), h.reason());
    }
}
