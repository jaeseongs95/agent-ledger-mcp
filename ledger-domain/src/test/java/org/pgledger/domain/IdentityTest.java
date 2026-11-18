package org.pgledger.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.pgledger.domain.error.LedgerException;
import org.pgledger.domain.error.TrustCode;
import org.pgledger.domain.error.ValidationCode;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationIssue;
import org.pgledger.domain.error.ValidationReason;
import org.pgledger.domain.value.ActorId;
import org.pgledger.domain.value.AuthorId;
import org.pgledger.domain.value.CriterionId;
import org.pgledger.domain.value.EntityId;
import org.pgledger.domain.value.ImportedActorId;
import org.pgledger.domain.value.IssueId;
import org.pgledger.domain.value.OperationKey;
import org.pgledger.domain.value.ProjectCode;
import org.pgledger.domain.value.ProjectId;
import org.pgledger.domain.value.Reason;
import org.pgledger.domain.value.SessionKey;
import org.pgledger.domain.value.SpecId;
import org.pgledger.domain.value.TaskId;

/** DOM-001 .. DOM-008 and DOM-036: identities and ids. */
class IdentityTest {

    @Test
    void dom001_validAuthorIdsAreAccepted() {
        for (String ok : List.of("claude:1234abcd", "a:x", "user:owner", "a".repeat(32) + ":x",
                "claude:" + "s".repeat(128), "my-vendor:A.b_c-9")) {
            assertThat(AuthorId.of(ok).value()).isEqualTo(ok);
        }
    }

    @Test
    void dom002_invalidAuthorIdsAreRejectedAndAbsentIsADifferentFailure() {
        for (String bad : List.of("claude", "user:someone", "Claude:x", ":x", "a:", "a".repeat(33) + ":x",
                "claude:" + "s".repeat(129), "claude:a b", "claude:a:b", "claude:한글", "user:owner ", "")) {
            Expect.failsWith(TrustCode.AUTHOR_ID_FORMAT, () -> AuthorId.of(bad));
        }
        Expect.failsWith(TrustCode.HOST_ATTESTATION_REQUIRED, () -> AuthorId.of(null));
    }

    @Test
    void dom003_sessionKeyKeepsTheClient() {
        assertThat(new SessionKey("claude:1")).isEqualTo(new SessionKey("claude:1"));
        assertThat(new SessionKey("claude:1")).isNotEqualTo(new SessionKey("codex:1"));
        assertThat(ActorId.ofStored("someone")).isInstanceOf(ImportedActorId.class);
        assertThat(ActorId.ofStored("someone").sessionKey()).isNotEqualTo(AuthorId.of("claude:1").sessionKey());
        assertThat(ActorId.ofStored("claude:1")).isInstanceOf(AuthorId.class);
        assertThat(ActorId.ofStored("claude:1").sessionKey()).isEqualTo(AuthorId.of("claude:1").sessionKey());
    }

    @Test
    void dom004_projectCodes() {
        for (String ok : List.of("E2E", "A", "A" + "B".repeat(31))) {
            assertThat(ProjectCode.of(ok).value()).isEqualTo(ok);
        }
        for (String bad : List.of("A" + "B".repeat(32), "e2e", "1AB", "A-B", "", " A")) {
            Expect.invalid(() -> ProjectCode.of(bad));
        }
        assertThat(ProjectId.of("e2e").value()).isEqualTo("e2e");
        Expect.invalid(() -> ProjectId.of("a b"));
        Expect.invalid(() -> ProjectId.of("x".repeat(65)));
    }

    @Test
    void dom005_issueIds() {
        assertThat(IssueId.of("E2E-I-0001").number()).isEqualTo(1);
        assertThat(IssueId.of("E2E-I-10000").number()).isEqualTo(10000);
        assertThat(IssueId.of("E2E-I-0001").projectCode()).isEqualTo(ProjectCode.of("E2E"));
        for (String bad : List.of("E2E-I-001", "e2e-I-0001", "E2E-I-", "E2E-T-0001", "")) {
            Expect.invalid(() -> IssueId.of(bad));
        }
        assertThat(IssueId.of(ProjectCode.of("E2E"), 1).value()).isEqualTo("E2E-I-0001");
        assertThat(IssueId.of(ProjectCode.of("E2E"), 9999).value()).isEqualTo("E2E-I-9999");
        assertThat(IssueId.of(ProjectCode.of("E2E"), 10000).value()).isEqualTo("E2E-I-10000");
    }

    @Test
    void dom006_taskAndSpecIdsAreFreeTextWithIssuedPattern() {
        for (String ok : List.of("E2E-T-0001", "E2E-T-1", "E2E-T|PIPE")) {
            assertThat(TaskId.of(ok).value()).isEqualTo(ok);
            assertThat(SpecId.of(ok).value()).isEqualTo(ok);
        }
        Expect.invalid(() -> TaskId.of(" "));
        Expect.invalid(() -> TaskId.of(""));
        Expect.invalid(() -> SpecId.of(" "));
        assertThat(TaskId.issue(ProjectCode.of("E2E"), 7).value()).isEqualTo("E2E-T-0007");
        assertThat(SpecId.issue(ProjectCode.of("E2E"), 7).value()).isEqualTo("E2E-S-0007");
    }

    @Test
    void dom005_issueNumberHasAnUpperBoundOfEighteenDigits() {
        String eighteen = "E2E-I-" + "9".repeat(18);
        String nineteen = "E2E-I-" + "9".repeat(19);
        assertThat(IssueId.of(eighteen).number()).isEqualTo(999_999_999_999_999_999L);
        assertThat(IssueId.parse(eighteen)).isPresent();
        assertThat(IssueId.FORMAT.matcher(eighteen).matches()).isTrue();
        Expect.invalid(() -> IssueId.of(nineteen));
        Expect.invalid(() -> IssueId.of("E2E-I-" + "9".repeat(40)));
        assertThat(IssueId.parse(nineteen)).isEmpty();
        assertThat(IssueId.FORMAT.matcher(nineteen).matches()).isFalse();
        assertThat(IssueId.of(ProjectCode.of("E2E"), 999_999_999_999_999_999L).value()).isEqualTo(eighteen);
        Expect.invalid(() -> IssueId.of(ProjectCode.of("E2E"), 1_000_000_000_000_000_000L));
        Expect.invalid(() -> IssueId.of(ProjectCode.of("E2E"), Long.MAX_VALUE));
        Expect.invalid(() -> TaskId.issue(ProjectCode.of("E2E"), Long.MAX_VALUE));
        Expect.invalid(() -> SpecId.issue(ProjectCode.of("E2E"), Long.MAX_VALUE));
    }

    @Test
    void dom006_taskAndSpecNumberOfAServerIssuedId() {
        ProjectCode e2e = ProjectCode.of("E2E");
        assertThat(TaskId.of("E2E-T-0007").number()).hasValue(7);
        assertThat(TaskId.of("E2E-T-7").number()).hasValue(7);
        assertThat(TaskId.issue(e2e, 10000).number()).hasValue(10000);
        assertThat(TaskId.of("E2E-T-" + "9".repeat(18)).number()).hasValue(999_999_999_999_999_999L);
        assertThat(TaskId.of("E2E-T-" + "9".repeat(19)).number()).isEmpty();
        assertThat(TaskId.of("E2E-T|PIPE").number()).isEmpty();
        assertThat(TaskId.of("E2E-I-0001").number()).isEmpty();
        assertThat(TaskId.of("E2E-T-0007X").number()).isEmpty();
        assertThat(TaskId.of("E2E-T-0007").numberIn(e2e)).hasValue(7);
        assertThat(TaskId.of("OTHER-T-0007").numberIn(e2e)).isEmpty();
        assertThat(TaskId.of("E2E-T-" + "9".repeat(19)).numberIn(e2e)).isEmpty();
        for (String id : new String[] {"E2E-t-0005", "e2e-T-0005", "e2e-t-00005"}) {
            assertThat(TaskId.of(id).number()).as(id).hasValue(5);
            assertThat(TaskId.of(id).numberIn(e2e)).as(id).hasValue(5);
        }
        assertThat(TaskId.of("e2e-t-0005X").numberIn(e2e)).isEmpty();
        assertThat(TaskId.of("other-t-0005").numberIn(e2e)).isEmpty();

        assertThat(SpecId.of("E2E-S-0003").numberIn(e2e)).hasValue(3);
        assertThat(SpecId.of("e2e-s-00003").numberIn(e2e)).hasValue(3);
        assertThat(SpecId.of("E2E-T-0003").numberIn(e2e)).isEmpty();
    }

    @Test
    void dom007_opk014_operationKeyRulesAndDerivedKeys() {
        ValidationIssue required = onlyIssue(Expect.invalid(() -> OperationKey.parse(null)));
        assertThat(required.path()).isEqualTo("operationKey");
        assertThat(required.reason()).isEqualTo(ValidationReason.REQUIRED);
        for (String bad : List.of("", " ", "   ", "\t\n", "k".repeat(129), "k".repeat(1000))) {
            ValidationIssue issue = onlyIssue(Expect.invalid(() -> OperationKey.parse(bad)));
            assertThat(issue.path()).as("path of '%s'", bad).isEqualTo("operationKey");
            assertThat(issue.reason()).isEqualTo(ValidationReason.MUST_BE_CHARS_RANGE);
            assertThat(issue.reasonArgs()).containsExactly("1", "128");
            Expect.invalid(() -> new OperationKey(bad));
        }
        for (String ok : List.of("k", "k".repeat(128), "not-a-uuid", "1-1-1-1-1", "한글 키 01",
                UUID.randomUUID().toString())) {
            assertThat(OperationKey.parse(ok).value()).isEqualTo(ok);
            assertThat(OperationKey.parse(ok)).hasToString(ok);
        }
        assertThat(OperationKey.WIRE_NAME).isEqualTo("operationKey");
        OperationKey batch = OperationKey.parse(UUID.randomUUID().toString());
        assertThat(batch.derive(0)).isEqualTo(batch.derive(0));
        assertThat(batch.derive(0)).isNotEqualTo(batch.derive(1));
        OperationKey other = OperationKey.parse(UUID.randomUUID().toString());
        assertThat(batch.derive(0)).isNotEqualTo(other.derive(0));
        assertThat(OperationKey.parse("k".repeat(128)).derive(3).value()).hasSizeLessThanOrEqualTo(128);
    }

    private static ValidationIssue onlyIssue(LedgerException e) {
        assertThat(e).isInstanceOf(ValidationException.class);
        List<ValidationIssue> issues = ((ValidationException) e).issues();
        assertThat(issues).hasSize(1);
        return issues.get(0);
    }

    @Test
    void dom008_reasonLengthCountsCharactersAfterTrim() {
        assertThat(Reason.of("x".repeat(10)).text()).hasSize(10);
        assertThat(Reason.of("x".repeat(1000)).text()).hasSize(1000);
        assertThat(Reason.of("가".repeat(10)).text()).hasSize(10);
        assertThat(Reason.of("  " + "x".repeat(10) + "  ").text()).isEqualTo("x".repeat(10));
        for (String bad : List.of("x".repeat(9), "x".repeat(1001), "x".repeat(9) + "   ", "", "          ")) {
            Expect.failsWith(ValidationCode.REASON_INVALID, () -> Reason.of(bad));
        }
        Expect.failsWith(ValidationCode.REASON_INVALID, () -> Reason.of(null));
    }

    @Test
    void dom036_reh3Id01_freeTextIdsAreKept() {
        for (String ok : List.of("E2E-I-0001", "E2E-T-1", "E2E-T|PIPE", "OLD-PIPE-C|A", "OLD-C-1", "E2E-t-1", "Abc",
                "AGS:A01", "AGS:A07-a", "USER_BACKLOG:revision58:task:ledger-storage-reliability-design",
                "AGS:criterion:milestone-check-01",
                "a#b/c.d:e_f-g|h", "x", "a".repeat(120), "가".repeat(120))) {
            assertThat(EntityId.of(ok).value()).isEqualTo(ok);
            assertThat(TaskId.of(ok).value()).isEqualTo(ok);
            assertThat(SpecId.of(ok).value()).isEqualTo(ok);
            assertThat(CriterionId.of(ok).value()).isEqualTo(ok);
            assertThat(TaskId.parse(ok)).isPresent();
            assertThat(SpecId.parse(ok)).isPresent();
            assertThat(CriterionId.parse(ok)).isPresent();
        }
        String zeroWidthSpace = "A\u200BB";
        for (String bad : List.of("", " ", "   ", "a".repeat(121), "AGS A01", "A\tB", "A\nB", "A\r\nB", "A\u00A0B",
                zeroWidthSpace, "A\u0000B", "A\u0085B", "AGS:A01 ")) {
            ValidationException refused = (ValidationException) Expect.invalid(() -> EntityId.of(bad));
            assertThat(refused.issues().getFirst().reason().name()).as("reason for %s", bad).isEqualTo("MUST_BE_ID");
            Expect.invalid(() -> TaskId.of(bad));
            Expect.invalid(() -> SpecId.of(bad));
            Expect.invalid(() -> CriterionId.of(bad));
            assertThat(TaskId.parse(bad)).isEmpty();
            assertThat(SpecId.parse(bad)).isEmpty();
            assertThat(CriterionId.parse(bad)).isEmpty();
        }
        assertThat(IssueId.of("AGS-I-0001").value()).isEqualTo("AGS-I-0001");
        ValidationException lower = (ValidationException) Expect.invalid(() -> IssueId.of("ags-i-0001"));
        assertThat(lower.issues().getFirst().reason().name()).isEqualTo("ISSUE_ID_FORMAT");
    }

    @Test
    void reh3Id02_equalityOfIdsIsExact() {
        assertThat(TaskId.of("AGS:A07-a")).isNotEqualTo(TaskId.of("AGS:A07-A")).isEqualTo(TaskId.of("AGS:A07-a"));
        assertThat(SpecId.of("ags:s1")).isNotEqualTo(SpecId.of("AGS:S1"));
        assertThat(CriterionId.of("ags:c1")).isNotEqualTo(CriterionId.of("AGS:C1"));
    }
}
