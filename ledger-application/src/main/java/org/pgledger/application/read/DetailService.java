// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.application.read;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.pgledger.application.history.SpecHistory;
import org.pgledger.application.history.TaskHistory;
import org.pgledger.database.repository.CriterionRepository;
import org.pgledger.database.repository.DeliverableRepository;
import org.pgledger.database.repository.HistoryQueryRepository;
import org.pgledger.database.repository.HistoryRepository;
import org.pgledger.database.repository.IssueCaseRepository;
import org.pgledger.database.repository.IssueRepository;
import org.pgledger.database.repository.LinkRepository;
import org.pgledger.database.repository.MergeChainRepository;
import org.pgledger.database.repository.SpecRepository;
import org.pgledger.database.repository.TaskRepository;
import org.pgledger.domain.error.LookupCode;
import org.pgledger.domain.error.LookupException;
import org.pgledger.domain.model.Criterion;
import org.pgledger.domain.model.CriterionLink;
import org.pgledger.domain.model.Deliverable;
import org.pgledger.domain.model.HistoryEntry;
import org.pgledger.domain.model.Issue;
import org.pgledger.domain.model.IssueCase;
import org.pgledger.domain.model.Spec;
import org.pgledger.domain.model.Task;
import org.pgledger.domain.rule.DeliverableTarget;
import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.rule.RelationEnd;
import org.pgledger.domain.value.CriterionId;
import org.pgledger.domain.value.IssueId;
import org.pgledger.domain.value.SpecId;
import org.pgledger.domain.value.TaskId;
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
            List<CaseView> cases, List<RelationView> relations, List<ChangeSummary> recentChanges) {
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
    private final HistoryRepository historyRows;
    private final HistoryQueryRepository historyQueries;

    public DetailService(IssueRepository issues, SpecRepository specs, TaskRepository tasks,
            CriterionRepository criteria, DeliverableRepository deliverables, IssueCaseRepository cases,
            LinkRepository links, MergeChainRepository mergeChain, HistoryRepository historyRows,
            HistoryQueryRepository historyQueries) {
        this.issues = issues;
        this.specs = specs;
        this.tasks = tasks;
        this.criteria = criteria;
        this.deliverables = deliverables;
        this.cases = cases;
        this.links = links;
        this.mergeChain = mergeChain;
        this.historyRows = historyRows;
        this.historyQueries = historyQueries;
    }

    public IssueDetail issue(String rawId) {
        IssueId id = IssueId.of(rawId);
        Issue i = issues.findById(id).orElseThrow(() -> new LookupException(LookupCode.NOT_FOUND,
                List.of(HistoryEntity.ISSUE.token(), rawId)));
        List<Deliverable> delivRows = deliverables.findByTargetEntityAndTargetIdOrderById(
                DeliverableTarget.ISSUE, rawId);
        Deliverable lastDeliv = delivRows.isEmpty() ? null : delivRows.getLast();
        List<TaskRef> taskRefs = links.tasksOf(id).stream().map(link -> tasks.findById(link.taskId()))
                .flatMap(java.util.Optional::stream).map(t -> new TaskRef(t.id().value(), t.state().name(),
                        t.ownerActorId() == null ? null : t.ownerActorId().value())).toList();
        List<RelationView> relations = links.relationsOf(id).stream().map(r -> new RelationView(r.relation().value(),
                (Objects.equals(r.a(), id) ? r.b() : r.a()).value(),
                Objects.equals(r.a(), id) ? RelationEnd.A : RelationEnd.B)).toList();
        LastChange last = newestChange(HistoryEntity.ISSUE, rawId);
        List<ChangeSummary> recent = recentChanges(HistoryEntity.ISSUE, rawId);
        return new IssueDetail(rawId, i.projectId().value(), i.title(), i.category().value(), i.severity().value(),
                i.status().name(), i.mergedInto() == null ? null : i.mergedInto().value(), Texts.orEmpty(i.symptom()),
                Texts.orEmpty(i.rootCause()), i.ownerActorId() != null ? i.ownerActorId().value()
                        : lastDeliv == null ? null : lastDeliv.recordedBy().value(),
                i.version(), i.updatedAt(), i.createdBy().value(),
                lastDeliv == null ? null : lastDeliv.recordedBy().value(),
                lastDeliv == null ? null : lastDeliv.recordedAt(), last,
                toDeliverableViews(delivRows), taskRefs, links.specsOfIssue(id), links.criteriaOfIssue(id),
                cases(i), relations, recent);
    }

    public SpecDetail spec(String rawId) {
        SpecId id = SpecId.of(rawId);
        Spec s = specs.findById(id).orElseThrow(() -> new LookupException(LookupCode.NOT_FOUND,
                List.of(HistoryEntity.SPEC.token(), rawId)));
        List<Deliverable> delivRows = deliverables.findByTargetEntityAndTargetIdOrderById(
                DeliverableTarget.SPEC, rawId);
        String completedBy = new SpecHistory(historyRows.findByEntityAndEntityIdOrderById(HistoryEntity.SPEC, rawId))
                .reports().latestCompletion()
                .map(t -> t.author().value())
                .orElse(delivRows.isEmpty() ? null : delivRows.getLast().recordedBy().value());
        List<String> children = specs.findByParentId(id).stream().map(child -> child.id().value()).sorted().toList();
        return new SpecDetail(rawId, s.projectId().value(), s.parentId() == null ? null : s.parentId().value(),
                s.title(), Texts.orEmpty(s.summary()), Texts.orEmpty(s.acceptanceCriteria()), s.priority(),
                s.status().name(), s.ownerActorId() == null ? null : s.ownerActorId().value(), s.originRef(),
                s.originQuote(), s.version(), s.createdAt(), s.updatedAt(), s.createdBy().value(), completedBy,
                newestChange(HistoryEntity.SPEC, rawId), toDeliverableViews(delivRows), children,
                links.tasksOfSpec(id), links.issuesOfSpec(id), links.criteriaOfSpec(id),
                recentChanges(HistoryEntity.SPEC, rawId));
    }

    public TaskDetail task(String rawId) {
        TaskId id = TaskId.of(rawId);
        Task t = tasks.findById(id).orElseThrow(() -> new LookupException(LookupCode.NOT_FOUND,
                List.of(HistoryEntity.TASK.token(), rawId)));
        List<Deliverable> delivRows = deliverables.findByTargetEntityAndTargetIdOrderById(
                DeliverableTarget.TASK, rawId);
        String completedBy = new TaskHistory(historyRows.findByEntityAndEntityIdOrderById(HistoryEntity.TASK, rawId))
                .reports().latestReport()
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
        return new CriterionDetail(rawId, c.projectId().value(), c.milestone(), c.goalLabel(), c.code(),
                Texts.orEmpty(c.criterionText()), c.state().name(), c.version(), c.createdBy().value(),
                newestChange(HistoryEntity.CRITERION, rawId), linkedIssues, linkedSpecs, linkedTasks,
                recentChanges(HistoryEntity.CRITERION, rawId));
    }

    private static List<DeliverableView> toDeliverableViews(List<Deliverable> rows) {
        return rows.stream().map(d -> new DeliverableView(d.id(), d.kind().value(), d.deliverableRef(), d.location(),
                d.summary(), d.checkRef(), d.checkResult() == null ? null : d.checkResult().token(), d.checkedAt(),
                d.attachmentId(), d.recordedBy().value(), d.recordedAt())).toList();
    }

    private LastChange newestChange(HistoryEntity entity, String rawId) {
        List<Long> newest = historyQueries.newestOfEntity(entity, rawId, true, 1);
        return newest.isEmpty() ? null : lastChange(historyRows.findById(newest.getFirst()).orElseThrow());
    }

    private List<ChangeSummary> recentChanges(HistoryEntity entity, String rawId) {
        return historyQueries.newestOfEntity(entity, rawId, false, RECENT_CHANGES).stream()
                .map(h -> historyRows.findById(h).orElseThrow())
                .map(DetailService::summary)
                .toList();
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

    private static LastChange lastChange(HistoryEntry h) {
        return new LastChange(h.at(), h.authorId().value(), h.login(), h.actorAssurance().name(), h.op().name(),
                h.reason());
    }

    private static ChangeSummary summary(HistoryEntry h) {
        return new ChangeSummary(h.id(), h.at(), h.authorId().value(), h.op().name(), h.reason());
    }
}
