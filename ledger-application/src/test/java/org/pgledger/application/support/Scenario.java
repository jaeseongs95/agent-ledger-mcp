package org.pgledger.application.support;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.pgledger.application.admin.GrantService;
import org.pgledger.application.admin.GrantService.GrantCommand;
import org.pgledger.application.admin.ProjectService;
import org.pgledger.application.admin.ProjectService.ProjectCommand;
import org.pgledger.application.history.ChangeSet;
import org.pgledger.application.json.LedgerJson;
import org.pgledger.application.write.RecordDeliverableCommand;
import org.pgledger.application.write.RecordDeliverableService;
import org.pgledger.application.write.ReviewService;
import org.pgledger.application.write.SaveCommand;
import org.pgledger.application.write.SaveService;
import org.pgledger.application.write.TransitionCommand;
import org.pgledger.application.write.WriteResult.RecordDeliverableResult;
import org.pgledger.application.write.WriteResult.SaveResult;
import org.pgledger.database.repository.CriterionRepository;
import org.pgledger.database.repository.HistoryRepository;
import org.pgledger.database.repository.IssueRepository;
import org.pgledger.database.repository.SpecRepository;
import org.pgledger.database.repository.TaskRepository;
import org.pgledger.domain.model.Criterion;
import org.pgledger.domain.model.HistoryEntry;
import org.pgledger.domain.model.Issue;
import org.pgledger.domain.model.Spec;
import org.pgledger.domain.model.Task;
import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.value.CriterionId;
import org.pgledger.domain.value.IssueId;
import org.pgledger.domain.value.SpecId;
import org.pgledger.domain.value.TaskId;
import org.pgledger.testing.TestOwners;

/**
 * Isolated project fixture with enrolled actors and universal 2.0 scopes ({@code issue}, {@code spec}, {@code task},
 * {@code deliverable}, {@code verify}).
 */
public final class Scenario {

    public static final String AUTHOR = "claude:e2e-author";
    public static final String REVIEWER = "codex:e2e-reviewer";
    public static final String ISSUE_ONLY = "codex:issue-only";
    public static final String NOGRANT = "gemini:e2e-nogrant";
    public static final String REASON = "a reason that is long enough";
    public static final String EVIDENCE = "https://evidence.example/1";

    private final LedgerAppContext ctx;
    private final String projectId;
    private final String code;

    public Scenario(LedgerAppContext ctx) {
        this.ctx = ctx;
        int number = ctx.nextProjectNumber();
        this.projectId = "p" + number;
        this.code = "E2E" + number;
        ctx.clock().set(TestClock.START);
        ProjectService.ProjectCommand project = new ProjectCommand(projectId, code, "Project " + number);
        ctx.bean(ProjectService.class).add(project, TestOwners.OWNER);
        List.of(AUTHOR, REVIEWER, ISSUE_ONLY, NOGRANT).forEach(ctx::enroll);
        GrantService grants = ctx.bean(GrantService.class);
        grants.grant(new GrantCommand(AUTHOR, projectId,
                List.of("issue", "spec", "task", "deliverable", "verify"), null, null), TestOwners.OWNER);
        grants.grant(new GrantCommand(REVIEWER, projectId, List.of("deliverable", "verify"), null, null),
                TestOwners.OWNER);
        grants.grant(new GrantCommand(ISSUE_ONLY, projectId, List.of("issue"), null, null), TestOwners.OWNER);
    }

    public LedgerAppContext ctx() {
        return ctx;
    }

    public <T> T bean(Class<T> type) {
        return ctx.bean(type);
    }

    public String projectId() {
        return projectId;
    }

    public String code() {
        return code;
    }

    public String issueId(int number) {
        return code + "-I-" + "0".repeat(Math.max(0, 4 - Integer.toString(number).length())) + number;
    }

    public String specId(int number) {
        return code + "-S-" + "0".repeat(Math.max(0, 4 - Integer.toString(number).length())) + number;
    }

    public String taskId(int number) {
        return code + "-T-" + "0".repeat(Math.max(0, 4 - Integer.toString(number).length())) + number;
    }

    public String criterionId(String suffix) {
        return code + "-C-" + suffix;
    }

    public static String uuid() {
        return UUID.randomUUID().toString();
    }

    public static String ref(char ch) {
        return String.valueOf(ch).repeat(40);
    }

    public Map<String, String> issueFields(String title) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("project_id", projectId);
        fields.put("title", title);
        fields.put("category", "ARC");
        fields.put("severity", "P2");
        fields.put("symptom", "symptom of " + title);
        fields.put("root_cause", "cause of " + title);
        return fields;
    }

    public Map<String, String> specFields(String title) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("project_id", projectId);
        fields.put("title", title);
        fields.put("summary", "summary of " + title);
        fields.put("acceptance_criteria", "acceptance of " + title);
        return fields;
    }

    public Map<String, String> taskFields(String title) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("project_id", projectId);
        fields.put("title", title);
        fields.put("purpose", "purpose of " + title);
        fields.put("success_condition", "success of " + title);
        return fields;
    }

    public Map<String, String> criterionFields(String milestone, String criterionCode) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("project_id", projectId);
        fields.put("milestone_name", milestone);
        fields.put("code", criterionCode);
        fields.put("criterion_text", "criterion " + criterionCode);
        return fields;
    }

    public SaveCommand create(String entity, String id, Map<String, String> fields) {
        return new SaveCommand(uuid(), entity, id, 0L, Fields.of(fields), REASON);
    }

    public SaveCommand edit(String entity, String id, long version, Map<String, String> fields) {
        return new SaveCommand(uuid(), entity, id, version, Fields.of(fields), REASON);
    }

    public TransitionCommand move(String entity, String id, long version, String to, String evidence) {
        return new TransitionCommand(uuid(), entity, id, version, to, REASON, evidence, null, null);
    }

    public RecordDeliverableCommand deliverableCommand(String targetEntity, String targetId, String deliverableRef) {
        return new RecordDeliverableCommand(uuid(), targetEntity, targetId, "code_change", deliverableRef, REASON,
                "repo/e2e", "Deliverable for " + targetId, null, null, null, null);
    }

    public RecordDeliverableCommand deliverableCommand(String issueId, String deliverableRef) {
        return deliverableCommand("issue", issueId, deliverableRef);
    }

    public SaveResult newIssue(String title) {
        return bean(SaveService.class).save(AUTHOR, create("issue", null, issueFields(title)));
    }

    public SaveResult newSpec(String title) {
        return bean(SaveService.class).save(AUTHOR, create("spec", null, specFields(title)));
    }

    public SaveResult newTask(String title) {
        return bean(SaveService.class).save(AUTHOR, create("task", null, taskFields(title)));
    }

    public SaveResult newCriterion(String suffix, String milestone, String criterionCode) {
        return bean(SaveService.class).save(AUTHOR,
                create("criterion", criterionId(suffix), criterionFields(milestone, criterionCode)));
    }

    public RecordDeliverableResult deliver(String actor, String issueId, String deliverableRef) {
        return bean(RecordDeliverableService.class).recordDeliverable(actor,
                deliverableCommand("issue", issueId, deliverableRef));
    }

    public SaveResult resolvedIssue(String title) {
        SaveResult issue = newIssue(title);
        deliver(AUTHOR, issue.id(), ref('a'));
        return issue;
    }

    public SaveResult verifiedIssue(String title) {
        SaveResult issue = resolvedIssue(title);
        bean(ReviewService.class).review(REVIEWER, move("issue", issue.id(), 2, "VERIFIED", EVIDENCE));
        return issue;
    }

    public String actorWith(String... scopes) {
        String actor = "claude:t" + UUID.randomUUID().toString().substring(0, 8);
        grant(actor, projectId, scopes);
        return actor;
    }

    public String actorWithoutGrants() {
        String actor = "claude:n" + UUID.randomUUID().toString().substring(0, 8);
        ctx.enroll(actor);
        return actor;
    }

    public void grant(String actor, String project, String... scopes) {
        ctx.enroll(actor);
        bean(GrantService.class).grant(new GrantCommand(actor, project, List.of(scopes), null, null), TestOwners.OWNER);
    }

    public String otherProject() {
        int number = ctx.nextProjectNumber();
        bean(ProjectService.class).add(new ProjectCommand("p" + number, "E2E" + number, "Project " + number),
                TestOwners.OWNER);
        return "p" + number;
    }

    public Issue issue(String id) {
        return bean(IssueRepository.class).findById(IssueId.of(id)).orElseThrow();
    }

    public Spec spec(String id) {
        return bean(SpecRepository.class).findById(SpecId.of(id)).orElseThrow();
    }

    public Task task(String id) {
        return bean(TaskRepository.class).findById(TaskId.of(id)).orElseThrow();
    }

    public Criterion criterion(String id) {
        return bean(CriterionRepository.class).findById(CriterionId.of(id)).orElseThrow();
    }

    public List<HistoryEntry> history(HistoryEntity entity, String entityId) {
        return bean(HistoryRepository.class).findByEntityAndEntityIdOrderById(entity, entityId);
    }

    public HistoryEntry historyRow(long id) {
        return bean(HistoryRepository.class).findById(id).orElseThrow();
    }

    public ChangeSet changes(long historyId) {
        return bean(LedgerJson.class).read(historyRow(historyId).changes(), ChangeSet.class);
    }

    public <T> T before(long historyId, Class<T> type) {
        return bean(LedgerJson.class).read(historyRow(historyId).beforeData(), type);
    }

    public long count(String sql, String... values) {
        var statement = ctx.jdbc().sql(sql);
        for (int i = 0; i < values.length; i++) {
            statement = statement.param(values[i]);
        }
        return statement.query(Long.class).single();
    }
}
