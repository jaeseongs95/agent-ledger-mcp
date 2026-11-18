package org.pgledger.application;

import static org.assertj.core.api.Assertions.assertThat;
import org.pgledger.testing.TestOwners;

import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pgledger.application.admin.SessionKeyService;
import org.pgledger.application.gate.AttestationProperties;
import org.pgledger.application.gate.SignedAttestationVerifier;
import org.pgledger.application.gate.ToolCall;
import org.pgledger.application.support.Expect;
import org.pgledger.application.support.Fields;
import org.pgledger.application.support.LedgerServiceTest;
import org.pgledger.application.support.Scenario;
import org.pgledger.application.write.BatchOpInput;
import org.pgledger.application.write.BatchService;
import org.pgledger.application.write.ReviewService;
import org.pgledger.application.write.SaveCommand;
import org.pgledger.application.write.SaveService;
import org.pgledger.application.write.TransitionCommand;
import org.pgledger.application.write.WhoamiService;
import org.pgledger.application.write.WriteResult.SaveResult;
import org.pgledger.apptest.TestKeyRing;
import org.pgledger.domain.context.ToolScope;
import org.pgledger.domain.error.ConflictCode;
import org.pgledger.domain.error.LedgerException;
import org.pgledger.domain.error.LookupCode;
import org.pgledger.domain.error.TrustCode;
import org.pgledger.domain.error.ValidationCode;
import org.pgledger.domain.error.ValidationReason;
import org.pgledger.domain.rule.Channel;
import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.value.OperationKey;

/**
 * SVC-GATE-20 .. SVC-GATE-35: the signed attestation of the author at the write gate. The other service tests call the
 * services with a plain author and let {@code SigningAttestationVerifier} sign it; these tests hand the services the
 * signed text themselves, so they decide what is signed (which author, tool, operation key, time, key).
 */
@LedgerServiceTest
class AttestationGateTest {

    private static final String SAVE = "ledger_save";

    /** The arguments digest of the calls of these tests unless a test binds another (see {@link TestKeyRing}). */
    private static final String DIGEST = TestKeyRing.DIRECT_DIGEST;

    /** The call of the tool {@link #SAVE} with the digest {@link #DIGEST}, as the verifier is asked directly. */
    private static final ToolCall CALL = new ToolCall.Digested(SAVE, DIGEST);

    /** The digest of other arguments: what a captured token meets when the call carries other content. */
    private static final String OTHER_DIGEST = org.pgledger.attestation.ArgumentsDigest.of(Map.of("other", "content"));

    /**
     * Runs the call as the tool binding of the MCP server does: with the name of the tool called and the digest of the
     * arguments that the binding computed from the raw call.
     */
    private static <T> T asTool(String tool, Supplier<T> call) {
        return asTool(tool, DIGEST, call);
    }

    private static <T> T asTool(String tool, String argumentsDigest, Supplier<T> call) {
        return ScopedValue.where(ToolScope.CURRENT, tool).where(ToolScope.ARGUMENTS_DIGEST, argumentsDigest)
                .call(call::get);
    }

    private static String newOperationKey() {
        return UUID.randomUUID().toString();
    }

    private static SaveCommand create(Scenario s, String operationKey, String title) {
        return new SaveCommand(operationKey, "defect", null, 0L, Fields.of(s.defectFields(title)), Scenario.REASON);
    }

    private static String now(Scenario s, String author, String tool, String operationKey) {
        return s.ctx().keys().token(author, tool, operationKey, s.ctx().clock().instant());
    }

    private static SaveResult save(Scenario s, String token, SaveCommand command) {
        return asTool(SAVE, () -> s.bean(SaveService.class).save(token, command));
    }

    private static LedgerException refused(Scenario s, String token, SaveCommand command, TrustCode code) {
        LedgerException failure = Expect.failure(() -> save(s, token, command), code);
        // nothing of the attestation may be in what the failure says
        assertThat(failure.getMessage()).doesNotContain(token);
        assertThat(failure.args()).noneMatch(arg -> arg.contains(token));
        return failure;
    }

    @Test
    @DisplayName("SVC-GATE-20: a token signed for the tool and the operation key of the call, inside the window, writes as its author")
    void gate20_signedCallWrites(Scenario s) {
        String author = s.actorWith("defect");
        String operationKey = newOperationKey();
        SaveResult result = save(s, now(s, author, SAVE, operationKey), create(s, operationKey, "signed"));
        assertThat(result.author()).isEqualTo(author);
        assertThat(s.history(HistoryEntity.DEFECT, result.id())).hasSize(1);
    }

    @Test
    @DisplayName("SVC-GATE-21: the old format, a bare <vendor>:<session-id>, is ATTESTATION_UNSIGNED; text that is no author at all is AUTHOR_ID_FORMAT")
    void gate21_unsignedOldFormat(Scenario s) {
        SignedAttestationVerifier verifier = s.bean(SignedAttestationVerifier.class);
        String author = s.actorWith("defect");
        assertThat(Expect.failure(() -> verifier.verify(author, CALL, ""), TrustCode.ATTESTATION_UNSIGNED).args())
                .isEmpty();
        assertThat(Expect.failure(() -> verifier.verify("user:owner", CALL, ""), TrustCode.ATTESTATION_UNSIGNED)
                .args()).isEmpty();
        for (String text : List.of("", "claude", "user:someone", "claude:bad id", "PGLA2.x")) {
            Expect.failure(() -> verifier.verify(text, CALL, ""), TrustCode.AUTHOR_ID_FORMAT);
        }
        Expect.failure(() -> verifier.verify(null, CALL, ""), TrustCode.HOST_ATTESTATION_REQUIRED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"pgla2.", "pgla2.a.b.c.d.e", "pgla2.a.b.c.d.e.f.g",
            "pgla2.!.b.c.44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a.1.AAAA",
            "pgla2.YQ.Yg.Yw.44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a.01.AAAA",
            "pgla2.YQ.Yg.Yw.44136FA355B3678A1146AD16F7E8649E94FB4FC21FE77E8310C060F61CAAFF8A.1.AAAA"})
    @DisplayName("SVC-GATE-22: text with the prefix of a token that is not a canonical token is ATTESTATION_MALFORMED")
    void gate22_malformedToken(String text, Scenario s) {
        SignedAttestationVerifier verifier = s.bean(SignedAttestationVerifier.class);
        Expect.failure(() -> verifier.verify(text, CALL, ""), TrustCode.ATTESTATION_MALFORMED);
    }

    @Test
    @DisplayName("PCS-041: a text with the prefix pgla1 is neither a token of the format nor an author: AUTHOR_ID_FORMAT like any other such text (no release had the version 1 token)")
    void pcs041_versionOneTextIsMalformed(Scenario s) {
        SignedAttestationVerifier verifier = s.bean(SignedAttestationVerifier.class);
        String signature = "A".repeat(86);
        for (String text : List.of("pgla1.", "pgla1.a.b.c.1.AAAA",
                String.join(".", "pgla1", "YQ", "bGVkZ2VyX3NhdmU", "", "1700000000", signature))) {
            LedgerException failure = Expect.failure(() -> verifier.verify(text, CALL, ""), TrustCode.AUTHOR_ID_FORMAT);
            assertThat(failure.args()).isEmpty();
            assertThat(Expect.message(failure)).doesNotContain(text);
        }
    }

    @Test
    @DisplayName("SVC-GATE-23: a forged signature (any other key pair signing the claims of the author) is ATTESTATION_SIGNATURE_INVALID and writes nothing")
    void gate23_forgedSignature(Scenario s) {
        String author = s.actorWith("defect");
        String operationKey = newOperationKey();
        String forged = TestKeyRing.token(TestKeyRing.newPair(), author, SAVE, operationKey, s.ctx().clock().instant());
        LedgerException failure = refused(s, forged, create(s, operationKey, "forged"),
                TrustCode.ATTESTATION_SIGNATURE_INVALID);
        // the author is not in the failure: a caller that cannot sign learns nothing about the author (F5)
        assertThat(failure.args()).isEmpty();
        assertThat(s.count("SELECT COUNT(*) FROM request_receipt WHERE operation_key = ?", operationKey)).isZero();
    }

    @Test
    @DisplayName("SVC-GATE-24: a signature made with the key of another registered author is ATTESTATION_SIGNATURE_INVALID")
    void gate24_signatureOfAnotherAuthor(Scenario s) {
        String victim = s.actorWith("defect");
        String attacker = s.actorWith("defect");
        String operationKey = newOperationKey();
        KeyPair attackerKey = s.ctx().keys().pairOf(attacker);
        String token = TestKeyRing.token(attackerKey, victim, SAVE, operationKey, s.ctx().clock().instant());
        refused(s, token, create(s, operationKey, "impersonation"), TrustCode.ATTESTATION_SIGNATURE_INVALID);
        // the attacker's own token still works for the attacker
        String own = newOperationKey();
        assertThat(save(s, now(s, attacker, SAVE, own), create(s, own, "own")).author()).isEqualTo(attacker);
    }

    @Test
    @DisplayName("SVC-GATE-25: a token signed for another tool, or used outside any tool call, is ATTESTATION_BINDING_MISMATCH (tool)")
    void gate25_toolMismatch(Scenario s) {
        String author = s.actorWith("defect");
        String operationKey = newOperationKey();
        String forLink = now(s, author, "ledger_link", operationKey);
        LedgerException failure = refused(s, forLink, create(s, operationKey, "wrong tool"),
                TrustCode.ATTESTATION_BINDING_MISMATCH);
        assertThat(failure.args()).containsExactly("tool");
        String forSave = now(s, author, SAVE, operationKey);
        Expect.failure(() -> s.bean(SaveService.class).save(forSave, create(s, operationKey, "no tool call")),
                TrustCode.ATTESTATION_BINDING_MISMATCH);
        assertThat(s.count("SELECT COUNT(*) FROM request_receipt WHERE operation_key = ?", operationKey)).isZero();
    }

    @Test
    @DisplayName("SVC-GATE-26: a token signed for another operation key, with none, or used with none, is ATTESTATION_BINDING_MISMATCH (operationKey: args and details.field), and writes nothing; a call without a key, whose token is for the empty key, passes the gate and is then refused for the missing operationKey (changed: it used to write with a fresh key)")
    void gate26_operationKeyMismatch(Scenario s) {
        String author = s.actorWith("defect");
        String one = newOperationKey();
        String other = newOperationKey();
        LedgerException failure = refused(s, now(s, author, SAVE, one), create(s, other, "other id"),
                TrustCode.ATTESTATION_BINDING_MISMATCH);
        assertThat(failure.args()).containsExactly("operationKey");
        assertThat(failure.details()).containsEntry("field", List.of("operationKey"));
        refused(s, now(s, author, SAVE, ""), create(s, one, "token without key"), TrustCode.ATTESTATION_BINDING_MISMATCH);
        refused(s, now(s, author, SAVE, one), create(s, null, "call without key"), TrustCode.ATTESTATION_BINDING_MISMATCH);
        refused(s, now(s, author, SAVE, one.toUpperCase()), create(s, one, "other spelling"),
                TrustCode.ATTESTATION_BINDING_MISMATCH);
        assertThat(s.count("SELECT COUNT(*) FROM request_receipt WHERE operation_key IN (?, ?)", one, other)).isZero();
        // a call without a key has the empty key in its claim: the token for the empty key is the token of that call, so the
        // gate lets it pass and the missing operationKey is refused after it (the server never makes a key up)
        LedgerException missing = Expect.failure(() -> save(s, now(s, author, SAVE, ""), create(s, null, "empty key")),
                ValidationCode.VALIDATION_FAILED);
        assertThat(Expect.firstIssue(missing).path()).isEqualTo("operationKey");
        assertThat(Expect.firstIssue(missing).reason()).isEqualTo(ValidationReason.REQUIRED);
        assertThat(s.count("SELECT COUNT(*) FROM defect WHERE project_id = ?", s.projectId())).isZero();
    }

    @Test
    @DisplayName("OPK-010 (service part): a token signed for another operation key is ATTESTATION_BINDING_MISMATCH with details.field operationKey and nothing is written (no receipt, no history, no defect); the same token with its own key works")
    void opk010_tokenForAnotherOperationKey(Scenario s) {
        String author = s.actorWith("defect");
        String signedFor = newOperationKey();
        String sent = newOperationKey();
        String token = now(s, author, SAVE, signedFor);

        LedgerException failure = refused(s, token, create(s, sent, "other key"), TrustCode.ATTESTATION_BINDING_MISMATCH);
        assertThat(failure.args()).containsExactly("operationKey");
        assertThat(failure.details()).containsEntry("field", List.of("operationKey"));
        assertThat(Expect.message(failure)).contains("operationKey");
        assertThat(s.count("SELECT COUNT(*) FROM request_receipt WHERE operation_key IN (?, ?)", signedFor, sent)).isZero();
        assertThat(s.count("SELECT COUNT(*) FROM history WHERE operation_key IN (?, ?)", signedFor, sent)).isZero();
        assertThat(s.count("SELECT COUNT(*) FROM defect WHERE project_id = ?", s.projectId())).isZero();

        SaveResult written = save(s, token, create(s, signedFor, "own key"));
        assertThat(written.author()).isEqualTo(author);
        assertThat(s.count("SELECT COUNT(*) FROM request_receipt WHERE operation_key = ?", signedFor)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(longs = {301, 3600, -301, -86400})
    @DisplayName("SVC-GATE-27: an issue time more than the window (5 minutes) from the server clock, in the past or in the future, is ATTESTATION_TIME_WINDOW")
    void gate27_outsideTheWindow(long offsetSeconds, Scenario s) {
        String author = s.actorWith("defect");
        String operationKey = newOperationKey();
        Instant issued = s.ctx().clock().instant().minusSeconds(offsetSeconds);
        String token = s.ctx().keys().token(author, SAVE, operationKey, issued);
        LedgerException failure = refused(s, token, create(s, operationKey, "outside"), TrustCode.ATTESTATION_TIME_WINDOW);
        assertThat(failure.args()).containsExactly("300");
        assertThat(s.count("SELECT COUNT(*) FROM request_receipt WHERE operation_key = ?", operationKey)).isZero();
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 1, 299, 300, -299, -300})
    @DisplayName("SVC-GATE-27: an issue time exactly on the edge of the window or inside it is accepted")
    void gate27_insideTheWindow(long offsetSeconds, Scenario s) {
        String author = s.actorWith("defect");
        String operationKey = newOperationKey();
        Instant issued = s.ctx().clock().instant().minusSeconds(offsetSeconds);
        String token = s.ctx().keys().token(author, SAVE, operationKey, issued);
        assertThat(save(s, token, create(s, operationKey, "inside")).author()).isEqualTo(author);
    }

    @Test
    @DisplayName("SVC-GATE-27: the server clock decides: the same token is inside the window and, after the clock moved, outside")
    void gate27_theClockIsTheServers(Scenario s) {
        String author = s.actorWith("defect");
        String operationKey = newOperationKey();
        String token = now(s, author, SAVE, operationKey);
        s.ctx().clock().set(s.ctx().clock().instant().plus(Duration.ofMinutes(5)));
        assertThat(save(s, token, create(s, operationKey, "still inside")).author()).isEqualTo(author);
        s.ctx().clock().set(s.ctx().clock().instant().plusSeconds(1));
        Expect.failure(() -> save(s, token, create(s, operationKey, "now outside")), TrustCode.ATTESTATION_TIME_WINDOW);
    }

    @Test
    @DisplayName("SVC-GATE-28: an author whose key was never registered is ATTESTATION_SIGNATURE_INVALID (F5: not told apart from a wrong signature), whatever grants exist; the message names the session-key command and not the author")
    void gate28_keyNotRegistered(Scenario s) {
        // a grant needs a live key (SVC-GRT-11), so the author without a key is one whose key the owner closed afterwards
        String author = s.actorWith("defect");
        assertThat(s.bean(SessionKeyService.class).revoke(author, TestOwners.OWNER)).isTrue();
        String operationKey = newOperationKey();
        KeyPair unregistered = TestKeyRing.newPair();
        String token = TestKeyRing.token(unregistered, author, SAVE, operationKey, s.ctx().clock().instant());
        LedgerException failure = refused(s, token, create(s, operationKey, "no key"),
                TrustCode.ATTESTATION_SIGNATURE_INVALID);
        assertThat(failure.args()).isEmpty();
        assertThat(Expect.message(failure)).doesNotContain(author).contains("session-key");
    }

    @Test
    @DisplayName("SVC-GATE-29: a revoked key is ATTESTATION_SIGNATURE_INVALID; after the owner registers a new key the old key still is, and the new one writes")
    void gate29_revokedKey(Scenario s) {
        String author = s.actorWith("defect");
        SessionKeyService keys = s.bean(SessionKeyService.class);
        String before = newOperationKey();
        assertThat(save(s, now(s, author, SAVE, before), create(s, before, "before")).author()).isEqualTo(author);

        assertThat(keys.revoke(author, TestOwners.OWNER)).isTrue();
        String revoked = newOperationKey();
        refused(s, now(s, author, SAVE, revoked), create(s, revoked, "revoked"), TrustCode.ATTESTATION_SIGNATURE_INVALID);

        KeyPair replacement = TestKeyRing.newPair();
        keys.register(new SessionKeyService.RegisterCommand(author,
                org.pgledger.attestation.PublicKeys.encode(replacement.getPublic()), "replaced"), TestOwners.OWNER);
        String old = newOperationKey();
        refused(s, now(s, author, SAVE, old), create(s, old, "old key"), TrustCode.ATTESTATION_SIGNATURE_INVALID);
        String fresh = newOperationKey();
        String token = TestKeyRing.token(replacement, author, SAVE, fresh, s.ctx().clock().instant());
        assertThat(save(s, token, create(s, fresh, "new key")).author()).isEqualTo(author);
    }

    @Test
    @DisplayName("SVC-GATE-30: the same signed call sent again returns the stored response; the same operation key with other content is OPERATION_CONFLICT; the same token for another operation key is ATTESTATION_BINDING_MISMATCH")
    void gate30_reuseOfASignature(Scenario s) {
        String author = s.actorWith("defect");
        String operationKey = newOperationKey();
        String token = now(s, author, SAVE, operationKey);
        SaveResult first = save(s, token, create(s, operationKey, "original"));
        assertThat(save(s, token, create(s, operationKey, "original"))).isEqualTo(first);
        Expect.failure(() -> save(s, token, create(s, operationKey, "different content")), ConflictCode.OPERATION_CONFLICT);
        Expect.failure(() -> save(s, token, create(s, newOperationKey(), "different content")),
                TrustCode.ATTESTATION_BINDING_MISMATCH);
        assertThat(s.count("SELECT COUNT(*) FROM defect WHERE project_id = ?", s.projectId())).isEqualTo(1);
    }

    @Test
    @DisplayName("SVC-GATE-31: the order of the checks: signature before project and grant, key before signature, binding and window after the signature, grant last")
    void gate31_orderOfTheChecks(Scenario s) {
        String granted = s.actorWith("defect");
        String ungranted = s.actorWithoutGrants();
        String operationKey = newOperationKey();
        Instant at = s.ctx().clock().instant();
        Map<String, String> fields = s.defectFields("t");
        fields.put("project_id", "no-such-project");
        SaveCommand unknownProject = new SaveCommand(operationKey, "defect", null, 0L, Fields.of(fields), Scenario.REASON);

        // a forged signature wins over an unknown project and over a missing grant
        String forged = TestKeyRing.token(TestKeyRing.newPair(), ungranted, SAVE, operationKey, at);
        Expect.failure(() -> save(s, forged, unknownProject), TrustCode.ATTESTATION_SIGNATURE_INVALID);
        // an unregistered key wins over everything after it
        String stranger = "claude:order" + UUID.randomUUID().toString().substring(0, 8);
        String strangerToken = TestKeyRing.token(TestKeyRing.newPair(), stranger, "ledger_link", operationKey,
                at.minusSeconds(86400));
        Expect.failure(() -> save(s, strangerToken, unknownProject), TrustCode.ATTESTATION_SIGNATURE_INVALID);
        // a valid signature with the wrong tool and a stale time: the binding comes before the window
        String wrongBoth = s.ctx().keys().token(granted, "ledger_link", operationKey, at.minusSeconds(86400));
        Expect.failure(() -> save(s, wrongBoth, unknownProject), TrustCode.ATTESTATION_BINDING_MISMATCH);
        // the arguments digest is part of the binding: it comes after the tool and the operation key, before the window
        String wrongDigest = s.ctx().keys().token(granted, SAVE, operationKey, OTHER_DIGEST, at.minusSeconds(86400));
        Expect.failure(() -> save(s, wrongDigest, unknownProject), TrustCode.ATTESTATION_BINDING_MISMATCH);
        // a valid binding with a stale time: the window comes before the project
        String stale = s.ctx().keys().token(granted, SAVE, operationKey, at.minusSeconds(86400));
        Expect.failure(() -> save(s, stale, unknownProject), TrustCode.ATTESTATION_TIME_WINDOW);
        // everything about the attestation is right: now the project is looked at, then the grant
        String valid = s.ctx().keys().token(granted, SAVE, operationKey, at);
        Expect.failure(() -> save(s, valid, unknownProject), LookupCode.NOT_FOUND);
        String noGrant = s.ctx().keys().token(ungranted, SAVE, operationKey, at);
        Expect.failure(() -> save(s, noGrant, create(s, operationKey, "no grant")), TrustCode.SESSION_NOT_GRANTED);
        // verification through the author channel is refused before the attestation is looked at
        TransitionCommand verify = s.move("defect", s.newDefect("to verify").id(), 1, "VERIFIED", Scenario.EVIDENCE);
        Expect.failure(() -> asTool("ledger_transition", () -> s.bean(org.pgledger.application.write.TransitionService.class)
                .transition("garbage", verify)), TrustCode.VERIFY_NEEDS_REVIEWER_LOGIN);
    }

    @Test
    @DisplayName("SVC-GATE-32: a batch is attested once, with the operation key of the batch; its operations have no key of their own and run with keys derived from it (changed: they used to keep request ids of their own)")
    void gate32_batch(Scenario s) {
        String author = s.actorWith("defect");
        String batchId = newOperationKey();
        List<BatchOpInput> ops = List.of(
                new BatchOpInput.Save("defect", Fields.of(s.defectFields("one")), Scenario.REASON, null, null),
                new BatchOpInput.Save("defect", Fields.of(s.defectFields("two")), Scenario.REASON, null, null));
        assertThat(asTool("ledger_batch", () -> s.bean(BatchService.class).batch(now(s, author, "ledger_batch", batchId),
                batchId, ops)).results()).hasSize(2);
        // the attestation names the key of the batch; the receipts are the one of the batch under its own key (operation
        // BATCH, changed: there was none) and those of the derived keys of the operations
        OperationKey batchKey = OperationKey.parse(batchId);
        assertThat(s.count("SELECT COUNT(*) FROM request_receipt WHERE operation_key = ? AND operation = 'BATCH'",
                batchId)).isEqualTo(1);
        assertThat(s.count("SELECT COUNT(*) FROM request_receipt WHERE operation_key IN (?, ?)",
                batchKey.derive(0).toString(), batchKey.derive(1).toString())).isEqualTo(2);

        String other = newOperationKey();
        LedgerException failure = Expect.failure(() -> asTool("ledger_batch", () -> s.bean(BatchService.class)
                .batch(now(s, author, "ledger_batch", other), batchId, ops)), TrustCode.ATTESTATION_BINDING_MISMATCH);
        // changed: the attestation of the batch is checked before any operation runs, so the failure names no operation
        assertThat(failure.details()).doesNotContainKey("op_index").containsEntry("field", List.of("operationKey"));
        Expect.failure(() -> asTool("ledger_batch", () -> s.bean(BatchService.class)
                .batch(now(s, author, SAVE, batchId), batchId, ops)), TrustCode.ATTESTATION_BINDING_MISMATCH);
    }

    @Test
    @DisplayName("SVC-GATE-33: whoami is attested with the empty operation key and its own tool; the reviewer channel is the tool ledger_review")
    void gate33_whoamiAndReview(Scenario s) {
        String author = s.actorWith("defect");
        WhoamiService whoami = s.bean(WhoamiService.class);
        assertThat(asTool("ledger_whoami", () -> whoami.whoami(now(s, author, "ledger_whoami", ""), Channel.AUTHOR))
                .grants()).hasSize(1);
        assertThat(asTool("ledger_whoami", () -> whoami.whoami(now(s, author, "ledger_whoami", ""), Channel.AUTHOR))
                .author()).isEqualTo(author);
        Expect.failure(() -> asTool("ledger_whoami", () -> whoami.whoami(now(s, author, "ledger_whoami",
                newOperationKey()), Channel.AUTHOR)), TrustCode.ATTESTATION_BINDING_MISMATCH);
        Expect.failure(() -> asTool("ledger_save", () -> whoami.whoami(now(s, author, "ledger_whoami", ""),
                Channel.AUTHOR)), TrustCode.ATTESTATION_BINDING_MISMATCH);
        Expect.failure(() -> whoami.whoami(now(s, author, "ledger_whoami", ""), Channel.AUTHOR),
                TrustCode.ATTESTATION_BINDING_MISMATCH);
        Expect.failure(() -> asTool("ledger_whoami", () -> whoami.whoami(TestKeyRing.token(TestKeyRing.newPair(),
                author, "ledger_whoami", "", s.ctx().clock().instant()), Channel.AUTHOR)),
                TrustCode.ATTESTATION_SIGNATURE_INVALID);
        // a value without a valid author is still answered, not refused (as before signatures)
        assertThat(asTool("ledger_whoami", () -> whoami.whoami("claude", Channel.AUTHOR)).authorFormatOk()).isFalse();
        Expect.failure(() -> asTool("ledger_whoami", () -> whoami.whoami(null, Channel.AUTHOR)),
                TrustCode.HOST_ATTESTATION_REQUIRED);

        String reviewer = s.actorWith("defect");
        String operationKey = newOperationKey();
        SaveResult defect = s.newDefect("to review");
        TransitionCommand move = new TransitionCommand(operationKey, "defect", defect.id(), 1L, "DISPUTED", Scenario.REASON,
                Scenario.EVIDENCE, null, null);
        Expect.failure(() -> asTool("ledger_transition", () -> s.bean(ReviewService.class)
                .review(now(s, reviewer, "ledger_review", operationKey), move)), TrustCode.ATTESTATION_BINDING_MISMATCH);
        assertThat(asTool("ledger_review", () -> s.bean(ReviewService.class)
                .review(now(s, reviewer, "ledger_review", operationKey), move)).author()).isEqualTo(reviewer);
    }

    @Test
    @DisplayName("SVC-GATE-34: the window is a Duration setting, five minutes by default, of at least one second")
    void gate34_windowSetting(Scenario s) {
        assertThat(s.bean(AttestationProperties.class).window()).isEqualTo(Duration.ofMinutes(5));
        assertThat(new AttestationProperties(Duration.ofSeconds(1)).window()).isEqualTo(Duration.ofSeconds(1));
        for (Duration bad : List.of(Duration.ZERO, Duration.ofSeconds(-5), Duration.ofMillis(999))) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> new AttestationProperties(bad))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ledger.attestation.window");
        }
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new AttestationProperties(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("SVC-GATE-36: the token of a batch is bound to the digest of the arguments of the whole batch: another digest is ATTESTATION_BINDING_MISMATCH (arguments) and writes nothing (changed: the batch is attested before its first operation, so the failure names no operation any more)")
    void gate36_batchTokenIsBoundToTheArguments(Scenario s) {
        String author = s.actorWith("defect");
        String batchId = newOperationKey();
        List<BatchOpInput> ops = List.of(
                new BatchOpInput.Save("defect", Fields.of(s.defectFields("one")), Scenario.REASON, null, null));
        String token = s.ctx().keys().token(author, "ledger_batch", batchId, OTHER_DIGEST, s.ctx().clock().instant());

        LedgerException failure = Expect.failure(() -> asTool("ledger_batch", DIGEST,
                () -> s.bean(BatchService.class).batch(token, batchId, ops)), TrustCode.ATTESTATION_BINDING_MISMATCH);
        assertThat(failure.args()).containsExactly("arguments");
        assertThat(failure.details()).doesNotContainKey("op_index").containsEntry("field", List.of("arguments"));
        // a call outside a tool call has no digest at all
        Expect.failure(() -> s.bean(BatchService.class).batch(token, batchId, ops),
                TrustCode.ATTESTATION_BINDING_MISMATCH);
        assertThat(s.count("SELECT COUNT(*) FROM defect WHERE project_id = ?", s.projectId())).isZero();
        assertThat(s.count("SELECT COUNT(*) FROM request_receipt WHERE operation_key IN (?, ?)", batchId,
                OperationKey.parse(batchId).derive(0).toString())).isZero();

        assertThat(asTool("ledger_batch", OTHER_DIGEST, () -> s.bean(BatchService.class).batch(token, batchId, ops))
                .results()).hasSize(1);
        assertThat(asTool("ledger_batch", OTHER_DIGEST, () -> s.bean(BatchService.class).batch(token, batchId, ops))
                .results()).hasSize(1);
        assertThat(s.count("SELECT COUNT(*) FROM defect WHERE project_id = ?", s.projectId())).isEqualTo(1);
    }

    private static List<BatchOpInput> oneSave(Scenario s) {
        return List.of(new BatchOpInput.Save("defect", Fields.of(s.defectFields("order")), Scenario.REASON, null, null));
    }

    private static long writtenBy(Scenario s, String author) {
        return s.count("SELECT COUNT(*) FROM request_receipt WHERE author_id = ?", author)
                + s.count("SELECT COUNT(*) FROM history WHERE author_id = ?", author)
                + s.count("SELECT COUNT(*) FROM defect WHERE project_id = ?", s.projectId());
    }

    @Test
    @DisplayName("OPK-023 (service): the order of the checks of a batch starts with the attestation: an unsigned, malformed or wrongly signed call with an empty key (and with an empty list of operations) is refused by the attestation, not for the key or the operations, and writes nothing")
    void opk023_theAttestationIsFirst(Scenario s) {
        String author = s.actorWith("defect");
        String forged = TestKeyRing.token(TestKeyRing.newPair(), author, "ledger_batch", "", s.ctx().clock().instant());
        for (List<BatchOpInput> ops : List.of(oneSave(s), List.<BatchOpInput>of())) {
            Expect.failure(() -> asTool("ledger_batch", () -> s.bean(BatchService.class).batch(null, "", ops)),
                    TrustCode.HOST_ATTESTATION_REQUIRED);
            Expect.failure(() -> asTool("ledger_batch", () -> s.bean(BatchService.class).batch("claude", "", ops)),
                    TrustCode.AUTHOR_ID_FORMAT);
            Expect.failure(() -> asTool("ledger_batch", () -> s.bean(BatchService.class).batch(forged, "", ops)),
                    TrustCode.ATTESTATION_SIGNATURE_INVALID);
        }
        // a token for another operation key is refused by the attestation, before the key (here the empty one) is looked at
        String forAnotherKey = now(s, author, "ledger_batch", newOperationKey());
        Expect.failure(() -> asTool("ledger_batch", () -> s.bean(BatchService.class).batch(forAnotherKey, "",
                oneSave(s))), TrustCode.ATTESTATION_BINDING_MISMATCH);
        assertThat(writtenBy(s, author)).isZero();
    }

    @Test
    @DisplayName("OPK-023 (service): with a valid attestation the number of operations is checked before the key: an empty list with an empty key is MUST_HOLD_ITEMS at ops, and writes nothing")
    void opk023_theNumberOfOperationsComesBeforeTheKey(Scenario s) {
        String author = s.actorWith("defect");
        String token = now(s, author, "ledger_batch", "");

        LedgerException failure = Expect.failure(() -> asTool("ledger_batch", () -> s.bean(BatchService.class)
                .batch(token, "", List.of())), ValidationCode.VALIDATION_FAILED);

        assertThat(Expect.firstIssue(failure).path()).isEqualTo("ops");
        assertThat(Expect.firstIssue(failure).reason()).isEqualTo(ValidationReason.MUST_HOLD_ITEMS);
        assertThat(writtenBy(s, author)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "   ", "\t"})
    @DisplayName("OPK-023 (service): with a valid attestation and operations an empty or blank operation key is VALIDATION_FAILED at operationKey (MUST_BE_CHARS_RANGE 1..128) and nothing is written: no receipt, no history, no defect")
    void opk023_aBlankKeyIsRefusedAfterTheAttestation(String operationKey, Scenario s) {
        assertKeyRefused(s, operationKey);
    }

    @Test
    @DisplayName("OPK-023 (service): with a valid attestation and operations an operation key of 129 characters is VALIDATION_FAILED at operationKey (MUST_BE_CHARS_RANGE 1..128) and nothing is written")
    void opk023_aKeyOf129CharactersIsRefused(Scenario s) {
        assertKeyRefused(s, "k".repeat(129));
    }

    private static void assertKeyRefused(Scenario s, String operationKey) {
        String author = s.actorWith("defect");
        String token = now(s, author, "ledger_batch", operationKey);

        LedgerException failure = Expect.failure(() -> asTool("ledger_batch", () -> s.bean(BatchService.class)
                .batch(token, operationKey, oneSave(s))), ValidationCode.VALIDATION_FAILED);

        assertThat(failure.args()).containsExactly("operationKey");
        assertThat(Expect.firstIssue(failure).path()).isEqualTo("operationKey");
        assertThat(Expect.firstIssue(failure).reason()).isEqualTo(ValidationReason.MUST_BE_CHARS_RANGE);
        assertThat(Expect.firstIssue(failure).reasonArgs()).containsExactly("1", "128");
        assertThat(writtenBy(s, author)).isZero();
    }

    @Test
    @DisplayName("SVC-GATE-37: the token of a save cannot be used for other arguments, before or after the legitimate call; the legitimate call is not blocked")
    void gate37_saveTokenIsBoundToTheArguments(Scenario s) {
        String author = s.actorWith("defect");
        String operationKey = newOperationKey();
        String token = s.ctx().keys().token(author, SAVE, operationKey, OTHER_DIGEST, s.ctx().clock().instant());

        LedgerException raced = Expect.failure(() -> asTool(SAVE, DIGEST,
                () -> s.bean(SaveService.class).save(token, create(s, operationKey, "attacker content"))),
                TrustCode.ATTESTATION_BINDING_MISMATCH);
        assertThat(raced.args()).containsExactly("arguments");
        assertThat(s.count("SELECT COUNT(*) FROM request_receipt WHERE operation_key = ?", operationKey)).isZero();

        SaveResult written = asTool(SAVE, OTHER_DIGEST,
                () -> s.bean(SaveService.class).save(token, create(s, operationKey, "signed content")));
        assertThat(written.author()).isEqualTo(author);
        Expect.failure(() -> asTool(SAVE, DIGEST,
                () -> s.bean(SaveService.class).save(token, create(s, operationKey, "attacker content"))),
                TrustCode.ATTESTATION_BINDING_MISMATCH);
        assertThat(s.count("SELECT COUNT(*) FROM defect WHERE project_id = ?", s.projectId())).isEqualTo(1);
    }

    @Test
    @DisplayName("SVC-GATE-38: after the legitimate call failed (its receipt rolled back) the captured token is still bound to its arguments")
    void gate38_tokenAfterAFailedCall(Scenario s) {
        String author = s.actorWith("defect");
        String operationKey = newOperationKey();
        String token = s.ctx().keys().token(author, SAVE, operationKey, OTHER_DIGEST, s.ctx().clock().instant());
        Map<String, String> fields = s.defectFields("fails");
        fields.put("project_id", "no-such-project");
        Expect.failure(() -> asTool(SAVE, OTHER_DIGEST, () -> s.bean(SaveService.class).save(token,
                new SaveCommand(operationKey, "defect", null, 0L, Fields.of(fields), Scenario.REASON))), LookupCode.NOT_FOUND);
        assertThat(s.count("SELECT COUNT(*) FROM request_receipt WHERE operation_key = ?", operationKey)).isZero();

        Expect.failure(() -> asTool(SAVE, DIGEST, () -> s.bean(SaveService.class).save(token,
                create(s, operationKey, "captured token, other content"))), TrustCode.ATTESTATION_BINDING_MISMATCH);
        assertThat(s.count("SELECT COUNT(*) FROM defect WHERE project_id = ?", s.projectId())).isZero();
    }

    /** The token text with the 64 signature bytes replaced by zeros: well formed, never valid. */
    private static String withZeroSignature(String token) {
        return token.substring(0, token.lastIndexOf('.') + 1) + "A".repeat(86);
    }

    @Test
    @DisplayName("SVC-GATE-39: an author with a key and an author without one cannot be told apart by a caller that cannot sign: one code, no argument, no author, the same message")
    void gate39_noOracleForRegisteredKeys(Scenario s) {
        String keyed = s.actorWith("defect");
        String keyless = s.actorWith("defect");
        assertThat(s.bean(SessionKeyService.class).revoke(keyless, TestOwners.OWNER)).isTrue();
        Instant at = s.ctx().clock().instant();
        List<LedgerException> failures = new java.util.ArrayList<>();
        for (String author : List.of(keyed, keyless)) {
            String operationKey = newOperationKey();
            String zero = withZeroSignature(TestKeyRing.token(TestKeyRing.newPair(), author, SAVE, operationKey, at));
            failures.add(Expect.failure(() -> save(s, zero, create(s, operationKey, "oracle")),
                    TrustCode.ATTESTATION_SIGNATURE_INVALID));
            String foreign = TestKeyRing.token(TestKeyRing.newPair(), author, SAVE, operationKey, at);
            failures.add(Expect.failure(() -> save(s, foreign, create(s, operationKey, "oracle")),
                    TrustCode.ATTESTATION_SIGNATURE_INVALID));
            failures.add(Expect.failure(() -> asTool("ledger_whoami", () -> s.bean(WhoamiService.class).whoami(
                    TestKeyRing.token(TestKeyRing.newPair(), author, "ledger_whoami", "", at), Channel.AUTHOR)),
                    TrustCode.ATTESTATION_SIGNATURE_INVALID));
        }
        for (LedgerException failure : failures) {
            assertThat(failure.args()).isEmpty();
            assertThat(failure.details()).isEmpty();
            assertThat(Expect.message(failure)).doesNotContain(keyed).doesNotContain(keyless)
                    .isEqualTo(Expect.message(failures.getFirst()));
        }
    }

    @Test
    @DisplayName("SVC-GATE-35: no failure of the attestation carries the token, its signature or a key in its message, arguments or details")
    void gate35_noSecretInFailures(Scenario s) {
        String author = s.actorWith("defect");
        String operationKey = newOperationKey();
        String stale = s.ctx().keys().token(author, SAVE, operationKey, s.ctx().clock().instant().minusSeconds(86400));
        String forged = TestKeyRing.token(TestKeyRing.newPair(), author, SAVE, operationKey, s.ctx().clock().instant());
        String privateKeyText = java.util.Base64.getEncoder()
                .encodeToString(s.ctx().keys().pairOf(author).getPrivate().getEncoded());
        record Case(String token, TrustCode code) {
        }
        for (Case failing : List.of(new Case(stale, TrustCode.ATTESTATION_TIME_WINDOW),
                new Case(forged, TrustCode.ATTESTATION_SIGNATURE_INVALID))) {
            LedgerException failure = Expect.failure(() -> save(s, failing.token(), create(s, operationKey, "secret")),
                    failing.code());
            String signature = failing.token().substring(failing.token().lastIndexOf('.') + 1);
            assertThat(String.valueOf(failure) + failure.args() + failure.details() + Expect.message(failure))
                    .doesNotContain(failing.token()).doesNotContain(signature).doesNotContain(privateKeyText);
        }
    }
}
