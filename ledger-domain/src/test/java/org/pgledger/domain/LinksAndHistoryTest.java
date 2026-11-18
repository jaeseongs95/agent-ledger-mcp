package org.pgledger.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.pgledger.domain.error.ValidationCode;
import org.pgledger.domain.model.CriterionLink;
import org.pgledger.domain.model.HistoryEntry;
import org.pgledger.domain.model.IssueRelation;
import org.pgledger.domain.model.IssueSpecLink;
import org.pgledger.domain.model.IssueTaskLink;
import org.pgledger.domain.model.SpecTaskLink;
import org.pgledger.domain.rule.ActorAssurance;
import org.pgledger.domain.rule.Channel;
import org.pgledger.domain.rule.CodeDirection;
import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.rule.HistoryOp;
import org.pgledger.domain.rule.LinkKind;
import org.pgledger.domain.value.ActorId;
import org.pgledger.domain.value.AuthorId;
import org.pgledger.domain.value.CodeValue;
import org.pgledger.domain.value.CriterionId;
import org.pgledger.domain.value.ImportedActorId;
import org.pgledger.domain.value.IssueId;
import org.pgledger.domain.value.JsonDocument;
import org.pgledger.domain.value.OperationKey;
import org.pgledger.domain.value.SpecId;
import org.pgledger.domain.value.TaskId;

/** DOM-023 and DOM-024: link canonical forms and history entry invariants. */
class LinksAndHistoryTest {

    private static final IssueId I1 = IssueId.of("E2E-I-0001");
    private static final IssueId I2 = IssueId.of("E2E-I-0002");

    @Test
    void dom023_relationsAreCanonicalAndLinkIdsAreUnambiguous() {
        CodeValue kind = CodeValue.of("same_site");
        IssueRelation ab = IssueRelation.between(I1, I2, kind, CodeDirection.CANONICAL, AuthorId.OWNER);
        IssueRelation ba = IssueRelation.between(I2, I1, kind, CodeDirection.CANONICAL, AuthorId.OWNER);
        assertThat(ab).isEqualTo(ba);
        assertThat(ba.kind()).isEqualTo(LinkKind.RELATION);
        assertThat(ba.historyId()).isEqualTo("E2E-I-0001|E2E-I-0002|same_site");
        Expect.invalid(() -> new IssueRelation(I1, I1, kind, AuthorId.OWNER));

        assertThat(new IssueTaskLink(I1, TaskId.of("E2E-T-0001"), AuthorId.OWNER).historyId())
                .isEqualTo("E2E-I-0001|E2E-T-0001");
        assertThat(new IssueSpecLink(I1, SpecId.of("E2E-S-0001"), AuthorId.OWNER).historyId())
                .isEqualTo("E2E-I-0001|E2E-S-0001");
        assertThat(new SpecTaskLink(SpecId.of("E2E-S-0001"), TaskId.of("E2E-T-0001"), AuthorId.OWNER).historyId())
                .isEqualTo("E2E-S-0001|E2E-T-0001");

        CriterionLink toTask = CriterionLink.toTask(CriterionId.of("C1"), TaskId.of("X"), AuthorId.OWNER);
        CriterionLink toIssue = CriterionLink.toIssue(CriterionId.of("C1"), IssueId.of("E2E-I-0001"), AuthorId.OWNER);
        CriterionLink toSpec = CriterionLink.toSpec(CriterionId.of("C1"), SpecId.of("E2E-I-0001"), AuthorId.OWNER);
        assertThat(toTask.historyId()).isEqualTo("C1|X");
        assertThat(toTask.kind()).isEqualTo(LinkKind.CRITERION_TASK);
        assertThat(toIssue.kind()).isEqualTo(LinkKind.CRITERION_ISSUE);
        assertThat(toSpec.kind()).isEqualTo(LinkKind.CRITERION_SPEC);
        // same string id under different entity types never shares a key
        assertThat(CriterionLink.toTask(CriterionId.of("C1"), TaskId.of("E2E-I-0001"), AuthorId.OWNER).linkKey())
                .isNotEqualTo(toIssue.linkKey())
                .isNotEqualTo(toSpec.linkKey());
        assertThat(toIssue.linkKey()).isNotEqualTo(toSpec.linkKey());
        // separator characters inside ids cannot make two distinct pairs collide
        assertThat(CriterionLink.toTask(CriterionId.of("OLD-PIPE-C"), TaskId.of("A|T"), AuthorId.OWNER).linkKey())
                .isNotEqualTo(CriterionLink.toTask(CriterionId.of("OLD-PIPE-C|A"), TaskId.of("T"), AuthorId.OWNER)
                        .linkKey());
        Expect.invalid(() -> new CriterionLink("k", CriterionId.of("C1"), null, null, null, AuthorId.OWNER));
        Expect.invalid(() -> new CriterionLink("k", CriterionId.of("C1"), TaskId.of("T"), I1, null, AuthorId.OWNER));
        Expect.invalid(() -> new CriterionLink("k", CriterionId.of("C1"), null, I1, SpecId.of("S1"), AuthorId.OWNER));
    }

    @Test
    void ldir02_aDirectedRelationKeepsTheOrderOfTheCallerAndItsHistoryIdFollowsIt() {
        CodeValue kind = CodeValue.of("caused_by");
        IssueRelation ab = IssueRelation.between(I1, I2, kind, CodeDirection.GIVEN, AuthorId.OWNER);
        IssueRelation ba = IssueRelation.between(I2, I1, kind, CodeDirection.GIVEN, AuthorId.OWNER);
        assertThat(ab).as("(a, b) and (b, a) of a directed kind are two relations").isNotEqualTo(ba);
        assertThat(ab.a()).isEqualTo(I1);
        assertThat(ab.b()).isEqualTo(I2);
        assertThat(ba.a()).isEqualTo(I2);
        assertThat(ba.b()).isEqualTo(I1);
        assertThat(ab.historyId()).isEqualTo("E2E-I-0001|E2E-I-0002|caused_by");
        assertThat(ba.historyId()).isEqualTo("E2E-I-0002|E2E-I-0001|caused_by");
        Expect.invalid(() -> IssueRelation.between(I1, I1, kind, CodeDirection.GIVEN, AuthorId.OWNER));
    }

    private static final OperationKey REQUEST = new OperationKey(UUID.randomUUID().toString());
    private static final String REASON = "a reason of enough length";

    private static HistoryEntry entry(HistoryOp op, String to, Long corrects, JsonDocument before, String login,
            ActorId author, ActorAssurance assurance, String sourceRef) {
        return new HistoryEntry(null, DomainFixtures.NOW, author, login, assurance, HistoryEntity.ISSUE,
                "E2E-I-0001", op, to == null ? null : "OPEN", to, null, before, REASON, null, null, corrects, REQUEST,
                sourceRef, null);
    }

    private static HistoryEntry author(HistoryOp op, String to, Long corrects, JsonDocument before) {
        return entry(op, to, corrects, before, Channel.AUTHOR.login(), AuthorId.of("claude:me"),
                ActorAssurance.DECLARED, null);
    }

    @Test
    void dom024_historyEntryInvariants() {
        JsonDocument before = JsonDocument.of("{\"version\":1}");
        author(HistoryOp.TRANSITION, "RESOLVED", null, before);
        Expect.invalid(() -> author(HistoryOp.TRANSITION, null, null, before));
        Expect.invalid(() -> author(HistoryOp.EDIT, "RESOLVED", null, before));
        author(HistoryOp.CORRECT, null, 7L, null);
        Expect.invalid(() -> author(HistoryOp.CORRECT, null, null, null));
        Expect.invalid(() -> author(HistoryOp.EDIT, null, 7L, before));
        for (HistoryOp op : new HistoryOp[] {HistoryOp.EDIT, HistoryOp.UNLINK}) {
            author(op, null, null, before);
        }
        for (HistoryOp op : new HistoryOp[] {HistoryOp.CREATE, HistoryOp.LINK}) {
            Expect.invalid(() -> author(op, null, null, before));
            author(op, null, null, null);
        }
        Expect.invalid(() -> author(HistoryOp.CORRECT, null, 7L, before));
        ActorId imported = new ImportedActorId("someone-imported");
        entry(HistoryOp.IMPORT, null, null, null, "postgres", imported, ActorAssurance.IMPORTED_LOGIN, "import:42");
        Expect.invalid(() -> entry(HistoryOp.IMPORT, null, null, null, "postgres", imported,
                ActorAssurance.IMPORTED_LOGIN, null));
        Expect.invalid(() -> entry(HistoryOp.IMPORT, null, null, before, "postgres", imported,
                ActorAssurance.IMPORTED_LOGIN, "import:42"));
        Expect.invalid(() -> entry(HistoryOp.EDIT, null, null, null, "ledger_author", imported,
                ActorAssurance.DECLARED, null));
        Expect.failsWith(ValidationCode.REASON_INVALID, () -> new HistoryEntry(null, DomainFixtures.NOW,
                AuthorId.of("claude:me"), "ledger_author", ActorAssurance.DECLARED, HistoryEntity.ISSUE,
                "E2E-I-0001", HistoryOp.EDIT, null, null, null, null, "short", null, null, null, REQUEST, null,
                null));
        Expect.invalid(() -> entry(HistoryOp.TRANSITION, "VERIFIED", null, before, Channel.AUTHOR.login(),
                AuthorId.of("claude:me"), ActorAssurance.DECLARED, null));
        entry(HistoryOp.TRANSITION, "VERIFIED", null, before, Channel.REVIEWER.login(), AuthorId.of("claude:me"),
                ActorAssurance.DECLARED, null);
        assertThat(author(HistoryOp.CREATE, null, null, null).changes()).isEqualTo(JsonDocument.EMPTY_OBJECT);
        assertThat(HistoryEntry.class.isRecord()).isTrue();
    }
}
