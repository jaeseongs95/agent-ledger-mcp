package org.pgledger.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.groups.Tuple.tuple;

import java.sql.SQLException;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.database.repository.Counter;
import org.pgledger.database.repository.CriterionRepository;
import org.pgledger.database.repository.DeliverableRepository;
import org.pgledger.database.repository.IdAllocator;
import org.pgledger.database.repository.LinkRepository;
import org.pgledger.database.support.DatabaseTest;
import org.pgledger.database.support.LedgerTestContext;
import org.pgledger.database.support.Seed;
import org.pgledger.domain.model.Criterion;
import org.pgledger.domain.model.CriterionLink;
import org.pgledger.domain.model.Deliverable;
import org.pgledger.domain.model.Issue;
import org.pgledger.domain.model.IssueRelation;
import org.pgledger.domain.model.IssueSpecLink;
import org.pgledger.domain.model.IssueTaskLink;
import org.pgledger.domain.model.Project;
import org.pgledger.domain.model.Spec;
import org.pgledger.domain.model.SpecTaskLink;
import org.pgledger.domain.model.Task;
import org.pgledger.domain.rule.CheckResult;
import org.pgledger.domain.rule.CodeDirection;
import org.pgledger.domain.rule.DeliverableTarget;
import org.pgledger.domain.value.AuthorId;
import org.pgledger.domain.value.CodeValue;
import org.pgledger.domain.value.TaskId;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

/** REPO-021, REPO-022: normalised link keys, unique keys, checks and foreign keys. */
@DatabaseTest
class ConstraintTest {

    private LedgerTestContext ctx;
    private Seed seed;
    private LinkRepository links;
    private JdbcClient jdbc;

    @BeforeEach
    void setUp(LedgerTestContext context) {
        ctx = context;
        seed = new Seed(ctx);
        links = ctx.bean(LinkRepository.class);
        jdbc = JdbcClient.create(ctx.pool());
    }

    private static final AuthorId BY = Seed.ALICE;

    @Test
    void repo021_taskIssueAndSpecWithTheSameIdTextAreDistinctTargetsOfACriterion() {
        Project project = seed.project();
        Issue issue = seed.issue(project, 1, "linked", LedgerTestContext.START);
        Spec spec = seed.spec(project, 1, "spec", LedgerTestContext.START);
        Task sameText = seed.task(project, TaskId.of(issue.id().value()), "same text");
        Criterion c = seed.criterion(project, project.code().value() + "-C", "1.0", "C");
        CriterionLink toTask = CriterionLink.toTask(c.id(), sameText.id(), BY);
        CriterionLink toIssue = CriterionLink.toIssue(c.id(), issue.id(), BY);
        CriterionLink toSpec = CriterionLink.toSpec(c.id(), spec.id(), BY);
        assertThat(ctx.tx(() -> links.add(toTask))).isTrue();
        assertThat(ctx.tx(() -> links.add(toTask))).as("a duplicate adds nothing").isFalse();
        assertThat(ctx.tx(() -> links.add(toIssue))).isTrue();
        assertThat(ctx.tx(() -> links.add(toSpec))).isTrue();
        assertThat(links.linksOf(c.id())).containsExactlyInAnyOrder(toTask, toIssue, toSpec);
        assertThat(ctx.tx(() -> links.remove(toTask))).isTrue();
        assertThat(ctx.tx(() -> links.remove(toTask))).isFalse();
    }

    @Test
    void repo021_aPipeInAnIdNeverMakesTwoDifferentPairsCollide() {
        Project project = seed.project();
        Task pipeTask = seed.task(project, TaskId.of("A|T"), "pipe task");
        Task plainTask = seed.task(project, TaskId.of("T"), "plain task");
        Criterion first = seed.criterion(project, "OLD-PIPE-C", "1.0", "P1");
        Criterion second = seed.criterion(project, "OLD-PIPE-C|A", "1.0", "P2");
        assertThat(ctx.tx(() -> links.add(CriterionLink.toTask(first.id(), pipeTask.id(), BY)))).isTrue();
        assertThat(ctx.tx(() -> links.add(CriterionLink.toTask(second.id(), plainTask.id(), BY)))).isTrue();
    }

    @Test
    void repo021_theLinkKeyIsNeverNullAndAnSqlDuplicateIsRefusedByIt() {
        Project project = seed.project();
        Task task = seed.task(project, 1, "keyed");
        Criterion c = seed.criterion(project, project.code().value() + "-K", "1.0", "K");
        CriterionLink link = CriterionLink.toTask(c.id(), task.id(), BY);
        ctx.tx(() -> links.add(link));
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO criterion_link (link_key, criterion_id, task_id, created_by) "
                + "VALUES (:key, :c, :t, 'user:owner')").param("key", link.linkKey()).param("c", c.id().value())
                .param("t", task.id().value()).update()).isInstanceOf(DuplicateKeyException.class);
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO criterion_link (link_key, criterion_id, created_by) "
                + "VALUES ('k', :c, 'user:owner')").param("c", c.id().value()).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void repo022_deliverableIsUniquePerTargetKindAndRef() {
        Project project = seed.project();
        Issue i1 = seed.issue(project, 1, "one", LedgerTestContext.START);
        IdAllocator ids = ctx.bean(IdAllocator.class);
        DeliverableRepository delivs = ctx.bean(DeliverableRepository.class);
        CodeValue kind = CodeValue.of("CODE_PATCH");
        String ref = "commit:" + "c".repeat(40);
        ctx.tx(() -> delivs.insert(Deliverable.record(ids.next(Counter.DELIVERABLE), DeliverableTarget.ISSUE,
                i1.id().value(), kind, ref, null, "first", null, null, null, null, BY, LedgerTestContext.START)));
        assertThatThrownBy(() -> ctx.tx(() -> delivs.insert(Deliverable.record(ids.next(Counter.DELIVERABLE),
                DeliverableTarget.ISSUE, i1.id().value(), kind, ref, null, "dup", null, null, null, null, BY,
                LedgerTestContext.START))))
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(ctx.tx(() -> delivs.insert(Deliverable.record(ids.next(Counter.DELIVERABLE),
                DeliverableTarget.ISSUE, i1.id().value(), CodeValue.of("DOCUMENT"), ref, null, "other kind", "ref",
                CheckResult.PASSED, null, null, BY, LedgerTestContext.START)))).isNotNull();
    }

    @Test
    void repo022_aCriterionIsUniquePerProjectMilestoneAndCode() {
        Project project = seed.project();
        seed.criterion(project, project.code().value() + "-C1", "1.0", "X");
        assertThatThrownBy(() -> seed.criterion(project, project.code().value() + "-C2", "1.0", "X"))
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(ctx.bean(CriterionRepository.class).findByProjectIdAndMilestoneAndCode(project.id(), "1.0", "X"))
                .isPresent();
    }

    @Test
    void repo022_relationsAndSpecLinksEnforceUniquenessAndDirection() {
        Project project = seed.project();
        Issue i1 = seed.issue(project, 1, "one", LedgerTestContext.START);
        Issue i2 = seed.issue(project, 2, "two", LedgerTestContext.START);
        Spec spec = seed.spec(project, 1, "spec", LedgerTestContext.START);
        Task task = seed.task(project, 1, "task");
        CodeValue sameSite = CodeValue.of("same_site");
        assertThat(ctx.tx(() -> links.add(IssueRelation.between(i2.id(), i1.id(), sameSite, CodeDirection.CANONICAL,
                BY)))).isTrue();
        assertThat(ctx.tx(() -> links.add(IssueRelation.between(i1.id(), i2.id(), sameSite, CodeDirection.CANONICAL,
                BY)))).as("the pair is the same relation in either order").isFalse();
        assertThat(ctx.tx(() -> links.add(IssueRelation.between(i1.id(), i2.id(), CodeValue.of("blocks"),
                CodeDirection.GIVEN, BY)))).as("another kind is another relation").isTrue();
        assertThat(ctx.tx(() -> links.add(IssueRelation.between(i2.id(), i1.id(), CodeValue.of("blocks"),
                CodeDirection.GIVEN, BY)))).isTrue();
        assertThat(links.relationsOf(i1.id())).extracting(IssueRelation::a, IssueRelation::b, r -> r.relation().value())
                .containsExactlyInAnyOrder(
                        tuple(i1.id(), i2.id(), "same_site"),
                        tuple(i1.id(), i2.id(), "blocks"),
                        tuple(i2.id(), i1.id(), "blocks"));
        assertThat(links.countRelations(CodeValue.of("blocks"))).isEqualTo(2);
        assertThat(links.countRelations(sameSite)).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO issue_relation (a, b, kind, created_by) VALUES (:a, :a, "
                + "'x', 'user:owner')").param("a", i1.id().value()).update())
                .as("the two ends are two different issues").isInstanceOf(DataIntegrityViolationException.class);
        assertThat(ctx.tx(() -> links.add(new IssueTaskLink(i1.id(), task.id(), BY)))).isTrue();
        assertThat(ctx.tx(() -> links.add(new IssueTaskLink(i1.id(), task.id(), BY)))).isFalse();
        assertThat(links.tasksOf(i1.id())).hasSize(1);
        assertThat(ctx.tx(() -> links.add(new IssueSpecLink(i1.id(), spec.id(), BY)))).isTrue();
        assertThat(ctx.tx(() -> links.add(new SpecTaskLink(spec.id(), task.id(), BY)))).isTrue();
        assertThat(links.specsOfIssue(i1.id())).containsExactly(spec.id().value());
        assertThat(links.tasksOfSpec(spec.id())).containsExactly(task.id().value());
    }

    static Stream<String> orphansAndBrokenChecks() {
        return Stream.of(
                "INSERT INTO task (id, id_key, project_id, title, purpose, success_condition, state, version, created_at, "
                        + "updated_at, created_by) VALUES ('ORPHAN-T', 'ORPHAN-T', 'nope', 't', 'p', 's', 'PLANNED', 1, "
                        + "TIMESTAMP '2026-11-05 10:00:00', TIMESTAMP '2026-11-05 10:00:00', 'user:owner')",
                "INSERT INTO spec (id, id_key, project_id, title, status, version, created_at, updated_at, created_by) "
                        + "VALUES ('ORPHAN-S', 'ORPHAN-S', 'nope', 's', 'PLANNED', 1, TIMESTAMP '2026-11-05 10:00:00', "
                        + "TIMESTAMP '2026-11-05 10:00:00', 'user:owner')",
                "INSERT INTO issue (id, project_id, title, category, severity, status, symptom, root_cause, "
                        + "version, created_at, updated_at, created_by) VALUES ('ORPHAN-I-0001', 'nope', 't', 'PROCESS', "
                        + "'P1', 'OPEN', 'b', 'c', 1, TIMESTAMP '2026-11-05 10:00:00', TIMESTAMP '2026-11-05 "
                        + "10:00:00', 'user:owner')",
                "UPDATE issue SET status = 'MERGED', merged_into = 'NOSUCH-I-0001' WHERE id = :issue",
                "INSERT INTO issue_case (id, issue_id, observation, recorded_by, recorded_at) VALUES (990001, "
                        + "'NOSUCH-I-0001', 'o', 'claude:a', TIMESTAMP '2026-11-05 10:00:00')",
                "INSERT INTO issue_task (issue_id, task_id, created_by) VALUES ('NOSUCH-I-0001', 'X', 'user:owner')",
                "INSERT INTO issue_relation (a, b, kind, created_by) VALUES ('AAA-I-0001', 'AAA-I-0002', 'k', "
                        + "'user:owner')",
                "INSERT INTO milestone_criterion (id, id_key, project_id, milestone_name, code, criterion_text, state, "
                        + "version, created_by) VALUES ('ORPHAN-C', 'ORPHAN-C', 'nope', '1', 'c', 't', 'UNKNOWN', 1, "
                        + "'user:owner')",
                "INSERT INTO criterion_link (link_key, criterion_id, task_id, created_by) VALUES ('k', 'NOSUCH', "
                        + "'X', 'user:owner')",
                "INSERT INTO session_grant (id, author_id, project_id, grant_scope, granted_by, granted_at, active_key) "
                        + "VALUES (990002, 'claude:a', 'nope', 'ISSUE', 'user:owner', TIMESTAMP '2026-11-05 "
                        + "10:00:00', 'orphan-key')",
                "INSERT INTO history (id, occurred_at, author_id, login, actor_assurance, entity, entity_id, op, changes, "
                        + "reason, corrects_id, operation_key) VALUES (990003, TIMESTAMP '2026-11-05 10:00:00', "
                        + "'claude:a', 'ledger_author', 'DECLARED', 'ISSUE', 'X', 'CORRECT', '{}', "
                        + "'a reason of length', 424242, '11111111-1111-1111-1111-111111111111')",
                "UPDATE issue SET status = 'MERGED' WHERE id = :issue",
                "UPDATE issue SET merged_into = id WHERE id = :issue");
    }

    @ParameterizedTest
    @MethodSource("orphansAndBrokenChecks")
    void repo022_aChildWithoutItsParentOrAnImpossibleRowIsRefusedWithClass23(String sql) {
        Issue issue = seed.issue(seed.project(), 1, "host", LedgerTestContext.START);
        Throwable thrown = catchThrowable(() -> jdbc.sql(sql).param("issue", issue.id().value()).update());
        assertThat(thrown).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(((SQLException) NestedExceptionUtils.getMostSpecificCause(thrown)).getSQLState())
                .startsWith("23");
    }
}
