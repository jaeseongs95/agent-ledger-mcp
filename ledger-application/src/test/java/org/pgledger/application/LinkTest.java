package org.pgledger.application;

import static org.assertj.core.api.Assertions.assertThat;
import org.pgledger.testing.TestOwners;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.pgledger.application.admin.CommonCodeService;
import org.pgledger.application.admin.CommonCodeService.CodeCommand;
import org.pgledger.application.history.Change;
import org.pgledger.application.support.Expect;
import org.pgledger.application.support.LedgerServiceTest;
import org.pgledger.application.support.Scenario;
import org.pgledger.application.write.LinkCommand;
import org.pgledger.application.write.LinkService;
import org.pgledger.application.write.SaveService;
import org.pgledger.application.write.WriteResult.LinkResult;
import org.pgledger.application.write.WriteResult.SaveResult;
import org.pgledger.domain.error.LedgerException;
import org.pgledger.domain.error.LookupCode;
import org.pgledger.domain.error.TransitionCode;
import org.pgledger.domain.error.ValidationCode;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationReason;
import org.pgledger.domain.model.HistoryEntry;
import org.pgledger.domain.rule.HistoryOp;

/** SVC-LNK-01 .. SVC-LNK-11: links and unlinks. */
@LedgerServiceTest
class LinkTest {

    private LinkResult link(Scenario s, String kind, String a, String b, Boolean remove) {
        return s.bean(LinkService.class).link(Scenario.AUTHOR, new LinkCommand(null, kind, a, b, Scenario.REASON,
                remove));
    }

    @Test
    @DisplayName("SVC-LNK-01: a relation is one link whatever the order of its two defects")
    void lnk01_relationOrder(Scenario s) {
        SaveResult d1 = s.newDefect("one");
        SaveResult d2 = s.newDefect("two");
        LinkResult first = link(s, "same_family", d2.id(), d1.id(), null);
        assertThat(first.id()).isEqualTo(d1.id() + "|" + d2.id() + "|same_family");
        assertThat(first.entity()).isEqualTo("defect_relation");
        Expect.failure(() -> link(s, "same_family", d1.id(), d2.id(), null), TransitionCode.NOTHING_CHANGES);
        HistoryEntry row = s.historyRow(first.historyId());
        assertThat(row.op()).isEqualTo(HistoryOp.LINK);
        assertThat(row.beforeData()).isNull();
        assertThat(s.changes(row.id()).entries()).containsExactly(Map.entry("kind", new Change(null, "same_family")));
    }

    @Test
    @DisplayName("LDIR-04: a directed relation (caused_by, blocks) keeps the ends in the order of the caller, and the other order is another relation")
    void ldir04_directedRelationKeepsTheCallersOrder(Scenario s) {
        SaveResult d1 = s.newDefect("one");
        SaveResult d2 = s.newDefect("two");
        // d2 sorts after d1; the caller names it first: "d2 is caused by d1"
        LinkResult first = link(s, "caused_by", d2.id(), d1.id(), null);
        assertThat(first.id()).isEqualTo(d2.id() + "|" + d1.id() + "|caused_by");
        assertThat(s.count("SELECT COUNT(*) FROM defect_relation WHERE a = ? AND b = ? AND kind = 'caused_by'",
                d2.id(), d1.id())).isEqualTo(1);
        // "d1 is caused by d2" is another statement, so it is stored as well
        LinkResult other = link(s, "caused_by", d1.id(), d2.id(), null);
        assertThat(other.id()).isEqualTo(d1.id() + "|" + d2.id() + "|caused_by");
        assertThat(s.count("SELECT COUNT(*) FROM defect_relation WHERE kind = 'caused_by' AND a IN (?, ?)", d1.id(),
                d2.id())).isEqualTo(2);
        Expect.failure(() -> link(s, "caused_by", d2.id(), d1.id(), null), TransitionCode.NOTHING_CHANGES);
        // the history of a link names the ends in the stored order
        assertThat(s.historyRow(first.historyId()).entityId()).isEqualTo(first.id());
        LinkResult blocks = link(s, "blocks", d2.id(), d1.id(), null);
        assertThat(blocks.id()).isEqualTo(d2.id() + "|" + d1.id() + "|blocks");
    }

    @Test
    @DisplayName("LDIR-05: an unlink finds a directed relation with the same rule that stored it, and keeps the removed row")
    void ldir05_unlinkOfADirectedRelation(Scenario s) {
        SaveResult d1 = s.newDefect("one");
        SaveResult d2 = s.newDefect("two");
        link(s, "caused_by", d2.id(), d1.id(), null);
        // the relation of the other direction is not stored, so removing it changes nothing
        Expect.failure(() -> link(s, "caused_by", d1.id(), d2.id(), true), TransitionCode.NOTHING_CHANGES);
        assertThat(s.count("SELECT COUNT(*) FROM defect_relation WHERE kind = 'caused_by' AND a IN (?, ?)", d1.id(),
                d2.id())).isEqualTo(1);
        LinkResult removed = link(s, "caused_by", d2.id(), d1.id(), true);
        assertThat(removed.removed()).isTrue();
        assertThat(removed.id()).isEqualTo(d2.id() + "|" + d1.id() + "|caused_by");
        HistoryEntry row = s.historyRow(removed.historyId());
        assertThat(row.op()).isEqualTo(HistoryOp.UNLINK);
        assertThat(row.beforeData()).isNotNull();
        assertThat(s.count("SELECT COUNT(*) FROM defect_relation WHERE kind = 'caused_by' AND a IN (?, ?)", d1.id(),
                d2.id())).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"same_site", "same_family", "same_fix_unit", "regression_of"})
    @DisplayName("LDIR-06: a relation kind without a direction (same_site, same_family, same_fix_unit, regression_of) is one relation in either order and is stored in canonical order")
    void ldir06_canonicalKinds(String kind, Scenario s) {
        SaveResult d1 = s.newDefect("one");
        SaveResult d2 = s.newDefect("two");
        LinkResult first = link(s, kind, d2.id(), d1.id(), null);
        assertThat(first.id()).isEqualTo(d1.id() + "|" + d2.id() + "|" + kind);
        Expect.failure(() -> link(s, kind, d1.id(), d2.id(), null), TransitionCode.NOTHING_CHANGES);
        assertThat(s.count("SELECT COUNT(*) FROM defect_relation WHERE a = ? AND b = ? AND kind = ?", d1.id(), d2.id(),
                kind)).isEqualTo(1);
        // either order removes it
        assertThat(link(s, kind, d2.id(), d1.id(), true).removed()).isTrue();
        assertThat(s.count("SELECT COUNT(*) FROM defect_relation WHERE kind = ? AND a IN (?, ?)", kind, d1.id(),
                d2.id())).isZero();
    }

    @Test
    @DisplayName("LDIR-07: a relation kind added later declares its direction, and a retired directed kind is still unlinked in the stored order")
    void ldir07_newDirectedKind(Scenario s) {
        String kind = "DIR_" + s.code();
        CommonCodeService codes = s.bean(CommonCodeService.class);
        codes.set(new CodeCommand("RELATION_KIND", kind, "방향", "Directed", null, null, 61, null, "GIVEN"),
                TestOwners.OWNER);
        SaveResult d1 = s.newDefect("one");
        SaveResult d2 = s.newDefect("two");
        assertThat(link(s, kind, d2.id(), d1.id(), null).id()).isEqualTo(d2.id() + "|" + d1.id() + "|" + kind);
        // retired: new links are refused, the direction stays (a command without direction keeps it), the unlink works
        codes.set(new CodeCommand("RELATION_KIND", kind, "방향", "Directed", null, null, 61, "INACTIVE"),
                TestOwners.OWNER);
        Expect.failure(() -> link(s, kind, d1.id(), d2.id(), null), ValidationCode.CODE_INVALID);
        Expect.failure(() -> link(s, kind, d1.id(), d2.id(), true), TransitionCode.NOTHING_CHANGES);
        assertThat(link(s, kind, d2.id(), d1.id(), true).removed()).isTrue();
        // a kind without a direction is canonical
        String plain = "PLN_" + s.code();
        codes.set(new CodeCommand("RELATION_KIND", plain, "일반", "Plain", null, null, 62, null), TestOwners.OWNER);
        assertThat(link(s, plain, d2.id(), d1.id(), null).id()).isEqualTo(d1.id() + "|" + d2.id() + "|" + plain);
    }

    @Test
    @DisplayName("LDIR-09: the direction of a relation kind cannot change while relations of it are stored")
    void ldir09_directionInUse(Scenario s) {
        String kind = "USE_" + s.code();
        CommonCodeService codes = s.bean(CommonCodeService.class);
        // no relation yet: the owner may still change the direction, in both ways
        codes.set(new CodeCommand("RELATION_KIND", kind, "사용", "Use", null, null, 63, null, "GIVEN"), TestOwners.OWNER);
        codes.set(new CodeCommand("RELATION_KIND", kind, "사용", "Use", null, null, 63, null, "CANONICAL"),
                TestOwners.OWNER);
        SaveResult d1 = s.newDefect("one");
        SaveResult d2 = s.newDefect("two");
        link(s, kind, d2.id(), d1.id(), null);
        LedgerException refused = Expect.failure(() -> codes.set(new CodeCommand("RELATION_KIND", kind, "사용", "Use",
                null, null, 63, null, "GIVEN"), TestOwners.OWNER), ValidationCode.VALIDATION_FAILED);
        assertThat(Expect.firstIssue(refused).reason()).isEqualTo(ValidationReason.DIRECTION_IN_USE);
        assertThat(Expect.firstIssue(refused).reasonArgs()).containsExactly("1");
        // the same direction (or none) and other changes are fine
        codes.set(new CodeCommand("RELATION_KIND", kind, "사용 중", "In use", null, null, 63, null, "CANONICAL"),
                TestOwners.OWNER);
        codes.set(new CodeCommand("RELATION_KIND", kind, "사용 중", "In use", null, null, 63, "INACTIVE"),
                TestOwners.OWNER);
        // a value that is no direction is refused
        Expect.failure(() -> codes.set(new CodeCommand("RELATION_KIND", kind, "사용 중", "In use", null, null, 63, null,
                "SIDEWAYS"), TestOwners.OWNER), ValidationCode.VALIDATION_FAILED);
        // a code of another group is never directed
        Expect.failure(() -> codes.set(new CodeCommand("DEFECT_CATEGORY", "DIR_" + s.code(), "방향", "Dir", null, null, 64,
                null, "GIVEN"), TestOwners.OWNER), ValidationCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("SVC-LNK-02: a defect and the task that works on it")
    void lnk02_defectTask(Scenario s) {
        SaveResult d = s.newDefect("d");
        SaveResult t = s.newTask("t");
        LinkResult result = link(s, "defect_task", d.id(), t.id(), null);
        assertThat(result.entity()).isEqualTo("defect_task");
        assertThat(result.id()).isEqualTo(d.id() + "|" + t.id());
    }

    static Stream<String> conditionKinds() {
        return Stream.of("condition_task", "condition_defect");
    }

    @ParameterizedTest
    @MethodSource("conditionKinds")
    @DisplayName("SVC-LNK-03: a condition is linked to a task or a defect")
    void lnk03_conditionLinks(String kind, Scenario s) {
        SaveResult c = s.newCondition("1", "r", "c");
        SaveResult target = "condition_task".equals(kind) ? s.newTask("t") : s.newDefect("d");
        LinkResult result = link(s, kind, c.id(), target.id(), null);
        assertThat(result.entity()).isEqualTo("condition_link");
        assertThat(result.id()).isEqualTo(c.id() + "|" + target.id());
    }

    @Test
    @DisplayName("SVC-LNK-04: an unknown kind lists the kinds that exist")
    void lnk04_unknownKind(Scenario s) {
        SaveResult d = s.newDefect("d");
        ValidationException e = (ValidationException) Expect.failure(() -> link(s, "related", d.id(), d.id(), null),
                ValidationCode.VALIDATION_FAILED);
        assertThat(e.issues().get(0).allowed()).startsWith("defect_task", "condition_task", "condition_defect",
                "same_site", "caused_by", "blocks", "same_family", "same_fix_unit", "regression_of");
    }

    @Test
    @DisplayName("SVC-LNK-05: an inactive relation kind is refused for a new link")
    void lnk05_inactiveKind(Scenario s) {
        s.bean(CommonCodeService.class).set(new CodeCommand("RELATION_KIND", "old_kind", "old", null, null, null, 50,
                "INACTIVE"), TestOwners.OWNER);
        SaveResult d1 = s.newDefect("one");
        SaveResult d2 = s.newDefect("two");
        Expect.failure(() -> link(s, "old_kind", d1.id(), d2.id(), null), ValidationCode.CODE_INVALID);
    }

    @Test
    @DisplayName("SVC-LNK-06: removing a link records an UNLINK and keeps the removed row")
    void lnk06_remove(Scenario s) {
        SaveResult d = s.newDefect("d");
        SaveResult t = s.newTask("t");
        link(s, "defect_task", d.id(), t.id(), null);
        LinkResult removed = link(s, "defect_task", d.id(), t.id(), true);
        assertThat(removed.removed()).isTrue();
        HistoryEntry row = s.historyRow(removed.historyId());
        assertThat(row.op()).isEqualTo(HistoryOp.UNLINK);
        assertThat(row.beforeData()).isNotNull();
        assertThat(s.count("SELECT COUNT(*) FROM defect_task WHERE defect_id = ?", d.id())).isZero();
        Expect.failure(() -> link(s, "defect_task", d.id(), t.id(), true), TransitionCode.NOTHING_CHANGES);
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
                new Missing("defect", s -> new LinkCommand(null, "defect_task", s.defectId(9), s.taskId(1),
                        Scenario.REASON, null)),
                new Missing("task of a defect link", s -> new LinkCommand(null, "defect_task",
                        s.defectId(1), s.taskId(9), Scenario.REASON, null)),
                new Missing("condition", s -> new LinkCommand(null, "condition_task", s.conditionId("9"),
                        s.taskId(1), Scenario.REASON, null)),
                new Missing("defect of a relation", s -> new LinkCommand(null, "blocks", s.defectId(1),
                        s.defectId(9), Scenario.REASON, null)));
    }

    @ParameterizedTest
    @MethodSource("missing")
    @DisplayName("SVC-LNK-07: a link to an unknown defect, task or condition")
    void lnk07_notFound(Missing missing, Scenario s) {
        s.newDefect("d");
        s.newTask("t");
        Expect.failure(() -> s.bean(LinkService.class).link(Scenario.AUTHOR, missing.command().apply(s)),
                LookupCode.NOT_FOUND);
    }

    @Test
    @DisplayName("SVC-LNK-08: a relation between a defect and itself")
    void lnk08_self(Scenario s) {
        SaveResult d = s.newDefect("d");
        Expect.failure(() -> link(s, "blocks", d.id(), d.id(), null), ValidationCode.VALIDATION_FAILED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"same_site", "caused_by", "blocks", "same_family", "same_fix_unit", "regression_of"})
    @DisplayName("SVC-LNK-09: every seeded relation kind is accepted")
    void lnk09_seededKinds(String kind, Scenario s) {
        SaveResult d1 = s.newDefect("one");
        SaveResult d2 = s.newDefect("two");
        assertThat(link(s, kind, d1.id(), d2.id(), null).id()).endsWith("|" + kind);
    }

    @Test
    @DisplayName("SVC-LNK-10: a code added with code-set is usable at once, without a restart")
    void lnk10_newKind(Scenario s) {
        s.bean(CommonCodeService.class).set(new CodeCommand("RELATION_KIND", "test_relation", "test", null, null, null,
                60, null), TestOwners.OWNER);
        SaveResult d1 = s.newDefect("one");
        SaveResult d2 = s.newDefect("two");
        assertThat(link(s, "test_relation", d1.id(), d2.id(), null).id()).endsWith("|test_relation");
    }

    @Test
    @DisplayName("SVC-LNK-11: ids that contain the separator do not collide")
    void lnk11_pipeInIds(Scenario s) {
        SaveResult d = s.newDefect("d");
        String pipeTask = s.code() + "-T|PIPE";
        s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("task", pipeTask, s.taskFields("pipe")));
        LinkResult linked = link(s, "defect_task", d.id(), pipeTask, null);
        assertThat(linked.id()).isEqualTo(d.id() + "|" + pipeTask);
        assertThat(link(s, "defect_task", d.id(), pipeTask, true).removed()).isTrue();
    }

    @Test
    @DisplayName("SVC-LNK-11: two condition links whose ids split differently are both stored")
    void lnk11_aliasingPairs(Scenario s) {
        String c1 = s.code() + "-C";
        String t1 = "A|" + s.code() + "-T";
        String c2 = s.code() + "-C|A";
        String t2 = s.code() + "-T";
        s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("condition", c1, s.conditionFields("r", "one")));
        s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("condition", c2, s.conditionFields("r", "two")));
        s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("task", t1, s.taskFields("one")));
        s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("task", t2, s.taskFields("two")));
        link(s, "condition_task", c1, t1, null);
        link(s, "condition_task", c2, t2, null);
        assertThat(s.count("SELECT COUNT(*) FROM condition_link WHERE condition_id IN (?, ?)", c1, c2)).isEqualTo(2);
    }
}
