// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.pgledger.domain.error.TrustCode;
import org.pgledger.domain.error.ValidationCode;
import org.pgledger.domain.model.CommonCode;
import org.pgledger.domain.model.CriterionFields;
import org.pgledger.domain.model.Deliverable;
import org.pgledger.domain.model.Issue;
import org.pgledger.domain.model.IssueFields;
import org.pgledger.domain.model.Spec;
import org.pgledger.domain.model.SpecFields;
import org.pgledger.domain.model.TaskFields;
import org.pgledger.domain.rule.CheckResult;
import org.pgledger.domain.rule.CodeDirection;
import org.pgledger.domain.rule.CodeGroup;
import org.pgledger.domain.rule.CodeState;
import org.pgledger.domain.rule.DeliverableTarget;
import org.pgledger.domain.value.AuthorId;
import org.pgledger.domain.value.CanonicalPayload;
import org.pgledger.domain.value.CanonicalPayload.Fields;
import org.pgledger.domain.value.CodeValue;
import org.pgledger.domain.value.Evidence;
import org.pgledger.domain.value.IssueId;
import org.pgledger.domain.value.MaxRows;
import org.pgledger.domain.value.PageCursor;
import org.pgledger.domain.value.Patch;
import org.pgledger.domain.value.Sha256;
import org.pgledger.domain.value.SpecId;
import org.pgledger.domain.value.TextRule;
import org.pgledger.domain.value.UtcInstant;

/** DOM-028 .. DOM-034: digests, limits, field sets, cursors, row limits, text normalisation and UTC time. */
class ValuesTest {

    @Test
    void dom028_payloadDigestIgnoresOrderAndNullsAndSeesEveryValue() {
        Fields a = Fields.empty().text("id", "E2E-I-0001").text("reason", "because of the reason")
                .with("fields", Fields.empty().text("title", "t").text("severity", "P1"));
        Fields b = Fields.empty()
                .with("fields", Fields.empty().text("severity", "P1").text("title", "t"))
                .text("reason", "because of the reason").text("id", "E2E-I-0001");
        assertThat(CanonicalPayload.digest("save", a)).isEqualTo(CanonicalPayload.digest("save", a));
        assertThat(CanonicalPayload.digest("save", a)).isEqualTo(CanonicalPayload.digest("save", b));
        assertThat(CanonicalPayload.digest("save", a.text("branch", null))).isEqualTo(CanonicalPayload.digest("save", a));
        assertThat(CanonicalPayload.digest("save", a.text("reason", "another reason entirely")))
                .isNotEqualTo(CanonicalPayload.digest("save", a));
        assertThat(CanonicalPayload.digest("transition", a)).isNotEqualTo(CanonicalPayload.digest("save", a));
        assertThat(CanonicalPayload.digest("save", a.with("fields", Fields.empty().text("title", "t2"))))
                .isNotEqualTo(CanonicalPayload.digest("save", a));
        assertThat(CanonicalPayload.digest("x", Fields.empty().text("ab", "c")))
                .isNotEqualTo(CanonicalPayload.digest("x", Fields.empty().text("a", "bc")));
        assertThat(CanonicalPayload.digest("save", Fields.empty().text("id", "E2E-D-0001").text("v", "1")).hex())
                .isEqualTo(GOLDEN);
    }

    /** sha256 of {@code t4:save{k2:idt10:E2E-D-0001k1:vt1:1}}, computed independently with sha256sum. */
    private static final String GOLDEN = "700bad380a8773163a485424a6869901846cf6df8a7bd73b9bd477b2dad8f8df";

    @Test
    void dom029_limitsAtTheBoundaries() {
        for (TextRule rule : new TextRule[] {TextRule.TITLE, TextRule.SYMPTOM, TextRule.ROOT_CAUSE, TextRule.SUMMARY,
                TextRule.ACCEPTANCE_CRITERIA, TextRule.PURPOSE, TextRule.SUCCESS_CONDITION, TextRule.ORIGIN_QUOTE,
                TextRule.CRITERION_TEXT, TextRule.OBSERVATION, TextRule.EVIDENCE, TextRule.SOURCE_QUOTE,
                TextRule.GRANT_NOTE}) {
            assertThat(rule.optional("x".repeat(rule.max()))).hasSize(rule.max());
            assertThat(rule.optional("가".repeat(rule.max()))).hasSize(rule.max());
            Expect.invalid(() -> rule.optional("x".repeat(rule.max() + 1)));
        }
        assertThat(TextRule.TITLE.max()).isEqualTo(300);
        assertThat(TextRule.SYMPTOM.max()).isEqualTo(4000);
        assertThat(TextRule.SUMMARY.max()).isEqualTo(4000);
        assertThat(TextRule.ACCEPTANCE_CRITERIA.max()).isEqualTo(4000);
        assertThat(TextRule.ORIGIN_QUOTE.max()).isEqualTo(2000);
        assertThat(TextRule.EVIDENCE.max()).isEqualTo(2000);
        assertThat(TextRule.GRANT_NOTE.max()).isEqualTo(500);
        Expect.invalid(() -> TextRule.TITLE.required(""));
        assertThat(TextRule.CHECK_REF.optional("   ")).isNull();
        assertThat(TextRule.CHECK_REF.optional(" run-1 ")).isEqualTo("run-1");
        assertThat(Sha256.of("b".repeat(64)).hex()).hasSize(64);
        for (String bad : List.of("b".repeat(63), "b".repeat(65), "B".repeat(64))) {
            Expect.invalid(() -> Sha256.of(bad));
        }
        Expect.invalid(() -> Evidence.of("https://x", "b".repeat(63)));
        // check_result requires check_ref
        Expect.invalid(() -> Deliverable.record(1L, DeliverableTarget.ISSUE, "E2E-I-0001", CodeValue.of("CODE_PATCH"),
                "ref-1", null, "summary", "  ", CheckResult.PASSED, null, null, AuthorId.OWNER, DomainFixtures.NOW));
    }

    private static Patch<String> set(String v) {
        return Patch.set(v);
    }

    private static Patch<String> none() {
        return Patch.absent();
    }

    @Test
    void dom030_createNeedsRequiredFieldsAndEditNeedsAtLeastOne() {
        IssueFields complete = IssueFields.of(set("e2e"), set("title"), set("PROCESS"), set("P2"), set("symptom"),
                set("cause"), none());
        Issue created = Issue.open(IssueId.of("E2E-I-0001"), complete, DomainFixtures.NOW);
        assertThat(created.version()).isZero();
        assertThat(created.caseSeq()).isZero();
        assertThat(created.status().name()).isEqualTo("OPEN");
        for (IssueFields missing : List.of(
                IssueFields.of(none(), set("t"), set("A"), set("P1"), set("s"), set("c"), none()),
                IssueFields.of(set("e2e"), none(), set("A"), set("P1"), set("s"), set("c"), none()),
                IssueFields.of(set("e2e"), set("t"), set("A"), set("P1"), none(), set("c"), none()),
                IssueFields.of(set("e2e"), set("t"), set("A"), set("P1"), set("s"), none(), none()))) {
            Expect.invalid(missing::requireCreatable);
        }
        IssueFields empty = IssueFields.of(none(), none(), none(), none(), none(), none(), none());
        Expect.invalid(empty::requireEditable);
        IssueFields titleOnly = IssueFields.of(none(), set("new"), none(), none(), none(), none(), none());
        assertThat(titleOnly.requireEditable()).isSameAs(titleOnly);
        Issue edited = created.edited(titleOnly, DomainFixtures.NOW.plusSeconds(1));
        assertThat(edited.title()).isEqualTo("new");
        assertThat(edited.symptom()).isEqualTo("symptom");
        assertThat(edited.updatedAt()).isEqualTo(DomainFixtures.NOW.plusSeconds(1));
        IssueFields clearOwner = IssueFields.of(none(), none(), none(), none(), none(), none(), set(""));
        assertThat(created.edited(clearOwner, DomainFixtures.NOW).ownerActorId()).isNull();
        Expect.invalid(() -> IssueFields.of(none(), set("  "), none(), none(), none(), none(), none()));
        Expect.failsWith(TrustCode.AUTHOR_ID_FORMAT,
                () -> IssueFields.of(none(), none(), none(), none(), none(), none(), set("nobody")));

        SpecFields specFields = SpecFields.of(set("e2e"), none(), set("Script Draft"), set("summary"),
                set("reviewed"), set("P1"), none(), none(), none());
        Spec createdSpec = Spec.create(SpecId.of("E2E-S-0001"), specFields, DomainFixtures.NOW);
        assertThat(createdSpec.status().name()).isEqualTo("PLANNED");
        Expect.invalid(() -> SpecFields.of(none(), none(), set("x"), none(), none(), none(), none(), none(), none())
                .requireCreatable());
        Expect.invalid(() -> SpecFields.of(none(), none(), none(), none(), none(), none(), none(), none(), none())
                .requireEditable());

        TaskFields task = TaskFields.of(set("e2e"), set("t"), set("p"), set("s"), none(), set(""), none());
        assertThat(task.requireCreatable().originRef().orElse("unset")).isNull();
        Expect.invalid(() -> TaskFields.of(set("e2e"), set("t"), none(), set("s"), none(), none(), none())
                .requireCreatable());
        CriterionFields criterion = CriterionFields.of(set("e2e"), set("1.0"), none(), set("C1"), set("text"));
        assertThat(criterion.requireCreatable()).isSameAs(criterion);
        Expect.invalid(() -> CriterionFields.of(none(), none(), none(), none(), none()).requireEditable());

        assertThat(IssueFields.class.getRecordComponents()).extracting("name").containsExactly("projectId",
                "title", "category", "severity", "symptom", "rootCause", "ownerActorId");
        assertThat(SpecFields.class.getRecordComponents()).extracting("name").containsExactly("projectId", "parentId",
                "title", "summary", "acceptanceCriteria", "priority", "ownerActorId", "originRef", "originQuote");
        assertThat(TaskFields.class.getRecordComponents()).extracting("name").containsExactly("projectId", "title",
                "purpose", "successCondition", "ownerActorId", "originRef", "originQuote");
        assertThat(CriterionFields.class.getRecordComponents()).extracting("name").containsExactly("projectId",
                "milestone", "goalLabel", "code", "criterionText");
    }

    @Test
    void dom031_cursorsRoundTripAndDetectDamage() {
        PageCursor cursor = new PageCursor(PageCursor.Kind.ISSUES, "2026-11-05T10:00:00Z", "E2E-I-0042");
        String token = cursor.encode();
        assertThat(PageCursor.decode(token, PageCursor.Kind.ISSUES)).isEqualTo(cursor);
        assertThat(token).doesNotContain("E2E-I").doesNotContain("ISSUES");
        PageCursor noKey = new PageCursor(PageCursor.Kind.HISTORY, null, "17");
        assertThat(PageCursor.decode(noKey.encode(), PageCursor.Kind.HISTORY)).isEqualTo(noKey);
        Expect.invalid(() -> PageCursor.decode(token, PageCursor.Kind.TASKS));
        Expect.invalid(() -> PageCursor.decode("%%%not-base64%%%", PageCursor.Kind.ISSUES));
        Expect.invalid(() -> PageCursor.decode(token.substring(0, token.length() - 3), PageCursor.Kind.ISSUES));
        Expect.invalid(() -> PageCursor.decode(token.substring(0, 5) + "x" + token.substring(6),
                PageCursor.Kind.ISSUES));
        Expect.invalid(() -> PageCursor.decode("", PageCursor.Kind.ISSUES));
        Expect.invalid(() -> PageCursor.decode(null, PageCursor.Kind.ISSUES));
        Expect.invalid(() -> new PageCursor(PageCursor.Kind.ISSUES, "a\nb", "x"));
    }

    @Test
    void dom032_maxRows() {
        assertThat(MaxRows.of(1).value()).isEqualTo(1);
        assertThat(MaxRows.of(1000).value()).isEqualTo(1000);
        assertThat(MaxRows.of(null)).isEqualTo(MaxRows.DEFAULT);
        assertThat(MaxRows.DEFAULT.value()).isEqualTo(50);
        for (Number bad : new Number[] {0, -1, 1001, 50.5}) {
            Expect.invalid(() -> MaxRows.of(bad));
        }
        assertThat(MaxRows.of(50.0).value()).isEqualTo(50);
    }

    @Test
    void dom033_textNormalisation() {
        for (String blank : new String[] {"", "   ", null}) {
            assertThat(TextRule.SOURCE_QUOTE.optional(blank)).isNull();
            Expect.invalid(() -> TextRule.TITLE.required(blank));
        }
        assertThat(TextRule.TITLE.optional(" x ")).isEqualTo(" x ");
        assertThat(TextRule.EVIDENCE.optional(" x ")).isEqualTo("x");
        assertThat(Evidence.of("  ", null).provided()).isFalse();
        assertThat(Evidence.of("", null)).isEqualTo(Evidence.NONE);
        CommonCode code = new CommonCode(CodeGroup.ISSUE_CATEGORY, CodeValue.of("DESIGN"), "설계", null, "설명", " ",
                25, CodeState.ACTIVE, null, AuthorId.OWNER);
        assertThat(code.direction()).as("no direction is the canonical order").isEqualTo(CodeDirection.CANONICAL);
        assertThat(code.nameEn()).isEqualTo("설계");
        assertThat(code.descriptionEn()).isEqualTo("설명");
        assertThat(ValidationCode.VALIDATION_FAILED.token()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void dom034_timeInputNeedsAZoneAndOutputEndsInZ() {
        UtcInstant z = UtcInstant.parse("t", "2026-11-05T10:00:00Z");
        UtcInstant offset = UtcInstant.parse("t", "2026-11-05T19:00:00+09:00");
        assertThat(offset).isEqualTo(z);
        assertThat(z.toString()).isEqualTo("2026-11-05T10:00:00Z").endsWith("Z");
        assertThat(offset.toString()).endsWith("Z");
        assertThat(UtcInstant.parse("t", "2026-11-05T10:00:00.123456Z").toString()).endsWith("Z");
        for (String bad : new String[] {"2026-11-05T10:00:00", "2026-11-05", "", "yesterday", null}) {
            Expect.invalid(() -> UtcInstant.parse("observed_at", bad));
        }
    }

    @Test
    void hlp001_sha256HasFixedVectorsAndIsTheAttestationHelper() {
        assertThat(Sha256.ofText("abc").hex()).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(Sha256.ofBytes(new byte[0]).hex())
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        byte[] bytes = {0, 1, 2, (byte) 0xff};
        assertThat(Sha256.ofBytes(bytes).hex()).isEqualTo(org.pgledger.attestation.Sha256Hex.of(bytes));
    }
}
