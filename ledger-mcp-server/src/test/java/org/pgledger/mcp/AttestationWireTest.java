package org.pgledger.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import org.pgledger.testing.TestOwners;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.security.KeyPair;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.application.admin.SessionKeyService;
import org.pgledger.attestation.ArgumentsDigest;
import org.pgledger.attestation.PublicKeys;
import org.pgledger.mcp.error.ToolError;
import org.pgledger.mcp.support.Args;
import org.pgledger.mcp.support.BatchResults;
import org.pgledger.mcp.support.Calls;
import org.pgledger.mcp.support.Calls.Fields;
import org.pgledger.mcp.support.KeyRing;
import org.pgledger.mcp.support.Ledger;
import org.pgledger.mcp.support.McpServerTest;
import org.pgledger.mcp.support.McpSession;
import org.pgledger.mcp.support.Scenario;
import org.pgledger.mcp.support.Seed;
import org.pgledger.mcp.support.WriteCase;
import org.pgledger.mcp.tool.WriteOutputs;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.core.type.TypeReference;

/**
 * MCW-030 .. MCW-039: the signed attestation as the server sees it on the wire. A caller that holds the bearer token and
 * nothing else (no hook, no key) writes nothing as anybody: the plain {@code <vendor>:<session-id>} that the old hook
 * sent, a forged signature, a signature of another author, a token for another tool or operation key, a token of another
 * time, an author without a registered key, a revoked key. The calls are sent with {@link McpSession#callUnsigned}, so
 * the test decides exactly what the attestation is. The claim of a write tool names the {@code operationKey} of the call
 * (whoami has none: its claim key is empty).
 */
@McpServerTest
class AttestationWireTest {

    private static final String WHOAMI = "ledger_whoami";

    /** The call of the case as a flat object with this operation key (not whoami, which takes none), before the hook attests it. */
    private static Args argsOf(McpSession session, WriteCase write, Seed seed, String operationKey) {
        Args call = Args.from(session.mapper(), write.call(seed));
        if (!WHOAMI.equals(write.tool())) {
            call = call.with("operationKey", operationKey);
        }
        return call;
    }

    /** The call of the case as a flat object: with this operation key (not whoami) and this attestation. */
    private static Args callOf(McpSession session, WriteCase write, Seed seed, String operationKey, String attestation) {
        return argsOf(session, write, seed, operationKey).with("_hostAttestation", attestation);
    }

    /**
     * What the hook signs for the arguments of a call: the digest of the arguments as JSON, as the wire carries them
     * (a record inside the pairs becomes its JSON object first, with the mapper that writes the call).
     */
    private static String digest(McpSession session, Args arguments) {
        return ArgumentsDigest.of(session.mapper().convertValue(arguments.values(),
                new TypeReference<LinkedHashMap<String, Object>>() {
                }));
    }

    /** The digest of the call of the case, for the tokens of the tests that are not about the arguments. */
    private static String digestOf(McpSession session, WriteCase write, Seed seed, String operationKey) {
        return digest(session, argsOf(session, write, seed, operationKey));
    }

    /** The operation key a call of the case carries and its claim names: a fresh one, or empty for whoami (it carries none). */
    private static String operationKeyFor(WriteCase write) {
        return WHOAMI.equals(write.tool()) ? "" : UUID.randomUUID().toString();
    }

    private static ToolError refused(McpSession session, WriteCase write, Args call, String code, String... mustNotShow) {
        CallToolResult result = session.callUnsigned(write.tool(), call);
        assertThat(result.isError()).as("%s must be refused with %s", write.tool(), code).isTrue();
        ToolError error = session.error(result);
        assertThat(error.code()).isEqualTo(code);
        for (String secret : mustNotShow) {
            assertThat(error.message()).doesNotContain(secret);
            assertThat(error.details().toString()).doesNotContain(secret);
        }
        return error;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("org.pgledger.mcp.support.WriteCase#all")
    @DisplayName("MCW-030: the old attestation, a bare <vendor>:<session-id> as the author's id is known from the history, is ATTESTATION_UNSIGNED for all ten tools and writes nothing")
    void unsignedOldFormat(WriteCase write, McpSession session, Scenario scenario) {
        Seed seed = Seed.create(session, scenario);
        int before = Ledger.history(session, seed.project()).size();

        // the author id of a session is public: ledger_history lists it as author_id
        Args call = callOf(session, write, seed, operationKeyFor(write), write.actor());
        ToolError error = refused(session, write, call, "ATTESTATION_UNSIGNED");

        assertThat(error.message()).contains("not signed");
        assertThat(Ledger.history(session, seed.project())).hasSize(before);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("org.pgledger.mcp.support.WriteCase#all")
    @DisplayName("MCW-031: a forged signature, and a signature made with the key of another registered author, is ATTESTATION_SIGNATURE_INVALID for all ten tools and writes nothing")
    void forgedSignature(WriteCase write, McpSession session, Scenario scenario, @Autowired KeyRing keys) {
        Seed seed = Seed.create(session, scenario);
        keys.enroll(write.actor());
        keys.enroll(Scenario.NOGRANT);
        int before = Ledger.history(session, seed.project()).size();
        Instant now = keys.clock().instant();
        String operationKey = operationKeyFor(write);

        String digest = digestOf(session, write, seed, operationKey);
        String strangersKey = KeyRing.token(KeyRing.newPair(), write.actor(), write.tool(), operationKey, digest, now);
        String anotherAuthorsKey = KeyRing.token(keys.pairOf(Scenario.NOGRANT), write.actor(), write.tool(),
                operationKey, digest, now);
        for (String forged : List.of(strangersKey, anotherAuthorsKey)) {
            refused(session, write, callOf(session, write, seed, operationKey, forged),
                    "ATTESTATION_SIGNATURE_INVALID", forged);
        }
        assertThat(Ledger.history(session, seed.project())).hasSize(before);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("org.pgledger.mcp.support.WriteCase#all")
    @DisplayName("MCW-032: a token signed for another tool is ATTESTATION_BINDING_MISMATCH (tool) for all ten tools")
    void anotherTool(WriteCase write, McpSession session, Scenario scenario, @Autowired KeyRing keys) {
        Seed seed = Seed.create(session, scenario);
        keys.enroll(write.actor());
        String otherTool = WHOAMI.equals(write.tool()) ? "ledger_save" : WHOAMI;
        String operationKey = operationKeyFor(write);

        String token = keys.token(write.actor(), otherTool, operationKey,
                digestOf(session, write, seed, operationKey));
        ToolError error = refused(session, write, callOf(session, write, seed, operationKey, token),
                "ATTESTATION_BINDING_MISMATCH", token);

        assertThat(error.details()).containsEntry("field", List.of("tool"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("org.pgledger.mcp.support.WriteCase#all")
    @DisplayName("MCW-033: a token signed for another operationKey, or for none, is ATTESTATION_BINDING_MISMATCH (operationKey; was request_id) for all ten tools")
    void anotherOperationKey(WriteCase write, McpSession session, Scenario scenario, @Autowired KeyRing keys) {
        Seed seed = Seed.create(session, scenario);
        keys.enroll(write.actor());
        String sent = operationKeyFor(write);
        // a token for an operation key that the call does not carry (for whoami: any key, it carries none)
        String signedFor = UUID.randomUUID().toString();

        String digest = digestOf(session, write, seed, sent);
        String token = keys.token(write.actor(), write.tool(), signedFor, digest);
        ToolError error = refused(session, write, callOf(session, write, seed, sent, token),
                "ATTESTATION_BINDING_MISMATCH", token);
        assertThat(error.details()).containsEntry("field", List.of("operationKey"));
        if (!WHOAMI.equals(write.tool())) {
            String withoutKey = keys.token(write.actor(), write.tool(), "", digest);
            refused(session, write, callOf(session, write, seed, sent, withoutKey), "ATTESTATION_BINDING_MISMATCH");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("org.pgledger.mcp.support.WriteCase#keyed")
    @DisplayName("OPK-010: a token signed for another operation key is ATTESTATION_BINDING_MISMATCH with details.field operationKey for the nine write tools and writes nothing (no history row, no receipt); the same token under its own key writes once")
    void aTokenForAnotherOperationKeyWritesNothing(WriteCase write, McpSession session, Scenario scenario,
            @Autowired KeyRing keys) {
        Seed seed = Seed.create(session, scenario);
        keys.enroll(write.actor());
        int before = Ledger.history(session, seed.project()).size();
        long receiptsBefore = Ledger.receiptsOf(scenario, write.actor());
        String sent = UUID.randomUUID().toString();
        String signedFor = UUID.randomUUID().toString();
        // the digest is that of the call as sent: only the operation key of the claim differs from the call
        String digest = digestOf(session, write, seed, sent);

        String token = keys.token(write.actor(), write.tool(), signedFor, digest);
        ToolError error = refused(session, write, callOf(session, write, seed, sent, token),
                "ATTESTATION_BINDING_MISMATCH", token);

        assertThat(error.details()).containsEntry("field", List.of("operationKey"));
        assertThat(Ledger.history(session, seed.project())).hasSize(before);
        assertThat(Ledger.receipts(scenario, sent)).isZero();
        assertThat(Ledger.receipts(scenario, signedFor)).isZero();
        assertThat(Ledger.receiptsOf(scenario, write.actor())).isEqualTo(receiptsBefore);

        String own = keys.token(write.actor(), write.tool(), sent, digest);
        CallToolResult written = session.callUnsigned(write.tool(), callOf(session, write, seed, sent, own));
        assertThat(written.isError()).as("the token for the key of the call: %s", written.content()).isFalse();
        assertThat(Ledger.history(session, seed.project()).size()).isGreaterThan(before);
        assertThat(Ledger.receipts(scenario, sent)).isOne();
        assertThat(Ledger.receipts(scenario, signedFor)).isZero();
    }

    @Test
    @DisplayName("MCW-034: a token issued more than the window (5 minutes) before or after the server's time is ATTESTATION_TIME_WINDOW; one on the edge of the window is accepted")
    void timeWindow(McpSession session, Scenario scenario, @Autowired KeyRing keys) {
        Seed seed = Seed.create(session, scenario);
        WriteCase save = WriteCase.all().getFirst();
        keys.enroll(save.actor());
        Instant now = keys.clock().instant();
        for (long offset : new long[] {-600, 600, -3600 * 24, 3600 * 24}) {
            String operationKey = UUID.randomUUID().toString();
            String token = KeyRing.token(keys.pairOf(save.actor()), save.actor(), save.tool(), operationKey,
                    digestOf(session, save, seed, operationKey), now.plusSeconds(offset));
            ToolError error = refused(session, save, callOf(session, save, seed, operationKey, token),
                    "ATTESTATION_TIME_WINDOW", token);
            assertThat(error.details()).containsEntry("window_seconds", List.of("300"));
        }
        // 240 seconds either way is inside the window (the clock of the test runs on while the calls are made)
        for (long offset : new long[] {-240, 240}) {
            String operationKey = UUID.randomUUID().toString();
            String token = KeyRing.token(keys.pairOf(save.actor()), save.actor(), save.tool(), operationKey,
                    digestOf(session, save, seed, operationKey), now.plusSeconds(offset));
            assertThat(session.callUnsigned(save.tool(), callOf(session, save, seed, operationKey, token)).isError())
                    .isFalse();
        }
    }

    @Test
    @DisplayName("MCW-035: an author whose key was never registered is ATTESTATION_SIGNATURE_INVALID (F5: the same answer as a wrong signature), the message tells to show the key to the owner and does not name the author")
    void keyNotRegistered(McpSession session, Scenario scenario, @Autowired KeyRing keys) {
        Seed seed = Seed.create(session, scenario);
        WriteCase save = WriteCase.all().getFirst();
        String stranger = "claude:nokey" + UUID.randomUUID().toString().substring(0, 8);
        scenario.grant(stranger, "defect");
        String operationKey = UUID.randomUUID().toString();
        String token = KeyRing.token(KeyRing.newPair(), stranger, save.tool(), operationKey,
                digestOf(session, save, seed, operationKey), keys.clock().instant());

        ToolError error = refused(session, save, callOf(session, save, seed, operationKey, token),
                "ATTESTATION_SIGNATURE_INVALID", token);

        assertThat(error.message()).doesNotContain(stranger).contains("session-key");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("org.pgledger.mcp.support.WriteCase#all")
    @DisplayName("MCW-043: an author with a registered key and an author without one, each with a well-formed token and a zero signature, get one identical error for all ten tools: no author, no key, nothing to tell them apart")
    void noOracleForRegisteredKeys(WriteCase write, McpSession session, Scenario scenario, @Autowired KeyRing keys) {
        Seed seed = Seed.create(session, scenario);
        keys.enroll(write.actor());
        String stranger = "claude:nokey" + UUID.randomUUID().toString().substring(0, 8);
        scenario.grant(stranger, "defect");
        String operationKey = operationKeyFor(write);
        Instant now = keys.clock().instant();
        List<ToolError> errors = new java.util.ArrayList<>();
        for (String author : List.of(write.actor(), stranger)) {
            String signed = KeyRing.token(KeyRing.newPair(), author, write.tool(), operationKey,
                    digestOf(session, write, seed, operationKey), now);
            String zero = signed.substring(0, signed.lastIndexOf('.') + 1) + "A".repeat(86);
            Args call = argsOf(session, write, seed, operationKey).with("_hostAttestation", zero);
            errors.add(refused(session, write, call, "ATTESTATION_SIGNATURE_INVALID", zero, author));
        }
        assertThat(errors.get(1).message()).isEqualTo(errors.get(0).message());
        assertThat(errors.get(1).details()).isEqualTo(errors.get(0).details());
    }

    @Test
    @DisplayName("MCW-036: a revoked key is ATTESTATION_SIGNATURE_INVALID; after another key is registered, a signature of the old key still is and the new key writes")
    void revokedKey(McpSession session, Scenario scenario, @Autowired KeyRing keys,
            @Autowired SessionKeyService owner) {
        Seed seed = Seed.create(session, scenario);
        WriteCase save = WriteCase.all().getFirst();
        String author = scenario.actorWith("rk", "defect");
        keys.enroll(author);

        assertThat(owner.revoke(author, TestOwners.OWNER)).isTrue();
        String first = UUID.randomUUID().toString();
        refused(session, save, callOf(session, save, seed, first,
                keys.token(author, save.tool(), first, digestOf(session, save, seed, first))),
                "ATTESTATION_SIGNATURE_INVALID");

        KeyPair replacement = KeyRing.newPair();
        owner.register(new SessionKeyService.RegisterCommand(author, PublicKeys.encode(replacement.getPublic()), null), TestOwners.OWNER);
        String second = UUID.randomUUID().toString();
        refused(session, save, callOf(session, save, seed, second,
                keys.token(author, save.tool(), second, digestOf(session, save, seed, second))),
                "ATTESTATION_SIGNATURE_INVALID");
        String third = UUID.randomUUID().toString();
        String token = KeyRing.token(replacement, author, save.tool(), third, digestOf(session, save, seed, third),
                keys.clock().instant());
        CallToolResult written = session.callUnsigned(save.tool(), callOf(session, save, seed, third, token));
        assertThat(written.isError()).isFalse();
        assertThat(session.read(written, WriteOutputs.Saved.class).author()).isEqualTo(author);
    }

    @Test
    @DisplayName("MCW-037: a signed call that is captured and sent again returns the stored response; with other content under the same operationKey it is ATTESTATION_BINDING_MISMATCH (arguments; was REQUEST_CONFLICT before the signature covered the content); the same token for another operationKey is a binding mismatch (operationKey)")
    void replayOfASignedCall(McpSession session, Scenario scenario, @Autowired KeyRing keys) {
        WriteCase save = WriteCase.all().getFirst();
        String author = scenario.actorWith("rp", "defect");
        keys.enroll(author);
        String operationKey = UUID.randomUUID().toString();
        Args signed = Args.of().with("entity", "defect").with("reason", Scenario.REASON)
                .with("fields", Fields.defect(scenario.projectId(), "captured")).with("operationKey", operationKey);
        String token = keys.token(author, save.tool(), operationKey, digest(session, signed));
        Args captured = signed.with("_hostAttestation", token);

        WriteOutputs.Saved first = session.ok(save.tool(), captured, WriteOutputs.Saved.class);
        WriteOutputs.Saved again = session.ok(save.tool(), captured, WriteOutputs.Saved.class);
        assertThat(again).isEqualTo(first);

        ToolError conflict = session.failed(save.tool(), captured.with("fields",
                Fields.defect(scenario.projectId(), "forged content")));
        assertThat(conflict.code()).isEqualTo("ATTESTATION_BINDING_MISMATCH");
        assertThat(conflict.details()).containsEntry("field", List.of("arguments"));

        ToolError moved = session.failed(save.tool(), captured.with("operationKey", UUID.randomUUID().toString()));
        assertThat(moved.code()).isEqualTo("ATTESTATION_BINDING_MISMATCH");
        assertThat(moved.details()).containsEntry("field", List.of("operationKey"));
        assertThat(Ledger.history(session, scenario.projectId())).hasSize(1);
    }

    @Test
    @DisplayName("MCW-038: whoami of a signed session lists its grants; of a session without a registered key it is ATTESTATION_SIGNATURE_INVALID (F5); of a malformed value it still answers author_format_ok=false")
    void whoami(McpSession session, Scenario scenario, @Autowired KeyRing keys) {
        String author = scenario.actorWith("who", "defect", "task");
        keys.enroll(author);
        String emptyArguments = digest(session, Args.of());
        String token = keys.token(author, WHOAMI, "", emptyArguments);
        WriteOutputs.Whoami who = session.ok(WHOAMI, Args.of().with("_hostAttestation", token),
                WriteOutputs.Whoami.class);
        assertThat(who.author()).isEqualTo(author);
        assertThat(who.grants()).hasSize(2);

        String stranger = "claude:nokey" + UUID.randomUUID().toString().substring(0, 8);
        String forged = KeyRing.token(KeyRing.newPair(), stranger, WHOAMI, "", emptyArguments, keys.clock().instant());
        assertThat(session.failed(WHOAMI, Args.of().with("_hostAttestation", forged)).code())
                .isEqualTo("ATTESTATION_SIGNATURE_INVALID");
        assertThat(session.error(session.callUnsigned(WHOAMI, Args.of().with("_hostAttestation", author))).code())
                .isEqualTo("ATTESTATION_UNSIGNED");
        assertThat(session.ok(WHOAMI, Args.of().with("_hostAttestation", "claude"), WriteOutputs.Whoami.class)
                .authorFormatOk()).isFalse();
    }

    /** The token of the hook for exactly these arguments (the operation key is in them as the caller sent it). */
    private static String signedFor(McpSession session, KeyRing keys, String author, String tool, String operationKey,
            Args arguments) {
        return keys.token(author, tool, operationKey, digest(session, arguments));
    }

    private static Args batchCall(McpSession session, String operationKey, Calls.Op... ops) {
        return Args.from(session.mapper(), Calls.Batch.of(null, ops)).with("operationKey", operationKey);
    }

    @Test
    @DisplayName("MCW-041: the token of a batch cannot be sent again with other operations: new operations, an extra one or a changed one are ATTESTATION_BINDING_MISMATCH (arguments) and write nothing")
    void batchTokenCannotCarryOtherOperations(McpSession session, Scenario scenario, @Autowired KeyRing keys) {
        String author = scenario.actorWith("br", "defect");
        keys.enroll(author);
        String project = scenario.projectId();
        String batchKey = UUID.randomUUID().toString();
        Args legitimate = batchCall(session, batchKey, Calls.Op.save("defect", Fields.defect(project, "one")));
        String token = signedFor(session, keys, author, "ledger_batch", batchKey, legitimate);

        // (the operations carry no key of their own, so the changes are in the operations themselves)
        List<Args> variants = List.of(
                batchCall(session, batchKey, Calls.Op.save("defect", Fields.defect(project, "one")),
                        Calls.Op.save("defect", Fields.defect(project, "extra"))),
                batchCall(session, batchKey, Calls.Op.save("defect", Fields.defect(project, "changed"))),
                batchCall(session, batchKey, Calls.Op.save("task", Fields.task(project, "one"))));
        int before = Ledger.history(session, project).size();
        for (Args variant : variants) {
            ToolError error = session.failed("ledger_batch", variant.with("_hostAttestation", token));
            assertThat(error.code()).isEqualTo("ATTESTATION_BINDING_MISMATCH");
            assertThat(error.details()).containsEntry("field", List.of("arguments"));
            assertThat(error.message()).doesNotContain(token);
        }
        assertThat(Ledger.history(session, project)).hasSize(before);

        session.ok("ledger_batch", legitimate.with("_hostAttestation", token), BatchResults.class);
        session.ok("ledger_batch", legitimate.with("_hostAttestation", token), BatchResults.class);
        assertThat(Ledger.history(session, project)).hasSize(before + 1);
    }

    @Test
    @DisplayName("MCW-042: the token of a save cannot be used for other content, whether the legitimate call came first or not")
    void saveTokenCannotCarryOtherContent(McpSession session, Scenario scenario, @Autowired KeyRing keys) {
        String author = scenario.actorWith("pl", "defect");
        keys.enroll(author);
        String operationKey = UUID.randomUUID().toString();
        Args signed = Args.of().with("entity", "defect").with("reason", Scenario.REASON)
                .with("fields", Fields.defect(scenario.projectId(), "signed")).with("operationKey", operationKey);
        Args other = signed.with("fields", Fields.defect(scenario.projectId(), "attacker content"));
        String token = signedFor(session, keys, author, "ledger_save", operationKey, signed);
        int before = Ledger.history(session, scenario.projectId()).size();

        // the attacker is first: the other content must not win the race for the operation key
        ToolError raced = session.failed("ledger_save", other.with("_hostAttestation", token));
        assertThat(raced.code()).isEqualTo("ATTESTATION_BINDING_MISMATCH");
        assertThat(raced.details()).containsEntry("field", List.of("arguments"));
        assertThat(Ledger.history(session, scenario.projectId())).hasSize(before);

        WriteOutputs.Saved written = session.ok("ledger_save", signed.with("_hostAttestation", token),
                WriteOutputs.Saved.class);
        assertThat(written.author()).isEqualTo(author);
        assertThat(session.failed("ledger_save", other.with("_hostAttestation", token)).code())
                .isEqualTo("ATTESTATION_BINDING_MISMATCH");
        assertThat(Ledger.history(session, scenario.projectId())).hasSize(before + 1);
    }

    @Test
    @DisplayName("MCW-044 (audit2 N2): a token signed for the decimal 9007199254740992.0 cannot carry the integer 9007199254740993 (or 9007199254740992): the server does not verify an integer beyond 2**53-1, ATTESTATION_BINDING_MISMATCH (arguments), nothing is written")
    void anIntegerBeyondTheExactRangeIsNotVerified(McpSession session, Scenario scenario, @Autowired KeyRing keys) {
        String author = scenario.actorWith("pl", "defect");
        keys.enroll(author);
        String operationKey = UUID.randomUUID().toString();
        Args signed = Args.of().with("entity", "defect").with("reason", Scenario.REASON)
                .with("fields", Fields.defect(scenario.projectId(), "signed")).with("operationKey", operationKey)
                .with("expected_version", 9007199254740992.0);
        String token = signedFor(session, keys, author, "ledger_save", operationKey, signed);
        int before = Ledger.history(session, scenario.projectId()).size();

        for (long integer : new long[] {9007199254740993L, 9007199254740992L}) {
            ToolError error = session.failed("ledger_save",
                    signed.with("expected_version", integer).with("_hostAttestation", token));
            assertThat(error.code()).isEqualTo("ATTESTATION_BINDING_MISMATCH");
            assertThat(error.details()).containsEntry("field", List.of("arguments"));
        }
        assertThat(Ledger.history(session, scenario.projectId())).hasSize(before);
    }

    @Test
    @DisplayName("MCC-028: no tool registers, revokes or lists keys or grants: the owner does that in the admin web and the CLI only")
    void noKeyOrGrantTool(McpSession session) {
        assertThat(session.tools()).extracting(io.modelcontextprotocol.spec.McpSchema.Tool::name)
                .noneMatch(name -> name.contains("key") || name.contains("grant") || name.contains("register")
                        || name.contains("revoke") || name.contains("attest"));
    }

    @Test
    @DisplayName("MCW-039: a text that has the prefix of a token but is not a canonical token is ATTESTATION_MALFORMED and the error never contains it")
    void malformedToken(McpSession session, Scenario scenario) {
        Seed seed = Seed.create(session, scenario);
        WriteCase save = WriteCase.all().getFirst();
        for (String text : List.of("pgla2.", "pgla2.a.b.c.d.e", "pgla2.!.!.!.!.1.!", "pgla2." + "A".repeat(5000))) {
            refused(session, save, callOf(session, save, seed, UUID.randomUUID().toString(), text),
                    "ATTESTATION_MALFORMED", text);
        }
        // the retired version 1 is its own answer, never accepted
        for (String text : List.of("pgla1.", "pgla1.a.b.c.1.AAAA")) {
            refused(session, save, callOf(session, save, seed, UUID.randomUUID().toString(), text),
                    "ATTESTATION_VERSION_UNSUPPORTED", text);
        }
    }
}
