// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.veridger.application.gate.AuthorAttestationVerifier;
import org.veridger.application.gate.ToolCall;
import org.veridger.application.support.Expect;
import org.veridger.application.support.Fields;
import org.veridger.application.support.LedgerServiceTest;
import org.veridger.application.support.Scenario;
import org.veridger.application.write.BatchOpInput;
import org.veridger.application.write.BatchService;
import org.veridger.application.write.ReviewService;
import org.veridger.application.write.SaveCommand;
import org.veridger.application.write.SaveService;
import org.veridger.application.write.TransitionCommand;
import org.veridger.application.write.TransitionService;
import org.veridger.application.write.WhoamiService;
import org.veridger.application.write.WriteResult.SaveResult;
import org.veridger.domain.error.ConflictCode;
import org.veridger.domain.error.LedgerException;
import org.veridger.domain.error.LookupCode;
import org.veridger.domain.error.TrustCode;
import org.veridger.domain.error.ValidationCode;
import org.veridger.domain.error.ValidationReason;
import org.veridger.domain.rule.Channel;
import org.veridger.domain.rule.HistoryEntity;
import org.veridger.domain.value.OperationKey;

/**
 * Direct host-stamped {@code <vendor>:<session_id>} attestation gate tests ({@link AuthorAttestationVerifier}).
 */
@LedgerServiceTest
class AttestationGateTest {

    private static final ToolCall CALL = new ToolCall.Named("ledger_issue_save");

    private static String newOperationKey() {
        return UUID.randomUUID().toString();
    }

    private static SaveCommand create(Scenario s, String operationKey, String title) {
        return new SaveCommand(operationKey, "issue", null, 0L, Fields.of(s.issueFields(title)), Scenario.REASON);
    }

    private static List<BatchOpInput> oneSave(Scenario s) {
        return List.of(new BatchOpInput.Save("issue", Fields.of(s.issueFields("order")), Scenario.REASON, null, null));
    }

    private static long writtenBy(Scenario s, String author) {
        return s.count("SELECT COUNT(*) FROM request_receipt WHERE author_id = ?", author)
                + s.count("SELECT COUNT(*) FROM history WHERE author_id = ?", author)
                + s.count("SELECT COUNT(*) FROM issue WHERE project_id = ?", s.projectId());
    }

    @Test
    @DisplayName("granted author writes directly with plain <vendor>:<session_id> attestation")
    void plainAuthorWritesDirectly(Scenario s) {
        String author = s.actorWith("issue");
        String operationKey = newOperationKey();
        SaveResult result = s.bean(SaveService.class).save(author, create(s, operationKey, "direct"));
        assertThat(result.author()).isEqualTo(author);
        assertThat(s.history(HistoryEntity.ISSUE, result.id())).hasSize(1);
    }

    @Test
    @DisplayName("missing attestation is HOST_ATTESTATION_REQUIRED and malformed text is AUTHOR_ID_FORMAT")
    void missingAndMalformedAttestationsRefused(Scenario s) {
        AuthorAttestationVerifier verifier = s.bean(AuthorAttestationVerifier.class);
        Expect.failure(() -> verifier.verify(null, CALL, ""), TrustCode.HOST_ATTESTATION_REQUIRED);
        Expect.failure(() -> verifier.verify("   ", CALL, ""), TrustCode.HOST_ATTESTATION_REQUIRED);
        for (String text : List.of("claude", "user:someone", "claude:bad id", "ala3.a.b.c.d.e.f", "pgla2.x")) {
            LedgerException failure = Expect.failure(() -> verifier.verify(text, CALL, ""), TrustCode.AUTHOR_ID_FORMAT);
            assertThat(failure.args()).isEmpty();
            assertThat(Expect.message(failure)).doesNotContain(text);
        }
        assertThat(verifier.verify("user:owner", CALL, "").value()).isEqualTo("user:owner");
        assertThat(verifier.verify("codex:session-1", CALL, "").value()).isEqualTo("codex:session-1");
    }

    @Test
    @DisplayName("ungranted author is refused SESSION_NOT_GRANTED and unknown project is NOT_FOUND")
    void grantAndProjectChecksEnforced(Scenario s) {
        String granted = s.actorWith("issue");
        String ungranted = s.actorWithoutGrants();
        String operationKey = newOperationKey();
        Map<String, String> fields = s.issueFields("t");
        fields.put("project_id", "no-such-project");
        SaveCommand unknownProject = new SaveCommand(operationKey, "issue", null, 0L, Fields.of(fields), Scenario.REASON);

        Expect.failure(() -> s.bean(SaveService.class).save(granted, unknownProject), LookupCode.NOT_FOUND);
        Expect.failure(() -> s.bean(SaveService.class).save(ungranted, create(s, operationKey, "no grant")),
                TrustCode.SESSION_NOT_GRANTED);

        TransitionCommand verify = s.move("issue", s.newIssue("to verify").id(), 1, "VERIFIED", Scenario.EVIDENCE);
        Expect.failure(() -> s.bean(TransitionService.class).transition("garbage", verify),
                TrustCode.VERIFY_NEEDS_REVIEWER_LOGIN);
    }

    @Test
    @DisplayName("operationKey idempotency and conflict still hold with direct author attestation")
    void operationKeyIdempotencyAndConflict(Scenario s) {
        String author = s.actorWith("issue");
        String operationKey = newOperationKey();
        SaveResult first = s.bean(SaveService.class).save(author, create(s, operationKey, "original"));
        assertThat(s.bean(SaveService.class).save(author, create(s, operationKey, "original"))).isEqualTo(first);
        Expect.failure(() -> s.bean(SaveService.class).save(author, create(s, operationKey, "different content")),
                ConflictCode.OPERATION_CONFLICT);
        assertThat(s.count("SELECT COUNT(*) FROM issue WHERE project_id = ?", s.projectId())).isEqualTo(1);
    }

    @Test
    @DisplayName("batch writes derive per-op keys from the batch operationKey and validate attestation before ops and key")
    void batchChecksOrderAndDerivation(Scenario s) {
        String author = s.actorWith("issue");
        for (List<BatchOpInput> ops : List.of(oneSave(s), List.<BatchOpInput>of())) {
            Expect.failure(() -> s.bean(BatchService.class).batch(null, "", ops), TrustCode.HOST_ATTESTATION_REQUIRED);
            Expect.failure(() -> s.bean(BatchService.class).batch("claude", "", ops), TrustCode.AUTHOR_ID_FORMAT);
        }
        LedgerException emptyOps = Expect.failure(() -> s.bean(BatchService.class).batch(author, "", List.of()),
                ValidationCode.VALIDATION_FAILED);
        assertThat(Expect.firstIssue(emptyOps).path()).isEqualTo("ops");
        assertThat(Expect.firstIssue(emptyOps).reason()).isEqualTo(ValidationReason.MUST_HOLD_ITEMS);
        assertThat(writtenBy(s, author)).isZero();

        String batchId = newOperationKey();
        List<BatchOpInput> ops = List.of(
                new BatchOpInput.Save("issue", Fields.of(s.issueFields("one")), Scenario.REASON, null, null),
                new BatchOpInput.Save("issue", Fields.of(s.issueFields("two")), Scenario.REASON, null, null));
        assertThat(s.bean(BatchService.class).batch(author, batchId, ops).results()).hasSize(2);
        OperationKey batchKey = OperationKey.parse(batchId);
        assertThat(s.count("SELECT COUNT(*) FROM request_receipt WHERE operation_key = ? AND operation = 'BATCH'",
                batchId)).isEqualTo(1);
        assertThat(s.count("SELECT COUNT(*) FROM request_receipt WHERE operation_key IN (?, ?)",
                batchKey.derive(0).toString(), batchKey.derive(1).toString())).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "   ", "\t"})
    @DisplayName("blank batch operationKey is refused VALIDATION_FAILED after attestation")
    void blankBatchKeyRefused(String operationKey, Scenario s) {
        assertBatchKeyRefused(s, operationKey);
    }

    @Test
    @DisplayName("129-character batch operationKey is refused VALIDATION_FAILED")
    void longBatchKeyRefused(Scenario s) {
        assertBatchKeyRefused(s, "k".repeat(129));
    }

    private static void assertBatchKeyRefused(Scenario s, String operationKey) {
        String author = s.actorWith("issue");
        LedgerException failure = Expect.failure(() -> s.bean(BatchService.class).batch(author, operationKey, oneSave(s)),
                ValidationCode.VALIDATION_FAILED);
        assertThat(failure.args()).containsExactly("operationKey");
        assertThat(Expect.firstIssue(failure).path()).isEqualTo("operationKey");
        assertThat(Expect.firstIssue(failure).reason()).isEqualTo(ValidationReason.MUST_BE_CHARS_RANGE);
        assertThat(Expect.firstIssue(failure).reasonArgs()).containsExactly("1", "128");
        assertThat(writtenBy(s, author)).isZero();
    }

    @Test
    @DisplayName("whoami and review work directly with plain <vendor>:<session_id> attestation")
    void whoamiAndReview(Scenario s) {
        String author = s.actorWith("issue");
        WhoamiService whoami = s.bean(WhoamiService.class);
        assertThat(whoami.whoami(author, Channel.AUTHOR).grants()).hasSize(1);
        assertThat(whoami.whoami(author, Channel.AUTHOR).author()).isEqualTo(author);
        assertThat(whoami.whoami("claude", Channel.AUTHOR).authorFormatOk()).isFalse();
        Expect.failure(() -> whoami.whoami(null, Channel.AUTHOR), TrustCode.HOST_ATTESTATION_REQUIRED);

        String reviewer = s.actorWith("issue");
        SaveResult issue = s.newIssue("to review");
        TransitionCommand move = new TransitionCommand(newOperationKey(), "issue", issue.id(), 1L, "DISPUTED",
                Scenario.REASON, Scenario.EVIDENCE, null, null);
        assertThat(s.bean(ReviewService.class).review(reviewer, move).author()).isEqualTo(reviewer);
    }
}
