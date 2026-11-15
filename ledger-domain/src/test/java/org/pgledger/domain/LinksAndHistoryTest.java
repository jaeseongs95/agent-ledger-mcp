package org.pgledger.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.pgledger.domain.error.ValidationCode;
import org.pgledger.domain.model.ConditionLink;
import org.pgledger.domain.model.DefectRelation;
import org.pgledger.domain.model.DefectTaskLink;
import org.pgledger.domain.model.HistoryEntry;
import org.pgledger.domain.rule.ActorAssurance;
import org.pgledger.domain.rule.Channel;
import org.pgledger.domain.rule.CodeDirection;
import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.rule.HistoryOp;
import org.pgledger.domain.value.ActorId;
import org.pgledger.domain.value.AuthorId;
import org.pgledger.domain.value.CodeValue;
import org.pgledger.domain.value.ConditionId;
import org.pgledger.domain.value.DefectId;
import org.pgledger.domain.value.JsonDocument;
import org.pgledger.domain.value.ImportedActorId;
import org.pgledger.domain.value.OperationKey;
import org.pgledger.domain.value.TaskId;

/** DOM-023 and DOM-024: link canonical forms and the history entry invariants. */
class LinksAndHistoryTest {

    private static final DefectId D1 = DefectId.of("E2E-D-0001");
    private static final DefectId D2 = DefectId.of("E2E-D-0002");

    @Test
    void dom023_relationsAreCanonicalAndLinkIdsAreUnambiguous() {
        CodeValue kind = CodeValue.of("same_site");
        DefectRelation ab = DefectRelation.between(D1, D2, kind, CodeDirection.CANONICAL, AuthorId.OWNER);
        DefectRelation ba = DefectRelation.between(D2, D1, kind, CodeDirection.CANONICAL, AuthorId.OWNER);
        assertThat(ab).isEqualTo(ba);
        assertThat(ba.historyId()).isEqualTo("E2E-D-0001|E2E-D-0002|same_site");
        Expect.invalid(() -> new DefectRelation(D1, D1, kind, AuthorId.OWNER));

        assertThat(new DefectTaskLink(D1, TaskId.of("E2E-T-0001"), AuthorId.OWNER).historyId())
                .isEqualTo("E2E-D-0001|E2E-T-0001");
        ConditionLink toTask = ConditionLink.toTask(ConditionId.of("C1"), TaskId.of("X"), AuthorId.OWNER);
        ConditionLink toDefect = ConditionLink.toDefect(ConditionId.of("C1"), DefectId.of("E2E-D-0001"), AuthorId.OWNER);
        assertThat(toTask.historyId()).isEqualTo("C1|X");
        // a task X and a defect X on the same condition never share a key
        assertThat(ConditionLink.toTask(ConditionId.of("C1"), TaskId.of("E2E-D-0001"), AuthorId.OWNER).linkKey())
                .isNotEqualTo(toDefect.linkKey());
        // the pipe in an id cannot make two different pairs collide
        assertThat(ConditionLink.toTask(ConditionId.of("OLD-PIPE-C"), TaskId.of("A|T"), AuthorId.OWNER).linkKey())
                .isNotEqualTo(ConditionLink.toTask(ConditionId.of("OLD-PIPE-C|A"), TaskId.of("T"), AuthorId.OWNER)
                        .linkKey());
        Expect.invalid(() -> new ConditionLink(ConditionId.of("C1"), null, null, AuthorId.OWNER));
        Expect.invalid(() -> new ConditionLink(ConditionId.of("C1"), TaskId.of("T"), D1, AuthorId.OWNER));
    }

    @Test
    void ldir02_aDirectedRelationKeepsTheOrderOfTheCallerAndItsHistoryIdFollowsIt() {
        CodeValue kind = CodeValue.of("caused_by");
        DefectRelation ab = DefectRelation.between(D1, D2, kind, CodeDirection.GIVEN, AuthorId.OWNER);
        DefectRelation ba = DefectRelation.between(D2, D1, kind, CodeDirection.GIVEN, AuthorId.OWNER);
        assertThat(ab).as("(a, b) and (b, a) of a directed kind are two relations").isNotEqualTo(ba);
        assertThat(ab.a()).isEqualTo(D1);
        assertThat(ab.b()).isEqualTo(D2);
        assertThat(ba.a()).isEqualTo(D2);
        assertThat(ba.b()).isEqualTo(D1);
        assertThat(ab.historyId()).isEqualTo("E2E-D-0001|E2E-D-0002|caused_by");
        assertThat(ba.historyId()).isEqualTo("E2E-D-0002|E2E-D-0001|caused_by");
        Expect.invalid(() -> DefectRelation.between(D1, D1, kind, CodeDirection.GIVEN, AuthorId.OWNER));
    }

    private static final OperationKey REQUEST = new OperationKey(UUID.randomUUID().toString());
    private static final String REASON = "a reason of enough length";

    private static HistoryEntry entry(HistoryOp op, String to, Long corrects, JsonDocument before, String login,
            ActorId author, ActorAssurance assurance, String sourceRef) {
        return new HistoryEntry(null, DomainFixtures.NOW, author, login, assurance, HistoryEntity.DEFECT,
                "E2E-D-0001", op, to == null ? null : "OPEN", to, null, before, REASON, null, null, corrects, REQUEST,
                sourceRef);
    }

    private static HistoryEntry author(HistoryOp op, String to, Long corrects, JsonDocument before) {
        return entry(op, to, corrects, before, Channel.AUTHOR.login(), AuthorId.of("claude:me"),
                ActorAssurance.DECLARED, null);
    }

    @Test
    void dom024_historyEntryInvariants() {
        JsonDocument before = JsonDocument.of("{\"version\":1}");
        // TRANSITION iff a to-state is present
        author(HistoryOp.TRANSITION, "SOURCE_FIXED", null, before);
        Expect.invalid(() -> author(HistoryOp.TRANSITION, null, null, before));
        Expect.invalid(() -> author(HistoryOp.EDIT, "SOURCE_FIXED", null, before));
        // CORRECT iff corrects_id is present
        author(HistoryOp.CORRECT, null, 7L, null);
        Expect.invalid(() -> author(HistoryOp.CORRECT, null, null, null));
        Expect.invalid(() -> author(HistoryOp.EDIT, null, 7L, before));
        // before_data only for EDIT, TRANSITION, UNLINK
        for (HistoryOp op : new HistoryOp[] {HistoryOp.EDIT, HistoryOp.UNLINK}) {
            author(op, null, null, before);
        }
        for (HistoryOp op : new HistoryOp[] {HistoryOp.CREATE, HistoryOp.LINK}) {
            Expect.invalid(() -> author(op, null, null, before));
            author(op, null, null, null);
        }
        Expect.invalid(() -> author(HistoryOp.CORRECT, null, 7L, before));
        // imported operations need a source reference; imported rows carry no before_data
        ActorId imported = new ImportedActorId("someone-imported");
        entry(HistoryOp.IMPORT, null, null, null, "postgres", imported, ActorAssurance.IMPORTED_LOGIN, "import:42");
        Expect.invalid(() -> entry(HistoryOp.IMPORT, null, null, null, "postgres", imported,
                ActorAssurance.IMPORTED_LOGIN, null));
        Expect.invalid(() -> entry(HistoryOp.IMPORT, null, null, before, "postgres", imported,
                ActorAssurance.IMPORTED_LOGIN, "import:42"));
        // a native entry needs a declared author and a proper reason
        Expect.invalid(() -> entry(HistoryOp.EDIT, null, null, null, "ledger_author", imported,
                ActorAssurance.DECLARED, null));
        Expect.failsWith(ValidationCode.REASON_INVALID, () -> new HistoryEntry(null, DomainFixtures.NOW,
                AuthorId.of("claude:me"), "ledger_author", ActorAssurance.DECLARED, HistoryEntity.DEFECT,
                "E2E-D-0001", HistoryOp.EDIT, null, null, null, null, "short", null, null, null, REQUEST, null));
        // a verification needs the reviewer channel
        Expect.invalid(() -> entry(HistoryOp.TRANSITION, "VERIFIED", null, before, Channel.AUTHOR.login(),
                AuthorId.of("claude:me"), ActorAssurance.DECLARED, null));
        entry(HistoryOp.TRANSITION, "VERIFIED", null, before, Channel.REVIEWER.login(), AuthorId.of("claude:me"),
                ActorAssurance.DECLARED, null);
        // the default changes document and immutability
        assertThat(author(HistoryOp.CREATE, null, null, null).changes()).isEqualTo(JsonDocument.EMPTY_OBJECT);
        assertThat(HistoryEntry.class.isRecord()).isTrue();
    }
}
