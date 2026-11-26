// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.application;

import static org.assertj.core.api.Assertions.assertThat;
import org.agentledger.testing.TestOwners;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.agentledger.application.admin.CommonCodeService;
import org.agentledger.application.admin.CommonCodeService.CodeCommand;
import org.agentledger.application.history.Change;
import org.agentledger.application.support.Expect;
import org.agentledger.application.support.LedgerServiceTest;
import org.agentledger.application.support.Scenario;
import org.agentledger.application.write.LinkCommand;
import org.agentledger.application.write.LinkService;
import org.agentledger.application.write.SaveService;
import org.agentledger.application.write.WriteResult.LinkResult;
import org.agentledger.application.write.WriteResult.SaveResult;
import org.agentledger.domain.error.LedgerException;
import org.agentledger.domain.error.LookupCode;
import org.agentledger.domain.error.TransitionCode;
import org.agentledger.domain.error.ValidationCode;
import org.agentledger.domain.error.ValidationException;
import org.agentledger.domain.error.ValidationReason;
import org.agentledger.domain.model.HistoryEntry;
import org.agentledger.domain.rule.HistoryOp;

/** SVC-LNK-01 .. SVC-LNK-11: links and unlinks across issues, specs, tasks, criteria and relations. */
@LedgerServiceTest
class LinkTest {

    private LinkResult link(Scenario s, String kind, String a, String b, Boolean remove) {
        return s.bean(LinkService.class).link(Scenario.AUTHOR, new LinkCommand(Scenario.uuid(), kind, a, b,
                Scenario.REASON, remove));
    }

    @Test
    @DisplayName("SVC-LNK-01: a relation is one link whatever the order of its two issues")
    void lnk01_relationOrder(Scenario s) {
        SaveResult i1 = s.newIssue("one");
        SaveResult i2 = s.newIssue("two");
        LinkResult first = link(s, "same_family", i2.id(), i1.id(), null);
        assertThat(first.id()).isEqualTo(i1.id() + "|" + i2.id() + "|same_family");
        assertThat(first.entity()).isEqualTo("issue_relation");
        Expect.failure(() -> link(s, "same_family", i1.id(), i2.id(), null), TransitionCode.NOTHING_CHANGES);
        HistoryEntry row = s.historyRow(first.historyId());
        assertThat(row.op()).isEqualTo(HistoryOp.LINK);
        assertThat(row.beforeData()).isNull();
        assertThat(s.changes(row.id()).entries()).containsExactly(Map.entry("kind", new Change(null, "same_family")));
    }

    @Test
    @DisplayName("LDIR-04: a directed relation (caused_by, blocks) keeps the ends in the order of the caller, and the other order is another relation")
    void ldir04_directedRelationKeepsTheCallersOrder(Scenario s) {
        SaveResult i1 = s.newIssue("one");
        SaveResult i2 = s.newIssue("two");
        LinkResult first = link(s, "caused_by", i2.id(), i1.id(), null);
        assertThat(first.id()).isEqualTo(i2.id() + "|" + i1.id() + "|caused_by");
        assertThat(s.count("SELECT COUNT(*) FROM issue_relation WHERE a = ? AND b = ? AND kind = 'caused_by'",
                i2.id(), i1.id())).isEqualTo(1);
        LinkResult other = link(s, "caused_by", i1.id(), i2.id(), null);
        assertThat(other.id()).isEqualTo(i1.id() + "|" + i2.id() + "|caused_by");
        assertThat(s.count("SELECT COUNT(*) FROM issue_relation WHERE kind = 'caused_by' AND a IN (?, ?)", i1.id(),
                i2.id())).isEqualTo(2);
        Expect.failure(() -> link(s, "caused_by", i2.id(), i1.id(), null), TransitionCode.NOTHING_CHANGES);
        assertThat(s.historyRow(first.historyId()).entityId()).isEqualTo(first.id());
        LinkResult blocks = link(s, "blocks", i2.id(), i1.id(), null);
        assertThat(blocks.id()).isEqualTo(i2.id() + "|" + i1.id() + "|blocks");
    }

    @Test
    @DisplayName("LDIR-05: an unlink finds a directed relation with the same rule that stored it, and keeps the removed row")
    void ldir05_unlinkOfADirectedRelation(Scenario s) {
        SaveResult i1 = s.newIssue("one");
        SaveResult i2 = s.newIssue("two");
        link(s, "caused_by", i2.id(), i1.id(), null);
        Expect.failure(() -> link(s, "caused_by", i1.id(), i2.id(), true), TransitionCode.NOTHING_CHANGES);
        assertThat(s.count("SELECT COUNT(*) FROM issue_relation WHERE kind = 'caused_by' AND a IN (?, ?)", i1.id(),
                i2.id())).isEqualTo(1);
        LinkResult removed = link(s, "caused_by", i2.id(), i1.id(), true);
        assertThat(removed.removed()).isTrue();
        assertThat(removed.id()).isEqualTo(i2.id() + "|" + i1.id() + "|caused_by");
        HistoryEntry row = s.historyRow(removed.historyId());
        assertThat(row.op()).isEqualTo(HistoryOp.UNLINK);
        assertThat(row.beforeData()).isNotNull();
        assertThat(s.count("SELECT COUNT(*) FROM issue_relation WHERE kind = 'caused_by' AND a IN (?, ?)", i1.id(),
                i2.id())).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"same_site", "same_family", "same_work_unit", "regression_of"})
    @DisplayName("LDIR-06: a relation kind without a direction (same_site, same_family, same_work_unit, regression_of) is one relation in either order and is stored in canonical order")
    void ldir06_canonicalKinds(String kind, Scenario s) {
        SaveResult i1 = s.newIssue("one");
        SaveResult i2 = s.newIssue("two");
        LinkResult first = link(s, kind, i2.id(), i1.id(), null);
        assertThat(first.id()).isEqualTo(i1.id() + "|" + i2.id() + "|" + kind);
        Expect.failure(() -> link(s, kind, i1.id(), i2.id(), null), TransitionCode.NOTHING_CHANGES);
        assertThat(s.count("SELECT COUNT(*) FROM issue_relation WHERE a = ? AND b = ? AND kind = ?", i1.id(), i2.id(),
                kind)).isEqualTo(1);
        assertThat(link(s, kind, i2.id(), i1.id(), true).removed()).isTrue();
        assertThat(s.count("SELECT COUNT(*) FROM issue_relation WHERE kind = ? AND a IN (?, ?)", kind, i1.id(),
                i2.id())).isZero();
    }

    @Test
    @DisplayName("LDIR-07: a relation kind added later declares its direction, and a retired directed kind is still unlinked in the stored order")
    void ldir07_newDirectedKind(Scenario s) {
        String kind = "DIR_" + s.code();
        CommonCodeService codes = s.bean(CommonCodeService.class);
        codes.set(new CodeCommand("RELATION_KIND", kind, "방향", "Directed", null, null, 61, null, "GIVEN"),
                TestOwners.OWNER);
        SaveResult i1 = s.newIssue("one");
        SaveResult i2 = s.newIssue("two");
        assertThat(link(s, kind, i2.id(), i1.id(), null).id()).isEqualTo(i2.id() + "|" + i1.id() + "|" + kind);
        codes.set(new CodeCommand("RELATION_KIND", kind, "방향", "Directed", null, null, 61, "INACTIVE"),
                TestOwners.OWNER);
        Expect.failure(() -> link(s, kind, i1.id(), i2.id(), null), ValidationCode.CODE_INVALID);
        Expect.failure(() -> link(s, kind, i1.id(), i2.id(), true), TransitionCode.NOTHING_CHANGES);
        assertThat(link(s, kind, i2.id(), i1.id(), true).removed()).isTrue();
        String plain = "PLN_" + s.code();
        codes.set(new CodeCommand("RELATION_KIND", plain, "일반", "Plain", null, null, 62, null), TestOwners.OWNER);
        assertThat(link(s, plain, i2.id(), i1.id(), null).id()).isEqualTo(i1.id() + "|" + i2.id() + "|" + plain);
    }

    @Test
    @DisplayName("LDIR-09: the direction of a relation kind cannot change while relations of it are stored")
    void ldir09_directionInUse(Scenario s) {
        String kind = "USE_" + s.code();
        CommonCodeService codes = s.bean(CommonCodeService.class);
        codes.set(new CodeCommand("RELATION_KIND", kind, "사용", "Use", null, null, 63, null, "GIVEN"), TestOwners.OWNER);
        codes.set(new CodeCommand("RELATION_KIND", kind, "사용", "Use", null, null, 63, null, "CANONICAL"),
                TestOwners.OWNER);
        SaveResult i1 = s.newIssue("one");
        SaveResult i2 = s.newIssue("two");
        link(s, kind, i2.id(), i1.id(), null);
        LedgerException refused = Expect.failure(() -> codes.set(new CodeCommand("RELATION_KIND", kind, "사용", "Use",
                null, null, 63, null, "GIVEN"), TestOwners.OWNER), ValidationCode.VALIDATION_FAILED);
        assertThat(Expect.firstIssue(refused).reason()).isEqualTo(ValidationReason.DIRECTION_IN_USE);
        assertThat(Expect.firstIssue(refused).reasonArgs()).containsExactly("1");
        codes.set(new CodeCommand("RELATION_KIND", kind, "사용 중", "In use", null, null, 63, null, "CANONICAL"),
                TestOwners.OWNER);
        codes.set(new CodeCommand("RELATION_KIND", kind, "사용 중", "In use", null, null, 63, "INACTIVE"),
                TestOwners.OWNER);
        Expect.failure(() -> codes.set(new CodeCommand("RELATION_KIND", kind, "사용 중", "In use", null, null, 63, null,
                "SIDEWAYS"), TestOwners.OWNER), ValidationCode.VALIDATION_FAILED);
        Expect.failure(() -> codes.set(new CodeCommand("ISSUE_CATEGORY", "DIR_" + s.code(), "방향", "Dir", null, null, 64,
                null, "GIVEN"), TestOwners.OWNER), ValidationCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("SVC-LNK-02: issue_task, issue_spec and spec_task links and unlinks")
    void lnk02_workLinks(Scenario s) {
        SaveResult i = s.newIssue("i");
        SaveResult sp = s.newSpec("sp");
        SaveResult t = s.newTask("t");
        LinkResult it = link(s, "issue_task", i.id(), t.id(), null);
        assertThat(it.entity()).isEqualTo("issue_task");
        assertThat(it.id()).isEqualTo(i.id() + "|" + t.id());
        LinkResult isp = link(s, "issue_spec", i.id(), sp.id(), null);
        assertThat(isp.entity()).isEqualTo("issue_spec");
        assertThat(isp.id()).isEqualTo(i.id() + "|" + sp.id());
        LinkResult spt = link(s, "spec_task", sp.id(), t.id(), null);
        assertThat(spt.entity()).isEqualTo("spec_task");
        assertThat(spt.id()).isEqualTo(sp.id() + "|" + t.id());
    }

    static Stream<String> criterionKinds() {
        return Stream.of("criterion_task", "criterion_issue", "criterion_spec");
    }

    @ParameterizedTest
    @MethodSource("criterionKinds")
    @DisplayName("SVC-LNK-03: a criterion is linked to a task, an issue or a spec")
    void lnk03_criterionLinks(String kind, Scenario s) {
        SaveResult c = s.newCriterion("1", "r", "c");
        SaveResult target = "criterion_task".equals(kind) ? s.newTask("t")
                : "criterion_issue".equals(kind) ? s.newIssue("i") : s.newSpec("sp");
        LinkResult result = link(s, kind, c.id(), target.id(), null);
        assertThat(result.entity()).isEqualTo("criterion_link");
        assertThat(result.id()).isEqualTo(c.id() + "|" + target.id());
    }

    @Test
    @DisplayName("SVC-LNK-04: an unknown kind lists the kinds that exist")
    void lnk04_unknownKind(Scenario s) {
        SaveResult i = s.newIssue("i");
        ValidationException e = (ValidationException) Expect.failure(() -> link(s, "related", i.id(), i.id(), null),
                ValidationCode.VALIDATION_FAILED);
        assertThat(e.issues().get(0).allowed()).startsWith("issue_task", "issue_spec", "spec_task", "criterion_task",
                "criterion_issue", "criterion_spec");
    }

    @Test
    @DisplayName("SVC-LNK-05: an inactive relation kind is refused for a new link")
    void lnk05_inactiveKind(Scenario s) {
        s.bean(CommonCodeService.class).set(new CodeCommand("RELATION_KIND", "old_kind", "old", null, null, null, 50,
                "INACTIVE"), TestOwners.OWNER);
        SaveResult i1 = s.newIssue("one");
        SaveResult i2 = s.newIssue("two");
        Expect.failure(() -> link(s, "old_kind", i1.id(), i2.id(), null), ValidationCode.CODE_INVALID);
    }

    @Test
    @DisplayName("SVC-LNK-06: removing a link records an UNLINK and keeps the removed row")
    void lnk06_remove(Scenario s) {
        SaveResult i = s.newIssue("i");
        SaveResult t = s.newTask("t");
        link(s, "issue_task", i.id(), t.id(), null);
        LinkResult removed = link(s, "issue_task", i.id(), t.id(), true);
        assertThat(removed.removed()).isTrue();
        HistoryEntry row = s.historyRow(removed.historyId());
        assertThat(row.op()).isEqualTo(HistoryOp.UNLINK);
        assertThat(row.beforeData()).isNotNull();
        assertThat(s.count("SELECT COUNT(*) FROM issue_task WHERE issue_id = ?", i.id())).isZero();
        Expect.failure(() -> link(s, "issue_task", i.id(), t.id(), true), TransitionCode.NOTHING_CHANGES);
    }

    /** A link to something that does not exist. */
    record Missing(String name, Function<Scenario, LinkCommand> command) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Missing> missing() {
        return Stream.of(
                new Missing("issue", s -> new LinkCommand(Scenario.uuid(), "issue_task", s.issueId(9), s.taskId(1),
                        Scenario.REASON, null)),
                new Missing("task of an issue link", s -> new LinkCommand(Scenario.uuid(), "issue_task",
                        s.issueId(1), s.taskId(9), Scenario.REASON, null)),
                new Missing("spec of an issue link", s -> new LinkCommand(Scenario.uuid(), "issue_spec",
                        s.issueId(1), s.specId(9), Scenario.REASON, null)),
                new Missing("criterion", s -> new LinkCommand(Scenario.uuid(), "criterion_task", s.criterionId("9"),
                        s.taskId(1), Scenario.REASON, null)),
                new Missing("issue of a relation", s -> new LinkCommand(Scenario.uuid(), "blocks", s.issueId(1),
                        s.issueId(9), Scenario.REASON, null)));
    }

    @ParameterizedTest
    @MethodSource("missing")
    @DisplayName("SVC-LNK-07: a link to an unknown issue, spec, task or criterion")
    void lnk07_notFound(Missing missing, Scenario s) {
        s.newIssue("i");
        s.newSpec("sp");
        s.newTask("t");
        Expect.failure(() -> s.bean(LinkService.class).link(Scenario.AUTHOR, missing.command().apply(s)),
                LookupCode.NOT_FOUND);
    }

    @Test
    @DisplayName("SVC-LNK-08: a relation between an issue and itself")
    void lnk08_self(Scenario s) {
        SaveResult i = s.newIssue("i");
        Expect.failure(() -> link(s, "blocks", i.id(), i.id(), null), ValidationCode.VALIDATION_FAILED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"same_site", "caused_by", "blocks", "same_family", "same_work_unit", "regression_of"})
    @DisplayName("SVC-LNK-09: every seeded relation kind is accepted")
    void lnk09_seededKinds(String kind, Scenario s) {
        SaveResult i1 = s.newIssue("one");
        SaveResult i2 = s.newIssue("two");
        assertThat(link(s, kind, i1.id(), i2.id(), null).id()).endsWith("|" + kind);
    }

    @Test
    @DisplayName("SVC-LNK-10: a code added with code-set is usable at once, without a restart")
    void lnk10_newKind(Scenario s) {
        s.bean(CommonCodeService.class).set(new CodeCommand("RELATION_KIND", "test_relation", "test", null, null, null,
                60, null), TestOwners.OWNER);
        SaveResult i1 = s.newIssue("one");
        SaveResult i2 = s.newIssue("two");
        assertThat(link(s, "test_relation", i1.id(), i2.id(), null).id()).endsWith("|test_relation");
    }

    @Test
    @DisplayName("SVC-LNK-11: ids that contain the separator do not collide")
    void lnk11_pipeInIds(Scenario s) {
        SaveResult i = s.newIssue("i");
        String pipeTask = s.code() + "-T|PIPE";
        s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("task", pipeTask, s.taskFields("pipe")));
        LinkResult linked = link(s, "issue_task", i.id(), pipeTask, null);
        assertThat(linked.id()).isEqualTo(i.id() + "|" + pipeTask);
        assertThat(link(s, "issue_task", i.id(), pipeTask, true).removed()).isTrue();
    }

    @Test
    @DisplayName("SVC-LNK-11: two criterion links whose ids split differently are both stored")
    void lnk11_aliasingPairs(Scenario s) {
        String c1 = s.code() + "-C";
        String t1 = "A|" + s.code() + "-T";
        String c2 = s.code() + "-C|A";
        String t2 = s.code() + "-T";
        s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("criterion", c1, s.criterionFields("r", "one")));
        s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("criterion", c2, s.criterionFields("r", "two")));
        s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("task", t1, s.taskFields("one")));
        s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("task", t2, s.taskFields("two")));
        link(s, "criterion_task", c1, t1, null);
        link(s, "criterion_task", c2, t2, null);
        assertThat(s.count("SELECT COUNT(*) FROM criterion_link WHERE criterion_id IN (?, ?)", c1, c2)).isEqualTo(2);
    }
}
