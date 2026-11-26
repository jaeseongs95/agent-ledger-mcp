// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.veridger.application.read.AttentionService;
import org.veridger.application.read.AttentionService.AttentionQuery;
import org.veridger.application.read.DetailService;
import org.veridger.application.read.DetailService.CaseView;
import org.veridger.application.read.DetailService.IssueDetail;
import org.veridger.application.read.DetailService.SpecDetail;
import org.veridger.application.read.GetService;
import org.veridger.application.support.Expect;
import org.veridger.application.support.Fields;
import org.veridger.application.support.LedgerServiceTest;
import org.veridger.application.support.MergeFixture;
import org.veridger.application.support.Scenario;
import org.veridger.application.write.BatchOpInput;
import org.veridger.application.write.BatchService;
import org.veridger.application.write.LinkCommand;
import org.veridger.application.write.LinkService;
import org.veridger.application.write.RecordCaseCommand;
import org.veridger.application.write.RecordCaseService;
import org.veridger.application.write.RecordDeliverableService;
import org.veridger.application.write.ReviewService;
import org.veridger.application.write.SaveService;
import org.veridger.application.write.TransitionService;
import org.veridger.application.write.WriteResult.SaveResult;
import org.veridger.database.repository.Counter;
import org.veridger.database.repository.HistoryRepository;
import org.veridger.database.repository.IdAllocator;
import org.veridger.domain.error.LookupCode;
import org.veridger.domain.error.TransitionCode;
import org.veridger.domain.error.ValidationCode;
import org.veridger.domain.model.HistoryEntry;
import org.veridger.domain.rule.ActorAssurance;
import org.veridger.domain.rule.AttentionKind;
import org.veridger.domain.rule.HistoryEntity;
import org.veridger.domain.rule.HistoryOp;
import org.veridger.domain.rule.RelationEnd;
import org.veridger.domain.value.ImportedActorId;
import org.veridger.domain.value.JsonDocument;
import org.veridger.domain.value.OperationKey;

/** MCR-G01 .. MCR-G07, MCR-D01 .. MCR-D05, MCR-S01, MCR-T01, MCR-T02 and read-side checks. */
@LedgerServiceTest
class ReadDetailTest {

    @Test
    @DisplayName("MCR-G01: an issue, a spec, a task and a criterion in one call, in the order of the request")
    void g01_mixedIds(Scenario s) {
        SaveResult i = s.newIssue("i");
        SaveResult sp = s.newSpec("sp");
        SaveResult t = s.newTask("t");
        SaveResult c = s.newCriterion("1", "r", "c");
        var result = s.bean(GetService.class).get(List.of(c.id(), i.id(), sp.id(), t.id()));
        assertThat(result.items()).extracting(GetService.Item::entity)
                .containsExactly("criterion", "issue", "spec", "task");
        assertThat(result.items()).extracting(GetService.Item::state)
                .containsExactly("UNKNOWN", "OPEN", "PLANNED", "PLANNED");
        assertThat(result.items()).extracting(GetService.Item::version).containsExactly(1L, 1L, 1L, 1L);
        assertThat(result.items()).extracting(GetService.Item::projectId).containsOnly(s.projectId());
        assertThat(result.notFound()).isEmpty();
    }

    @Test
    @DisplayName("MCR-G02: unknown ids are listed, not an error; duplicates come once")
    void g02_unknownAndDuplicates(Scenario s) {
        SaveResult i = s.newIssue("i");
        var result = s.bean(GetService.class).get(List.of(i.id(), s.issueId(99), i.id(), "lower-case-1"));
        assertThat(result.items()).extracting(GetService.Item::id).containsExactly(i.id());
        assertThat(result.notFound()).containsExactly(s.issueId(99), "lower-case-1");
        assertThat(s.bean(GetService.class).get(List.of(s.issueId(98))).items()).isEmpty();
    }

    @Test
    @DisplayName("MCR-G03: the version read by get is the expected version of the next save")
    void g03_readThenWrite(Scenario s) {
        SaveResult i = s.newIssue("i");
        s.bean(SaveService.class).save(Scenario.AUTHOR, s.edit("issue", i.id(), 1, Map.of("title", "two")));
        long version = s.bean(GetService.class).get(List.of(i.id())).items().getFirst().version();
        assertThat(s.bean(SaveService.class).save(Scenario.AUTHOR, s.edit("issue", i.id(), version,
                Map.of("title", "three"))).version()).isEqualTo(3);
    }

    @Test
    @DisplayName("MCR-G04: an id that is an issue and a task gives both items")
    void g04_sameIdTwoKinds(Scenario s) {
        SaveResult i = s.newIssue("i");
        s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("task", i.id(), s.taskFields("t")));
        var items = s.bean(GetService.class).get(List.of(i.id())).items();
        assertThat(items).extracting(GetService.Item::entity).containsExactly("issue", "task");
    }

    @Test
    @DisplayName("MCR-G05: no ids and too many ids are refused")
    void g05_bounds(Scenario s) {
        Expect.failure(() -> s.bean(GetService.class).get(List.of()), ValidationCode.VALIDATION_FAILED);
        List<String> tooMany = IntStream.rangeClosed(1, 201).mapToObj(n -> "X-I-" + n).toList();
        Expect.failure(() -> s.bean(GetService.class).get(tooMany), ValidationCode.VALIDATION_FAILED);
        assertThat(s.bean(GetService.class).get(tooMany.subList(0, 200)).notFound()).hasSize(200);
    }

    @Test
    @DisplayName("MCR-G06: a merged issue shows where it went")
    void g06_merged(Scenario s) {
        SaveResult source = s.newIssue("source");
        SaveResult target = s.newIssue("target");
        MergeFixture.merge(s, source.id(), 1, target.id());
        var item = s.bean(GetService.class).get(List.of(source.id())).items().getFirst();
        assertThat(item.state()).isEqualTo("MERGED");
        assertThat(item.mergedInto()).isEqualTo(target.id());
    }

    @Test
    @DisplayName("MCR-G07: a verified issue reads as VERIFIED")
    void g07_verified(Scenario s) {
        SaveResult i = s.verifiedIssue("i");
        var item = s.bean(GetService.class).get(List.of(i.id())).items().getFirst();
        assertThat(item.state()).isEqualTo("VERIFIED");
        assertThat(item.version()).isEqualTo(3);
        assertThat(item.projectId()).isEqualTo(s.projectId());
    }

    @Test
    @DisplayName("MCR-D01: the detail of an issue has deliverables, tasks, specs, criteria, cases, relations and changes")
    void d01_fullDetail(Scenario s) {
        SaveResult i = s.newIssue("i");
        SaveResult other = s.newIssue("other");
        SaveResult sp = s.newSpec("sp");
        SaveResult t = s.newTask("t");
        SaveResult c = s.newCriterion("1", "r", "c");
        s.bean(LinkService.class).link(Scenario.AUTHOR, new LinkCommand(Scenario.uuid(), "issue_task", i.id(), t.id(),
                Scenario.REASON, null));
        s.bean(LinkService.class).link(Scenario.AUTHOR, new LinkCommand(Scenario.uuid(), "issue_spec", i.id(), sp.id(),
                Scenario.REASON, null));
        s.bean(LinkService.class).link(Scenario.AUTHOR, new LinkCommand(Scenario.uuid(), "criterion_issue", c.id(), i.id(),
                Scenario.REASON, null));
        s.bean(LinkService.class).link(Scenario.AUTHOR, new LinkCommand(Scenario.uuid(), "blocks", other.id(), i.id(),
                Scenario.REASON, null));
        s.bean(RecordCaseService.class).recordCase(Scenario.AUTHOR, new RecordCaseCommand(Scenario.uuid(), i.id(), "seen",
                Scenario.REASON, "log", "quote", "2026-11-05T09:00:00Z"));
        s.deliver(Scenario.AUTHOR, i.id(), Scenario.ref('a'));
        IssueDetail detail = s.bean(DetailService.class).issue(i.id());
        assertThat(detail.projectId()).isEqualTo(s.projectId());
        assertThat(detail.title()).isEqualTo("i");
        assertThat(detail.status()).isEqualTo("RESOLVED");
        assertThat(detail.deliverables()).extracting(DetailService.DeliverableView::deliverableRef)
                .containsExactly(Scenario.ref('a'));
        assertThat(detail.tasks()).extracting(DetailService.TaskRef::task).containsExactly(t.id());
        assertThat(detail.specs()).containsExactly(sp.id());
        assertThat(detail.criteria()).containsExactly(c.id());
        assertThat(detail.relations()).extracting(DetailService.RelationView::kind).containsExactly("blocks");
        assertThat(detail.relations()).extracting(DetailService.RelationView::other).containsExactly(other.id());
        assertThat(detail.relations()).extracting(DetailService.RelationView::end).containsExactly(RelationEnd.B);
        assertThat(s.bean(DetailService.class).issue(other.id()).relations())
                .extracting(DetailService.RelationView::end).containsExactly(RelationEnd.A);
        assertThat(detail.cases()).extracting(CaseView::observation).containsExactly("seen");
        assertThat(detail.lastChange().op()).isEqualTo("TRANSITION");
        assertThat(detail.lastChange().login()).isEqualTo("ledger_author");
        assertThat(detail.recentChanges()).hasSizeLessThanOrEqualTo(DetailService.RECENT_CHANGES).isNotEmpty();
        assertThat(detail.version()).isEqualTo(2);
    }

    @Test
    @DisplayName("MCR-S01: the detail of a spec includes parent/children, deliverables, tasks, issues, criteria and completedBy")
    void s01_specDetail(Scenario s) {
        SaveResult parent = s.newSpec("parent");
        var childFields = s.specFields("child");
        childFields.put("parent_id", parent.id());
        SaveResult child = s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("spec", null, childFields));
        SaveResult t = s.newTask("t");
        SaveResult i = s.newIssue("i");
        SaveResult c = s.newCriterion("1", "m", "c");
        s.bean(LinkService.class).link(Scenario.AUTHOR, new LinkCommand(Scenario.uuid(), "spec_task", parent.id(), t.id(),
                Scenario.REASON, null));
        s.bean(LinkService.class).link(Scenario.AUTHOR, new LinkCommand(Scenario.uuid(), "issue_spec", i.id(), parent.id(),
                Scenario.REASON, null));
        s.bean(LinkService.class).link(Scenario.AUTHOR, new LinkCommand(Scenario.uuid(), "criterion_spec", c.id(),
                parent.id(), Scenario.REASON, null));
        s.bean(RecordDeliverableService.class).recordDeliverable(Scenario.AUTHOR,
                s.deliverableCommand("spec", parent.id(), Scenario.ref('s')));
        SpecDetail detail = s.bean(DetailService.class).spec(parent.id());
        assertThat(detail.status()).isEqualTo("COMPLETED");
        assertThat(detail.completedBy()).isEqualTo(Scenario.AUTHOR);
        assertThat(detail.childSpecs()).containsExactly(child.id());
        assertThat(detail.tasks()).containsExactly(t.id());
        assertThat(detail.issues()).containsExactly(i.id());
        assertThat(detail.criteria()).containsExactly(c.id());
        assertThat(detail.deliverables()).hasSize(1);
        Expect.failure(() -> s.bean(DetailService.class).spec(s.specId(99)), LookupCode.NOT_FOUND);
    }

    @Test
    @DisplayName("MCR-D02 / SVC-CAS-12: the cases of an issue include those of the issues merged into it")
    void d02_mergedCases(Scenario s) {
        SaveResult i3 = s.newIssue("three");
        SaveResult i4 = s.newIssue("four");
        s.bean(RecordCaseService.class).recordCase(Scenario.AUTHOR, new RecordCaseCommand(Scenario.uuid(), i3.id(),
                "on three", Scenario.REASON, null, null, "2026-11-05T08:00:00Z"));
        s.bean(RecordCaseService.class).recordCase(Scenario.AUTHOR, new RecordCaseCommand(Scenario.uuid(), i4.id(),
                "on four", Scenario.REASON, null, null, "2026-11-05T09:00:00Z"));
        MergeFixture.merge(s, i4.id(), 1, i3.id());
        List<CaseView> cases = s.bean(DetailService.class).issue(i3.id()).cases();
        assertThat(cases).extracting(CaseView::source).containsExactly("case", "case", "merged_symptom");
        assertThat(cases).extracting(CaseView::observation).containsExactly("on three", "on four", "symptom of four");
        assertThat(cases).extracting(CaseView::issue).containsExactly(i3.id(), i4.id(), i4.id());
    }

    @Test
    @DisplayName("MCR-D02 / SVC-CAS-13: a merge chain of 50 issues terminates and lists every one")
    void d02_chainOfFifty(Scenario s) {
        List<BatchOpInput> ops = IntStream.rangeClosed(1, 50).mapToObj(n -> (BatchOpInput) new BatchOpInput.Save(
                "issue", Fields.of(s.issueFields("chain " + n)), Scenario.REASON, null, null)).toList();
        s.bean(BatchService.class).batch(Scenario.AUTHOR, Scenario.uuid(), ops);
        IntStream.iterate(50, k -> k >= 2, k -> k - 1).forEach(k -> MergeFixture.merge(s, s.issueId(k), 1,
                s.issueId(k - 1)));
        List<CaseView> cases = s.bean(DetailService.class).issue(s.issueId(1)).cases();
        assertThat(cases).hasSize(49).allSatisfy(c -> assertThat(c.source()).isEqualTo("merged_symptom"));
        assertThat(cases.stream().map(CaseView::issue).collect(Collectors.toSet())).hasSize(49);
    }

    @Test
    @DisplayName("MCR-D02 / SVC-CAS-13: a chain I4 into I3 into I2 shows the cases of all three on I2")
    void d02_threeLevels(Scenario s) {
        SaveResult i2 = s.newIssue("two");
        SaveResult i3 = s.newIssue("three");
        SaveResult i4 = s.newIssue("four");
        for (SaveResult i : List.of(i2, i3, i4)) {
            s.bean(RecordCaseService.class).recordCase(Scenario.AUTHOR, new RecordCaseCommand(Scenario.uuid(), i.id(),
                    "on " + i.id(), Scenario.REASON, null, null, null));
        }
        MergeFixture.merge(s, i4.id(), 1, i3.id());
        MergeFixture.merge(s, i3.id(), 1, i2.id());
        List<CaseView> cases = s.bean(DetailService.class).issue(i2.id()).cases();
        assertThat(cases.stream().filter(c -> "case".equals(c.source())).map(CaseView::issue))
                .containsExactlyInAnyOrder(i2.id(), i3.id(), i4.id());
        assertThat(cases.stream().filter(c -> "merged_symptom".equals(c.source())).map(CaseView::issue))
                .containsExactlyInAnyOrder(i3.id(), i4.id());
    }

    @Test
    @DisplayName("SVC-CAS-14: a case keeps evidence, quote and the observed instant, and names its recorder")
    void cas14_caseFields(Scenario s) {
        SaveResult i = s.newIssue("i");
        s.bean(RecordCaseService.class).recordCase(Scenario.AUTHOR, new RecordCaseCommand(Scenario.uuid(), i.id(), "seen",
                Scenario.REASON, "log line", "said so", "2026-11-05T18:00:00+09:00"));
        CaseView c = s.bean(DetailService.class).issue(i.id()).cases().getFirst();
        assertThat(c.evidence()).isEqualTo("log line");
        assertThat(c.sourceQuote()).isEqualTo("said so");
        assertThat(c.observedAt()).isEqualTo(Instant.parse("2026-11-05T09:00:00Z"));
        assertThat(c.by()).isEqualTo(Scenario.AUTHOR);
    }

    @Test
    @DisplayName("MCR-D03: imported history rows are not the last change")
    void d03_importIsIgnored(Scenario s) {
        SaveResult i = s.newIssue("i");
        IdAllocator ids = s.bean(IdAllocator.class);
        s.ctx().tx(() -> s.bean(HistoryRepository.class).insert(new HistoryEntry(ids.next(Counter.HISTORY),
                Instant.parse("2027-01-01T00:00:00Z"), new ImportedActorId("claude:old"), "ledger_author",
                ActorAssurance.IMPORTED_LOGIN, HistoryEntity.ISSUE, i.id(), HistoryOp.IMPORT, null, null,
                JsonDocument.EMPTY_OBJECT, null, "imported by a migration", null, null, null,
                new OperationKey(Scenario.uuid()), "import-1")));
        IssueDetail detail = s.bean(DetailService.class).issue(i.id());
        assertThat(detail.lastChange().op()).isEqualTo("CREATE");
        assertThat(detail.recentChanges()).extracting(DetailService.ChangeSummary::op).contains("IMPORT");
    }

    @Test
    @DisplayName("MCR-D04 / SVC-FIX-12: creator, owner and resolver are told apart")
    void d04_creatorOwnerResolver(Scenario s) {
        SaveResult i = s.newIssue("i");
        s.deliver(Scenario.AUTHOR, i.id(), Scenario.ref('a'));
        s.deliver(Scenario.REVIEWER, i.id(), Scenario.ref('b'));
        IssueDetail resolved = s.bean(DetailService.class).issue(i.id());
        assertThat(resolved.createdBy()).isEqualTo(Scenario.AUTHOR);
        assertThat(resolved.ownerActorId()).isEqualTo(Scenario.REVIEWER);
        assertThat(resolved.resolvedBy()).isEqualTo(Scenario.REVIEWER);
        assertThat(resolved.resolvedAt()).isNotNull();
        Expect.failure(() -> s.bean(ReviewService.class).review(Scenario.AUTHOR, s.move("issue", i.id(), 3, "VERIFIED",
                Scenario.EVIDENCE)), TransitionCode.SAME_SESSION);
        String verifier = s.actorWith("verify");
        s.bean(ReviewService.class).review(verifier, s.move("issue", i.id(), 3, "VERIFIED", Scenario.EVIDENCE));
        IssueDetail verified = s.bean(DetailService.class).issue(i.id());
        assertThat(verified.lastChange().author()).isEqualTo(verifier);
        assertThat(verified.createdBy()).isEqualTo(Scenario.AUTHOR);
        assertThat(verified.ownerActorId()).isEqualTo(Scenario.REVIEWER);
    }

    @Test
    @DisplayName("MCR-D05: an unknown issue")
    void d05_notFound(Scenario s) {
        Expect.failure(() -> s.bean(DetailService.class).issue(s.issueId(9)), LookupCode.NOT_FOUND);
    }

    @Test
    @DisplayName("MCR-T01: a task names the session that completed it, not the one that sent it back")
    void t01_taskDetail(Scenario s) {
        var fields = s.taskFields("t");
        fields.put("owner_actor_id", "codex:owner1");
        fields.put("origin_ref", "ref-1");
        SaveResult t = s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("task", null, fields));
        SaveResult i = s.newIssue("i");
        SaveResult sp = s.newSpec("sp");
        SaveResult c = s.newCriterion("1", "r", "c");
        s.bean(LinkService.class).link(Scenario.AUTHOR, new LinkCommand(Scenario.uuid(), "issue_task", i.id(), t.id(),
                Scenario.REASON, null));
        s.bean(LinkService.class).link(Scenario.AUTHOR, new LinkCommand(Scenario.uuid(), "spec_task", sp.id(), t.id(),
                Scenario.REASON, null));
        s.bean(LinkService.class).link(Scenario.AUTHOR, new LinkCommand(Scenario.uuid(), "criterion_task", c.id(), t.id(),
                Scenario.REASON, null));
        s.bean(TransitionService.class).transition(Scenario.AUTHOR, s.move("task", t.id(), 1, "COMPLETED",
                Scenario.EVIDENCE));
        s.bean(ReviewService.class).review(Scenario.REVIEWER, s.move("task", t.id(), 2, "VERIFIED",
                Scenario.EVIDENCE));
        s.bean(ReviewService.class).review(Scenario.REVIEWER, s.move("task", t.id(), 3, "COMPLETED",
                Scenario.EVIDENCE));
        DetailService.TaskDetail detail = s.bean(DetailService.class).task(t.id());
        assertThat(detail.title()).isEqualTo("t");
        assertThat(detail.purpose()).isEqualTo("purpose of t");
        assertThat(detail.successCondition()).isEqualTo("success of t");
        assertThat(detail.originRef()).isEqualTo("ref-1");
        assertThat(detail.ownerActorId()).isEqualTo("codex:owner1");
        assertThat(detail.state()).isEqualTo("COMPLETED");
        assertThat(detail.version()).isEqualTo(4);
        assertThat(detail.completedBy()).isEqualTo(Scenario.AUTHOR);
        assertThat(detail.issues()).containsExactly(i.id());
        assertThat(detail.specs()).containsExactly(sp.id());
        assertThat(detail.criteria()).containsExactly(c.id());
    }

    @Test
    @DisplayName("MCR-T02: an unknown task")
    void t02_notFound(Scenario s) {
        Expect.failure(() -> s.bean(DetailService.class).task(s.taskId(9)), LookupCode.NOT_FOUND);
    }

    @Test
    @DisplayName("SVC-FIX-11: a verified issue whose deliverable has no external check data is never an attention item")
    void fix11_noVerifiedWithoutCheck(Scenario s) {
        SaveResult i = s.verifiedIssue("i");
        List<String> kinds = new ArrayList<>();
        var page = s.bean(AttentionService.class).attention(new AttentionQuery(s.projectId(), null, 1000, null));
        page.items().forEach(item -> kinds.add(item.kind() + ":" + item.id()));
        assertThat(kinds).noneMatch(k -> k.endsWith(":" + i.id()));
        assertThat(kinds).noneMatch(k -> k.startsWith("VERIFIED_WITHOUT_"));
        assertThat(java.util.Arrays.stream(AttentionKind.values()).map(Enum::name)).doesNotContain(
                "VERIFIED_WITHOUT_CI", "VERIFIED_WITHOUT_CHECK");
    }
}
