// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pgledger.database.repository.Counter;
import org.pgledger.database.repository.CriterionRepository;
import org.pgledger.database.repository.DeliverableRepository;
import org.pgledger.database.repository.HistoryRepository;
import org.pgledger.database.repository.IdAllocator;
import org.pgledger.database.repository.IssueCaseRepository;
import org.pgledger.database.repository.IssueRepository;
import org.pgledger.database.repository.ProjectRepository;
import org.pgledger.database.repository.ReceiptRepository;
import org.pgledger.database.repository.SessionGrantRepository;
import org.pgledger.database.repository.SessionTokenRepository;
import org.pgledger.database.repository.SpecRepository;
import org.pgledger.database.repository.TaskRepository;
import org.pgledger.database.support.LedgerDataJdbcTest;
import org.pgledger.database.support.LedgerTestContext;
import org.pgledger.database.support.RecordingDataSource;
import org.pgledger.database.support.Seed;
import org.pgledger.domain.model.Criterion;
import org.pgledger.domain.model.Deliverable;
import org.pgledger.domain.model.HistoryEntry;
import org.pgledger.domain.model.Issue;
import org.pgledger.domain.model.IssueCase;
import org.pgledger.domain.model.IssueFields;
import org.pgledger.domain.model.Project;
import org.pgledger.domain.model.Receipt;
import org.pgledger.domain.model.ReceiptOperation;
import org.pgledger.domain.model.SessionGrant;
import org.pgledger.domain.model.SessionToken;
import org.pgledger.domain.model.Spec;
import org.pgledger.domain.model.Task;
import org.pgledger.domain.rule.ActorAssurance;
import org.pgledger.domain.rule.Channel;
import org.pgledger.domain.rule.CheckResult;
import org.pgledger.domain.rule.CriterionState;
import org.pgledger.domain.rule.DeliverableTarget;
import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.rule.HistoryOp;
import org.pgledger.domain.rule.IssueStatus;
import org.pgledger.domain.rule.Scope;
import org.pgledger.domain.rule.SpecStatus;
import org.pgledger.domain.rule.TaskState;
import org.pgledger.domain.value.AuthorId;
import org.pgledger.domain.value.CodeValue;
import org.pgledger.domain.value.IssueId;
import org.pgledger.domain.value.JsonDocument;
import org.pgledger.domain.value.OperationKey;
import org.pgledger.domain.value.Patch;
import org.pgledger.domain.value.Sha256;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.OptimisticLockingFailureException;

/**
 * REPO-006 .. REPO-008 and round trip of every aggregate through the real schema.
 */
@LedgerDataJdbcTest
class AggregateRepositoryTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private RecordingDataSource spy;

    @Autowired
    private ProjectRepository projects;

    @Autowired
    private IssueRepository issues;

    @Autowired
    private SpecRepository specs;

    @Autowired
    private TaskRepository tasks;

    @Autowired
    private CriterionRepository criteria;

    @Autowired
    private DeliverableRepository deliverables;

    @Autowired
    private IssueCaseRepository cases;

    @Autowired
    private HistoryRepository history;

    @Autowired
    private ReceiptRepository receipts;

    @Autowired
    private SessionGrantRepository grants;

    @Autowired
    private SessionTokenRepository tokens;

    @Autowired
    private IdAllocator ids;

    private Seed seed;

    @BeforeEach
    void setUp() {
        seed = new Seed(context);
    }

    @Test
    void roundTripOfTheVersionedAggregates() {
        Project project = seed.project();
        assertThat(project.version()).isEqualTo(1);
        assertThat(project.createdBy()).isEqualTo(AuthorId.OWNER);
        assertThat(projects.findById(project.id())).hasValueSatisfying(p -> {
            assertThat(p.code()).isEqualTo(project.code());
            assertThat(p.nextIssueNo()).isZero();
            assertThat(p.nextSpecNo()).isZero();
            assertThat(p.nextTaskNo()).isZero();
            assertThat(p.version()).isEqualTo(1);
        });
        assertThat(projects.findByCode(project.code())).isPresent();

        Instant created = Instant.parse("2026-11-05T10:00:00.123456Z");
        Issue issue = seed.issue(project, 1, "first issue", created);
        assertThat(issue.version()).isEqualTo(1);
        Issue read = issues.findById(issue.id()).orElseThrow();
        assertThat(read).isEqualTo(issue);
        assertThat(read.createdAt()).isEqualTo(created);
        assertThat(read.createdBy()).isEqualTo(Seed.ALICE);
        assertThat(read.status()).isEqualTo(IssueStatus.OPEN);
        assertThat(read.caseSeq()).isZero();

        Spec spec = seed.spec(project, 1, "first spec", created);
        assertThat(specs.findById(spec.id())).hasValue(spec);
        assertThat(spec.status()).isEqualTo(SpecStatus.PLANNED);

        Task task = seed.task(project, 1, "first task");
        assertThat(tasks.findById(task.id())).hasValue(task);
        assertThat(task.state()).isEqualTo(TaskState.PLANNED);

        Criterion criterion = seed.criterion(project, project.code().value() + "-C-1", "1.0", "C1");
        assertThat(criteria.findById(criterion.id())).hasValue(criterion);
        assertThat(criterion.state()).isEqualTo(CriterionState.UNKNOWN);
        assertThat(criteria.findByProjectIdAndMilestoneAndCode(project.id(), "1.0", "C1")).isPresent();
    }

    @Test
    void repo006_createdByIsStampedOnInsertAndNeverRewritten() {
        Project project = seed.project();
        Issue issue = seed.issue(project, 1, "stamped", LedgerTestContext.START);
        spy.clear();
        Issue edited = LedgerTestContext.as(Seed.BOB, () -> issues.save(issue.edited(
                IssueFields.of(Patch.absent(), Patch.set("edited title"), Patch.absent(), Patch.absent(),
                        Patch.absent(), Patch.absent(), Patch.absent()), LedgerTestContext.START.plusSeconds(5))));
        assertThat(edited.version()).isEqualTo(2);
        assertThat(edited.createdBy()).isEqualTo(Seed.ALICE);
        assertThat(issues.findById(issue.id()).orElseThrow().createdBy()).isEqualTo(Seed.ALICE);
        List<String> updates = spy.statements().stream().filter(s -> s.toUpperCase().startsWith("UPDATE")).toList();
        assertThat(updates).isNotEmpty().noneMatch(s -> s.toLowerCase().contains("created_by"));
        assertThat(updates).noneMatch(s -> s.toLowerCase().contains("case_seq"));
    }

    @Test
    void repo007_aWriteWithoutAnActorFailsLoudly() {
        Project project = seed.project();
        assertThatThrownBy(() -> issues.save(Issue.open(
                IssueId.of(project.code(), 1),
                IssueFields.of(Patch.set(project.id().value()), Patch.set("t"), Patch.set("PROCESS"), Patch.set("P1"),
                        Patch.set("b"), Patch.set("c"), Patch.absent()), LedgerTestContext.START)))
                .hasStackTraceContaining("no actor is bound");
    }

    @Test
    void repo008_staleUpdateFailsAndTheVersionGrowsByOne() {
        Project project = seed.project();
        Issue issue = seed.issue(project, 1, "versioned", LedgerTestContext.START);
        IssueFields change = IssueFields.of(Patch.absent(), Patch.set("v2"), Patch.absent(), Patch.absent(),
                Patch.absent(), Patch.absent(), Patch.absent());
        Issue first = LedgerTestContext.as(Seed.ALICE, () -> issues.save(issue.edited(change, LedgerTestContext.START)));
        assertThat(first.version()).isEqualTo(2);
        assertThatThrownBy(() -> LedgerTestContext.as(Seed.BOB,
                () -> issues.save(issue.edited(change, LedgerTestContext.START))))
                .isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(issues.findById(issue.id()).orElseThrow().version()).isEqualTo(2);
    }

    @Test
    void historyReceiptsDeliverablesCasesTokensAndGrantsRoundTrip() {
        Project project = seed.project();
        Issue issue = seed.issue(project, 1, "with children", LedgerTestContext.START);
        Spec spec = seed.spec(project, 1, "spec with deliverable", LedgerTestContext.START);

        Deliverable issueDeliv = deliverables.insert(Deliverable.record(
                ids.next(Counter.DELIVERABLE), DeliverableTarget.ISSUE, issue.id().value(),
                CodeValue.of("CODE_PATCH"), "commit:" + "a".repeat(40), "main", "fixed issue",
                "run-1", CheckResult.PASSED, Instant.parse("2026-11-05T10:01:00Z"), null,
                Seed.ALICE, LedgerTestContext.START));
        assertThat(deliverables.findById(issueDeliv.id())).hasValue(issueDeliv);
        Deliverable updated = deliverables.update(issueDeliv.withCheck("run-2", CheckResult.FAILED, null));
        assertThat(deliverables.findById(issueDeliv.id()).orElseThrow().checkResult()).isEqualTo(CheckResult.FAILED);
        assertThat(updated.checkRef()).isEqualTo("run-2");

        Deliverable specDeliv = deliverables.insert(Deliverable.record(
                ids.next(Counter.DELIVERABLE), DeliverableTarget.SPEC, spec.id().value(),
                CodeValue.of("DOCUMENT"), "doc:chapter-1", "drafts/", "script draft",
                null, null, null, null, Seed.ALICE, LedgerTestContext.START));
        assertThat(deliverables.findByTargetEntityAndTargetIdOrderById(DeliverableTarget.SPEC, spec.id().value()))
                .containsExactly(specDeliv);

        IssueCase observed = cases.insert(IssueCase.observe(
                ids.next(Counter.ISSUE_CASE), issue.id(), "seen in log", null, "quoted source", null,
                Seed.ALICE, LedgerTestContext.START));
        assertThat(cases.findByIssueIdOrderById(issue.id())).containsExactly(observed);

        OperationKey request = new OperationKey(UUID.randomUUID().toString());
        HistoryEntry entry = history.insert(new HistoryEntry(
                ids.next(Counter.HISTORY), LedgerTestContext.START, Seed.ALICE, Channel.AUTHOR.login(),
                ActorAssurance.DECLARED, HistoryEntity.ISSUE, issue.id().value(), HistoryOp.EDIT, null, null,
                JsonDocument.of("{\"title\":[\"a\",\"b\"]}"), JsonDocument.of("{\"version\":1}"),
                "a reason of enough length", "https://evidence", Sha256.of("c".repeat(64)), null, request, null,
                null));
        assertThat(history.findById(entry.id())).hasValue(entry);
        assertThat(history.findByEntityAndEntityIdOrderById(HistoryEntity.ISSUE, issue.id().value()))
                .containsExactly(entry);

        Receipt receipt = receipts.insert(Receipt.reserve(request, ReceiptOperation.SAVE, Sha256.of("d".repeat(64)),
                Seed.ALICE, Channel.AUTHOR, LedgerTestContext.START));
        assertThat(receipts.findByAuthorIdAndOperationKey(Seed.ALICE, request)).hasValue(receipt);
        assertThat(receipt.response()).isNull();

        SessionGrant grant = grants.insert(SessionGrant.issue(
                ids.next(Counter.SESSION_GRANT), Seed.ALICE, project.id(), Scope.ISSUE, AuthorId.OWNER,
                LedgerTestContext.START, null, "note"));
        assertThat(grants.findById(grant.id())).hasValue(grant);
        assertThat(grants.findByActiveKey(grant.activeKey())).hasValue(grant);

        SessionToken token = tokens.insert(SessionToken.issue(
                ids.next(Counter.SESSION_TOKEN), Seed.ALICE, "a".repeat(64), "pgtk_abc", AuthorId.OWNER,
                LedgerTestContext.START, LedgerTestContext.START.plusSeconds(3600), "http client"));
        assertThat(tokens.findById(token.id())).hasValue(token);
        assertThat(tokens.findByTokenHash("a".repeat(64))).hasValue(token);
    }
}
