// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.mcp.tool;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonRawValue;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.pgledger.application.read.AttentionService;
import org.pgledger.application.read.CatalogService;
import org.pgledger.application.read.DetailService;
import org.pgledger.application.read.GetService;
import org.pgledger.application.read.HistoryService;
import org.pgledger.application.read.ListService;
import org.pgledger.application.read.Page;
import org.pgledger.application.read.SearchService;
import org.pgledger.application.read.StatsService;

/**
 * Payload records placed inside {@code data} of the {@code ApiResult.v1} envelope for the fifteen read tools.
 * Paginated lists carry {@code items}, {@code truncated}, and {@code next_cursor}; timestamps are UTC ISO-8601.
 */
public final class ReadOutputs {

    private static final String JSON_NULL = "null";

    private ReadOutputs() {
    }

    /** One page of a list. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Items<T>(@JsonProperty("items") List<T> items, @JsonProperty("truncated") boolean truncated,
            @JsonProperty("next_cursor") String nextCursor) {

        public Items {
            items = List.copyOf(items);
        }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record GotItem(@JsonProperty("entity") String entity, @JsonProperty("id") String id,
            @JsonProperty("state") String state, @JsonProperty("version") long version,
            @JsonProperty("project_id") String projectId, @JsonProperty("merged_into") String mergedInto) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Got(@JsonProperty("items") List<GotItem> items, @JsonProperty("not_found") List<String> notFound) {

        public Got {
            items = List.copyOf(items);
            notFound = List.copyOf(notFound);
        }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record LastChange(@JsonProperty("at") Instant at, @JsonProperty("author") String author,
            @JsonProperty("login") String login, @JsonProperty("assurance") String assurance,
            @JsonProperty("op") String op, @JsonProperty("reason") String reason) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Deliverable(@JsonProperty("id") long id, @JsonProperty("kind") String kind,
            @JsonProperty("deliverable_ref") String deliverableRef, @JsonProperty("location") String location,
            @JsonProperty("summary") String summary, @JsonProperty("check_ref") String checkRef,
            @JsonProperty("check_result") String checkResult, @JsonProperty("checked_at") Instant checkedAt,
            @JsonProperty("attachment_id") String attachmentId, @JsonProperty("by") String by,
            @JsonProperty("at") Instant at) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record TaskRef(@JsonProperty("task") String task, @JsonProperty("state") String state,
            @JsonProperty("owner") String owner) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Case(@JsonProperty("source") String source, @JsonProperty("issue") String issue,
            @JsonProperty("case_id") Long caseId, @JsonProperty("observation") String observation,
            @JsonProperty("evidence") String evidence, @JsonProperty("source_quote") String sourceQuote,
            @JsonProperty("observed_at") Instant observedAt, @JsonProperty("by") String by,
            @JsonProperty("at") Instant at) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Relation(@JsonProperty("kind") String kind, @JsonProperty("other") String other,
            @JsonProperty("end") String end) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ChangeSummary(@JsonProperty("id") long id, @JsonProperty("at") Instant at,
            @JsonProperty("author") String author, @JsonProperty("op") String op,
            @JsonProperty("reason") String reason) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Issue(@JsonProperty("id") String id, @JsonProperty("project_id") String projectId,
            @JsonProperty("title") String title, @JsonProperty("category") String category,
            @JsonProperty("severity") String severity, @JsonProperty("status") String status,
            @JsonProperty("merged_into") String mergedInto, @JsonProperty("symptom") String symptom,
            @JsonProperty("root_cause") String rootCause, @JsonProperty("owner_actor_id") String ownerActorId,
            @JsonProperty("version") long version, @JsonProperty("updated_at") Instant updatedAt,
            @JsonProperty("created_by") String createdBy, @JsonProperty("resolved_by") String resolvedBy,
            @JsonProperty("resolved_at") Instant resolvedAt, @JsonProperty("last_change") LastChange lastChange,
            @JsonProperty("deliverables") List<Deliverable> deliverables, @JsonProperty("tasks") List<TaskRef> tasks,
            @JsonProperty("specs") List<String> specs, @JsonProperty("criteria") List<String> criteria,
            @JsonProperty("cases") List<Case> cases, @JsonProperty("relations") List<Relation> relations,
            @JsonProperty("recent_changes") List<ChangeSummary> recentChanges) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Spec(@JsonProperty("id") String id, @JsonProperty("project_id") String projectId,
            @JsonProperty("parent_id") String parentId, @JsonProperty("title") String title,
            @JsonProperty("summary") String summary, @JsonProperty("acceptance_criteria") String acceptanceCriteria,
            @JsonProperty("priority") String priority, @JsonProperty("status") String status,
            @JsonProperty("owner_actor_id") String ownerActorId, @JsonProperty("origin_ref") String originRef,
            @JsonProperty("origin_quote") String originQuote, @JsonProperty("version") long version,
            @JsonProperty("created_at") Instant createdAt, @JsonProperty("updated_at") Instant updatedAt,
            @JsonProperty("created_by") String createdBy, @JsonProperty("completed_by") String completedBy,
            @JsonProperty("last_change") LastChange lastChange,
            @JsonProperty("deliverables") List<Deliverable> deliverables,
            @JsonProperty("child_specs") List<String> childSpecs, @JsonProperty("tasks") List<String> tasks,
            @JsonProperty("issues") List<String> issues, @JsonProperty("criteria") List<String> criteria,
            @JsonProperty("recent_changes") List<ChangeSummary> recentChanges) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Task(@JsonProperty("id") String id, @JsonProperty("project_id") String projectId,
            @JsonProperty("title") String title, @JsonProperty("purpose") String purpose,
            @JsonProperty("success_condition") String successCondition,
            @JsonProperty("origin_ref") String originRef, @JsonProperty("origin_quote") String originQuote,
            @JsonProperty("state") String state, @JsonProperty("owner_actor_id") String ownerActorId,
            @JsonProperty("version") long version, @JsonProperty("created_at") Instant createdAt,
            @JsonProperty("updated_at") Instant updatedAt, @JsonProperty("created_by") String createdBy,
            @JsonProperty("completed_by") String completedBy,
            @JsonProperty("deliverables") List<Deliverable> deliverables, @JsonProperty("issues") List<String> issues,
            @JsonProperty("specs") List<String> specs, @JsonProperty("criteria") List<String> criteria) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Criterion(@JsonProperty("id") String id, @JsonProperty("project_id") String projectId,
            @JsonProperty("milestone_name") String milestoneName, @JsonProperty("goal_label") String goalLabel,
            @JsonProperty("code") String code, @JsonProperty("criterion_text") String criterionText,
            @JsonProperty("state") String state, @JsonProperty("version") long version,
            @JsonProperty("created_by") String createdBy, @JsonProperty("last_change") LastChange lastChange,
            @JsonProperty("issues") List<String> issues, @JsonProperty("specs") List<String> specs,
            @JsonProperty("tasks") List<String> tasks,
            @JsonProperty("recent_changes") List<ChangeSummary> recentChanges) {
    }

    /**
     * One history row. {@code changes} is the object of {@code [before, after]} pairs of the row; {@code before_data}
     * is the full row before the change, present only when asked for ({@code include_before}): then it is the row,
     * or null when the operation has none.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record History(@JsonProperty("id") long id, @JsonProperty("at") Instant at,
            @JsonProperty("author_id") String authorId, @JsonProperty("vendor") String vendor,
            @JsonProperty("login") String login, @JsonProperty("actor_assurance") String actorAssurance,
            @JsonProperty("entity") String entity, @JsonProperty("entity_id") String entityId,
            @JsonProperty("op") String op, @JsonProperty("from_state") String fromState,
            @JsonProperty("to_state") String toState, @JsonRawValue @JsonProperty("changes") Object changes,
            @JsonInclude(JsonInclude.Include.NON_NULL) @JsonRawValue @JsonProperty("before_data") Object beforeData,
            @JsonProperty("reason") String reason,
            @JsonProperty("evidence_uri") String evidenceUri, @JsonProperty("corrects_id") Long correctsId,
            @JsonProperty("operation_key") String operationKey, @JsonProperty("source_ref") String sourceRef,
            @JsonProperty("attachment_id") String attachmentId) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Hit(@JsonProperty("kind") String kind, @JsonProperty("id") String id,
            @JsonProperty("project_id") String projectId, @JsonProperty("title") String title,
            @JsonProperty("state") String state, @JsonProperty("created_at") Instant createdAt) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record IssueRow(@JsonProperty("id") String id, @JsonProperty("title") String title,
            @JsonProperty("category") String category, @JsonProperty("severity") String severity,
            @JsonProperty("status") String status, @JsonProperty("merged_into") String mergedInto,
            @JsonProperty("owner") String owner, @JsonProperty("version") long version,
            @JsonProperty("created_at") Instant createdAt, @JsonProperty("updated_at") Instant updatedAt) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SpecRow(@JsonProperty("id") String id, @JsonProperty("parent_id") String parentId,
            @JsonProperty("title") String title, @JsonProperty("status") String status,
            @JsonProperty("priority") String priority, @JsonProperty("owner_actor_id") String ownerActorId,
            @JsonProperty("version") long version, @JsonProperty("created_at") Instant createdAt,
            @JsonProperty("updated_at") Instant updatedAt) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record TaskRow(@JsonProperty("id") String id, @JsonProperty("title") String title,
            @JsonProperty("state") String state, @JsonProperty("owner_actor_id") String ownerActorId,
            @JsonProperty("version") long version, @JsonProperty("created_at") Instant createdAt,
            @JsonProperty("updated_at") Instant updatedAt) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record CriterionRow(@JsonProperty("id") String id, @JsonProperty("milestone_name") String milestoneName,
            @JsonProperty("goal_label") String goalLabel, @JsonProperty("code") String code,
            @JsonProperty("criterion_text") String criterionText, @JsonProperty("state") String state,
            @JsonProperty("version") long version) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AttentionRow(@JsonProperty("kind") String kind, @JsonProperty("entity") String entity,
            @JsonProperty("id") String id, @JsonProperty("project_id") String projectId) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record DayCount(@JsonProperty("day") LocalDate day, @JsonProperty("issues") long issues,
            @JsonProperty("specs") long specs, @JsonProperty("tasks") long tasks) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Stats(@JsonProperty("issues_by_status") Map<String, Long> issuesByStatus,
            @JsonProperty("issues_by_severity") Map<String, Long> issuesBySeverity,
            @JsonProperty("specs_by_status") Map<String, Long> specsByStatus,
            @JsonProperty("tasks_by_state") Map<String, Long> tasksByState,
            @JsonProperty("criteria_by_state") Map<String, Long> criteriaByState,
            @JsonProperty("issues") long issues, @JsonProperty("specs") long specs,
            @JsonProperty("tasks") long tasks, @JsonProperty("criteria") long criteria,
            @JsonProperty("created_per_day") List<DayCount> createdPerDay) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ProjectRow(@JsonProperty("id") String id, @JsonProperty("code") String code,
            @JsonProperty("title") String title) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record CodeRow(@JsonProperty("group") String group, @JsonProperty("code") String code,
            @JsonProperty("name_ko") String nameKo, @JsonProperty("name_en") String nameEn,
            @JsonProperty("description_ko") String descriptionKo,
            @JsonProperty("description_en") String descriptionEn, @JsonProperty("sort_order") int sortOrder,
            @JsonProperty("state") String state, @JsonProperty("direction") String direction) {
    }

    public static <S, T> Items<T> page(Page<S> page, Function<S, T> view) {
        return new Items<>(page.items().stream().map(view).toList(), page.truncated(), page.nextCursor());
    }

    public static Got got(GetService.Result result) {
        return new Got(result.items().stream().map(i -> new GotItem(i.entity(), i.id(), i.state(), i.version(),
                i.projectId(), i.mergedInto())).toList(), result.notFound());
    }

    private static LastChange lastChange(DetailService.LastChange last) {
        return last == null ? null : new LastChange(last.at(), last.author(), last.login(), last.assurance(),
                last.op(), last.reason());
    }

    private static List<Deliverable> deliverables(List<DetailService.DeliverableView> list) {
        return list.stream().map(d -> new Deliverable(d.id(), d.kind(), d.deliverableRef(), d.location(), d.summary(),
                d.checkRef(), d.checkResult(), d.checkedAt(), d.attachmentId(), d.by(), d.at())).toList();
    }

    private static List<ChangeSummary> changes(List<DetailService.ChangeSummary> list) {
        return list.stream().map(c -> new ChangeSummary(c.id(), c.at(), c.author(), c.op(), c.reason())).toList();
    }

    public static Issue issue(DetailService.IssueDetail d) {
        return new Issue(d.id(), d.projectId(), d.title(), d.category(), d.severity(), d.status(), d.mergedInto(),
                d.symptom(), d.rootCause(), d.ownerActorId(), d.version(), d.updatedAt(), d.createdBy(),
                d.resolvedBy(), d.resolvedAt(), lastChange(d.lastChange()), deliverables(d.deliverables()),
                d.tasks().stream().map(t -> new TaskRef(t.task(), t.state(), t.owner())).toList(),
                d.specs(), d.criteria(),
                d.cases().stream().map(c -> new Case(c.source(), c.issue(), c.caseId(), c.observation(),
                        c.evidence(), c.sourceQuote(), c.observedAt(), c.by(), c.at())).toList(),
                d.relations().stream().map(r -> new Relation(r.kind(), r.other(), r.end().token())).toList(),
                changes(d.recentChanges()));
    }

    public static Spec spec(DetailService.SpecDetail s) {
        return new Spec(s.id(), s.projectId(), s.parentId(), s.title(), s.summary(), s.acceptanceCriteria(),
                s.priority(), s.status(), s.ownerActorId(), s.originRef(), s.originQuote(), s.version(),
                s.createdAt(), s.updatedAt(), s.createdBy(), s.completedBy(), lastChange(s.lastChange()),
                deliverables(s.deliverables()), s.childSpecs(), s.tasks(), s.issues(), s.criteria(),
                changes(s.recentChanges()));
    }

    public static Task task(DetailService.TaskDetail t) {
        return new Task(t.id(), t.projectId(), t.title(), t.purpose(), t.successCondition(), t.originRef(),
                t.originQuote(), t.state(), t.ownerActorId(), t.version(), t.createdAt(), t.updatedAt(),
                t.createdBy(), t.completedBy(), deliverables(t.deliverables()), t.issues(), t.specs(), t.criteria());
    }

    public static Criterion criterion(DetailService.CriterionDetail c) {
        return new Criterion(c.id(), c.projectId(), c.milestoneName(), c.goalLabel(), c.code(), c.criterionText(),
                c.state(), c.version(), c.createdBy(), lastChange(c.lastChange()),
                c.issues(), c.specs(), c.tasks(), changes(c.recentChanges()));
    }

    /** @param includeBefore whether the caller asked for {@code before_data}: when not, the key is left out */
    public static History history(HistoryService.HistoryView h, boolean includeBefore) {
        return new History(h.id(), h.at(), h.authorId(), h.vendor(), h.login(), h.actorAssurance(), h.entity(),
                h.entityId(), h.op(), h.fromState(), h.toState(), h.changes().text(),
                beforeData(h, includeBefore), h.reason(), h.evidenceUri(),
                h.correctsId(), h.operationKey(), h.sourceRef(), h.attachmentId());
    }

    private static String beforeData(HistoryService.HistoryView h, boolean includeBefore) {
        if (!includeBefore) {
            return null;
        }
        return h.beforeData() == null ? JSON_NULL : h.beforeData().text();
    }

    public static Hit hit(SearchService.Hit h) {
        return new Hit(h.entity(), h.id(), h.projectId(), h.title(), h.state(), h.createdAt());
    }

    public static IssueRow issueRow(ListService.IssueItem d) {
        return new IssueRow(d.id(), d.title(), d.category(), d.severity(), d.status(), d.mergedInto(), d.owner(),
                d.version(), d.createdAt(), d.updatedAt());
    }

    public static SpecRow specRow(ListService.SpecItem s) {
        return new SpecRow(s.id(), s.parentId(), s.title(), s.status(), s.priority(), s.ownerActorId(),
                s.version(), s.createdAt(), s.updatedAt());
    }

    public static TaskRow taskRow(ListService.TaskItem t) {
        return new TaskRow(t.id(), t.title(), t.state(), t.ownerActorId(), t.version(), t.createdAt(),
                t.updatedAt());
    }

    public static CriterionRow criterionRow(ListService.CriterionItem c) {
        return new CriterionRow(c.id(), c.milestoneName(), c.goalLabel(), c.code(), c.criterionText(), c.state(),
                c.version());
    }

    public static AttentionRow attentionRow(AttentionService.Item i) {
        return new AttentionRow(i.kind(), i.entity(), i.id(), i.projectId());
    }

    public static Stats stats(StatsService.Stats s) {
        return new Stats(s.issuesByStatus(), s.issuesBySeverity(), s.specsByStatus(), s.tasksByState(),
                s.criteriaByState(), s.issues(), s.specs(), s.tasks(), s.criteria(),
                s.createdPerDay().stream().map(d -> new DayCount(d.day(), d.issues(), d.specs(), d.tasks())).toList());
    }

    public static ProjectRow projectRow(CatalogService.ProjectView p) {
        return new ProjectRow(p.id(), p.code(), p.title());
    }

    public static CodeRow codeRow(CatalogService.CodeView c) {
        return new CodeRow(c.group(), c.code(), c.nameKo(), c.nameEn(), c.descriptionKo(), c.descriptionEn(),
                c.sortOrder(), c.state().token(), c.direction().token());
    }
}
