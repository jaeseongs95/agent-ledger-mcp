// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.hook;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.agentledger.attestation.ArgumentsDigest;
import org.agentledger.hook.AntigravityHost.Envelope;
import tools.jackson.databind.JsonNode;

/** HK-022, HK-023, HK-030, OPK-009: which tool, which operation key and which time the signed attestation carries. */
@ExtendWith(HookExtension.class)
class SigningTest {

    /** One naming of ledger_<op> in a host: {@code %s} is the operation, the server is the audience the hook signs for. */
    private record Naming(String template, String server) {}

    private static final Map<Client, List<Naming>> NAMINGS = Map.of(
            Client.CLAUDE, List.of(new Naming("mcp__ledger__ledger_%s", "ledger"), new Naming("mcp__srv__ledger_%s", "srv"),
                    new Naming("mcp__ledger__ledger_ledger_%s", "ledger"), new Naming("mcp__srv-1__srv-1_ledger_%s", "srv-1")),
            Client.CODEX, List.of(new Naming("mcp__test__ledger_%s", "test"), new Naming("mcp__srv__ledger_%s", "srv"),
                    new Naming("mcp__test__test_ledger_%s", "test"),
                    new Naming("mcp__codex_apps__app_ledger_ledger_%s", "codex_apps.ledger"),
                    new Naming("mcp__codex_apps__app_my-app_ledger_%s", "codex_apps.my-app")),
            Client.ANTIGRAVITY, List.of(new Naming("mcp:test:ledger_%s", "test"), new Naming("mcp:test/ledger_%s", "test"),
                    new Naming("mcp:test_ledger_%s", "test"), new Naming("mcp:ledger_ledger_%s", "ledger"),
                    new Naming("mcp:test:test_ledger_%s", "test")));

    /** (host, the host's tool name, its server, operation) for every naming of every host and all ten write tools. */
    static Stream<Arguments> namings() {
        return Hosts.ALL.stream().flatMap(host -> NAMINGS.get(host.client()).stream().flatMap(naming ->
                Hosts.WRITE_OPS.stream().map(op -> Arguments.argumentSet(host + " " + naming.template().formatted(op),
                        host, naming.template().formatted(op), naming.server(), op))));
    }

    @ParameterizedTest
    @MethodSource("namings")
    @DisplayName("HK-022 the tool claim is the canonical ledger_<op> whatever naming HK-032 accepted (mcp__<server>__[<server>_]ledger_<op>, mcp:<server>:ledger_<op> ...), signed with the key of that server")
    void toolClaimIsTheCanonicalName(HostScenario host, String hostToolName, String server, String op,
            HookFixture fixture) {
        JsonNode input = Json.tree(Map.of("marker", "naming"));
        Run run = fixture.runClean(host, host.event(hostToolName, input));
        Attestations.Stamp stamp = fixture.stampCarrier(server, host.author(HookFixture.HOST_ID), op, input,
                host.stampedPart(run));
        assertThat(stamp.claims().tool()).isEqualTo("ledger_" + op);
        assertThat(run.document()).isEqualTo(host.allowed(input, stamp.token()));
    }

    /** One naming of an attachment tool in a host: {@code %s} is the tool name, for example {@code artifact-chunk}. */
    private static final Map<Client, List<Naming>> ARTIFACT_NAMINGS = Map.of(
            Client.CLAUDE, List.of(new Naming("mcp__ledger__%s", "ledger"), new Naming("mcp__srv__%s", "srv"),
                    new Naming("mcp__ledger__ledger_%s", "ledger"), new Naming("mcp__ssh-test__ssh-test_%s", "ssh-test")),
            Client.CODEX, List.of(new Naming("mcp__test__%s", "test"), new Naming("mcp__test__test_%s", "test"),
                    new Naming("mcp__codex_apps__app_ledger_%s", "codex_apps.ledger"),
                    new Naming("mcp__codex_apps__app_my-app_%s", "codex_apps.my-app")),
            Client.ANTIGRAVITY, List.of(new Naming("mcp:test:%s", "test"), new Naming("mcp:test/%s", "test"),
                    new Naming("mcp:test_%s", "test"), new Naming("mcp:test:test_%s", "test"),
                    new Naming("mcp:ssh-test:ssh-test_%s", "ssh-test")));

    static Stream<Arguments> artifactNamings() {
        return Hosts.ALL.stream().flatMap(host -> ARTIFACT_NAMINGS.get(host.client()).stream().flatMap(naming ->
                Hosts.ARTIFACT_OPS.stream().map(op -> Arguments.argumentSet(host + " " + naming.template().formatted(op),
                        host, naming.template().formatted(op), naming.server(), op))));
    }

    @ParameterizedTest
    @MethodSource("artifactNamings")
    @DisplayName("HK-022 ATA-021 an attachment tool in every naming of every host is signed for its own hyphenated name (not ledger_...), with an empty operation key, no operationKey added, and the key of its server")
    void attachmentToolClaimIsTheHyphenatedName(HostScenario host, String hostToolName, String server, String op,
            HookFixture fixture) {
        JsonNode input = Json.tree(Map.of("marker", "attachment"));
        Run run = fixture.runClean(host, host.event(hostToolName, input));
        Attestations.Stamp stamp = fixture.stampCarrier(server, host.author(HookFixture.HOST_ID), op, input,
                host.stampedPart(run));
        assertThat(stamp.claims().tool()).isEqualTo(op);
        assertThat(stamp.claims().operationKey()).isEmpty();
        assertThat(run.document()).isEqualTo(host.allowed(input, stamp.token()));
    }

    @ParameterizedTest
    @MethodSource("org.agentledger.hook.Hosts#withArtifactOps")
    @DisplayName("HK-023 OPK-009 ATA-021 an attachment tool has no operation key in its claim whatever the arguments carry: nothing is generated, a given operationKey (artifact-begin has its own) is not touched and not signed as the claim's key, and it is never denied for it")
    void attachmentToolsHaveNoOperationKey(HostScenario host, String op, HookFixture fixture) {
        for (String inputJson : WHOAMI_INPUTS) {
            JsonNode input = Json.of(inputJson);
            Run run = fixture.runClean(host, host.event(host.toolName(op), input));
            Attestations.Stamp stamp = fixture.stamp(host, op, input, run);
            assertThat(stamp.claims().operationKey()).as(inputJson).isEmpty();
            assertThat(run.document()).as(inputJson).isEqualTo(host.allowed(input, stamp.token()));
        }
    }

    /** The most base64 characters of one chunk: 262144 bytes (the chunk size of the attachments) are 87382 groups of four. */
    private static final int LARGEST_CHUNK_CHARACTERS = 349_528;

    private record ChunkCall(String uploadId, int seq, String data) {}

    @ParameterizedTest
    @MethodSource("org.agentledger.hook.Hosts#all")
    @DisplayName("ATA-021 end to end: artifact_chunk with the largest chunk (349528 base64 characters) passes the intake and the canonical form, is signed for artifact_chunk with an empty operation key (it takes none), no operationKey is added, and the digest covers the whole data")
    void largestChunkIsSigned(HostScenario host, HookFixture fixture) {
        String data = "QUJD".repeat(LARGEST_CHUNK_CHARACTERS / 4);
        assertThat(data).hasSize(LARGEST_CHUNK_CHARACTERS);
        JsonNode input = Json.tree(new ChunkCall("0b8f3c1e-1111-4222-8333-444455556666", 7, data));
        Run run = fixture.runClean(host, host.event(host.toolName("artifact_chunk"), input));
        Attestations.Stamp stamp = fixture.stamp(host, "artifact_chunk", input, run);
        assertThat(stamp.claims().tool()).isEqualTo("artifact_chunk");
        assertThat(stamp.claims().operationKey()).isEmpty();
        assertThat(run.document()).isEqualTo(host.allowed(input, stamp.token()));
        // the digest is over the whole object: one character of the data changed gives another digest
        JsonNode changed = Json.tree(new ChunkCall("0b8f3c1e-1111-4222-8333-444455556666", 7, "R" + data.substring(1)));
        Attestations.Stamp other = fixture.stamp(host, "artifact_chunk", changed,
                fixture.runClean(host, host.event(host.toolName("artifact_chunk"), changed)));
        assertThat(other.claims().argumentsDigest()).isNotEqualTo(stamp.claims().argumentsDigest());
    }

    /** The ToolName of an Antigravity {@code call_mcp_tool} envelope with the ServerName {@code srv}: plain and with the server prefix. */
    private static final List<String> WRAPPER_TOOL_NAMES = List.of("ledger_%s", "srv_ledger_%s");

    static Stream<Arguments> wrapperNamings() {
        return WRAPPER_TOOL_NAMES.stream().flatMap(template -> Hosts.WRITE_OPS.stream()
                .map(op -> Arguments.argumentSet(template.formatted(op), template.formatted(op), op)));
    }

    @ParameterizedTest
    @MethodSource("wrapperNamings")
    @DisplayName("HK-022 Antigravity call_mcp_tool: the tool claim is the canonical ledger_<op> for every ToolName form")
    void wrapperToolClaimIsTheCanonicalName(String toolName, String op, HookFixture fixture) {
        AntigravityHost agy = AntigravityHost.INSTANCE;
        JsonNode input = Json.tree(Map.of("marker", "wrapper"));
        Run run = fixture.runClean(agy, agy.wrapperEvent(new Envelope("srv", toolName, input, null)));
        Attestations.Stamp stamp = fixture.stampCarrier("srv", agy.author(HookFixture.HOST_ID), op, input, agy.echoed(run));
        assertThat(stamp.claims().tool()).isEqualTo("ledger_" + op);
    }

    // ---- HK-023 / OPK-009: operationKey ----

    /**
     * Inputs whose operationKey is absent or JSON null: the hook generates nothing (before OPK-009 it generated a
     * UUID, also for a blank text); the claim's key is empty and the arguments go on unchanged but for the token.
     */
    private static final List<String> WITHOUT_OPERATION_KEY = List.of(
            "{\"note\":\"x\"}",
            "{\"note\":\"x\",\"operationKey\":null}");

    static Stream<Arguments> withoutOperationKey() {
        return Hosts.cross(WITHOUT_OPERATION_KEY);
    }

    @ParameterizedTest
    @MethodSource("withoutOperationKey")
    @DisplayName("HK-023 OPK-009 an absent or null operationKey is not replaced by a generated one (this changed: the hook used to generate a UUID): the claim's operation key is empty and the arguments are unchanged but for _hostAttestation; the server refuses the call")
    void generatesNoOperationKey(HostScenario host, String inputJson, HookFixture fixture) {
        JsonNode input = Json.of(inputJson);
        byte[] event = host.event(host.toolName("issue_save"), input);
        Run run = fixture.runClean(host, event);
        Attestations.Stamp stamp = fixture.stamp(host, "issue_save", input, run);
        assertThat(stamp.claims().operationKey()).isEmpty();
        assertThat(run.document()).isEqualTo(host.allowed(input, stamp.token()));
        assertThat(host.stampedPart(run).has("request_id")).as("no request_id is added").isFalse();
    }

    /** A call whose arguments hold nulls, and the same call without them (what the digest covers). */
    private record NullCase(String sent, String signed) {
        @Override
        public String toString() {
            return sent;
        }
    }

    private static final List<NullCase> NULL_MEMBER_CASES = List.of(
            new NullCase("{\"operationKey\":\"k1\",\"entity\":\"issue\",\"fields\":{\"owner_actor_id\":null,\"title\":\"x\"}}",
                    "{\"operationKey\":\"k1\",\"entity\":\"issue\",\"fields\":{\"title\":\"x\"}}"),
            new NullCase("{\"operationKey\":\"k1\",\"fields\":{\"owner_actor_id\":null},\"reason\":null}",
                    "{\"operationKey\":\"k1\",\"fields\":{}}"),
            new NullCase("{\"operationKey\":\"k1\",\"note\":null,\"fields\":{\"a\":{\"b\":null,\"c\":1}}}",
                    "{\"operationKey\":\"k1\",\"fields\":{\"a\":{\"c\":1}}}"),
            new NullCase("{\"operationKey\":null,\"note\":null,\"fields\":{\"title\":null}}",
                    "{\"fields\":{}}"));

    static Stream<Arguments> nullMemberCases() {
        return Hosts.cross(NULL_MEMBER_CASES);
    }

    @ParameterizedTest
    @MethodSource("nullMemberCases")
    @DisplayName("OPK-021 a call with null members (top level and inside fields) is signed with the digest of the arguments without the nulls; the arguments are handed on unchanged but for _hostAttestation, the nulls stay in what the host gets")
    void nullMembersAreNotSignedButStayInTheArguments(HostScenario host, NullCase call, HookFixture fixture) {
        JsonNode input = Json.of(call.sent());
        Run run = fixture.runClean(host, host.event(host.toolName("issue_save"), input));
        Attestations.Stamp stamp = fixture.stamp(host, "issue_save", input, run);
        // the same value the server computes from what it receives (it never sees the null members)
        Map<?, ?> received = (Map<?, ?>) Json.MAPPER.treeToValue(Json.of(call.signed()), Object.class);
        assertThat(stamp.claims().argumentsDigest()).isEqualTo(ArgumentsDigest.of(received));
        // the nulls are handed on: the document is the input plus the token, nothing dropped
        assertThat(run.document()).isEqualTo(host.allowed(input, stamp.token()));
        JsonNode handed = host.stampedPart(run);
        // a host that merges an overwrite (the direct call of antigravity) is handed the token only: the arguments are the
        // host's own and nothing of them is touched, which the comparison of the document above has shown
        if (handed.size() > 1) {
            assertThat(handed.has("note")).isEqualTo(input.has("note"));
            if (input.has("note")) {
                assertThat(handed.get("note").isNull()).as("note stays null").isTrue();
            }
            if (input.path("fields").has("owner_actor_id")) {
                assertThat(handed.path("fields").has("owner_actor_id")).isTrue();
                assertThat(handed.path("fields").get("owner_actor_id").isNull()).as("owner_actor_id stays null").isTrue();
            }
        }
        // the claim's key: the operationKey text, empty when it was null
        assertThat(stamp.claims().operationKey()).isEqualTo(input.path("operationKey").isString()
                ? input.path("operationKey").stringValue() : "");
    }

    /** Operation keys the model chose: kept exactly as they are, blanks, case, length and non-ASCII included. */
    private static final List<String> OWN_OPERATION_KEYS = List.of(
            "abc", " Req 1 ", "UPPER-lower-Mixed", "  leading", "trailing  ", "550E8400-E29B-41D4-A716-446655440000",
            "한글-요청", "0", "", "   ", "\t\n", "x".repeat(200));

    static Stream<Arguments> ownOperationKeys() {
        return Hosts.cross(OWN_OPERATION_KEYS);
    }

    @ParameterizedTest
    @MethodSource("ownOperationKeys")
    @DisplayName("HK-023 OPK-009 an operationKey text of any content (also empty, blank or longer than the server accepts) is kept unchanged (no trimming, no case change) and signed exactly as it is; the hook does not judge the key, the server does")
    void keepsTheOwnOperationKey(HostScenario host, String operationKey, HookFixture fixture) {
        JsonNode input = Json.tree(Map.of("note", "x", "operationKey", operationKey));
        Run run = fixture.runClean(host, host.event(host.toolName("issue_save"), input));
        Attestations.Stamp stamp = fixture.stamp(host, "issue_save", input, run);
        assertThat(stamp.claims().operationKey()).isEqualTo(operationKey);
        assertThat(run.document()).isEqualTo(host.allowed(input, stamp.token()));
    }

    /** Operation keys of another JSON type: number, boolean, object, array. */
    private static final List<String> INVALID_OPERATION_KEYS = List.of(
            "7", "1.5", "0", "true", "false", "{}", "{\"a\":1}", "[]", "[\"x\"]");

    static Stream<Arguments> invalidOperationKeys() {
        return Hosts.cross(INVALID_OPERATION_KEYS);
    }

    @ParameterizedTest
    @MethodSource("invalidOperationKeys")
    @DisplayName("HK-023 OPK-009 an operationKey that is a number, boolean, object or array is denied (OPERATION_KEY_INVALID, was REQUEST_ID_INVALID): no output arguments, nothing signed, no key created")
    void deniesAnInvalidOperationKey(HostScenario host, String operationKeyJson, HookFixture fixture) {
        JsonNode input = Json.of("{\"note\":\"x\",\"operationKey\":" + operationKeyJson + "}");
        Run run = fixture.runClean(host, host.event(host.toolName("issue_save"), input));
        assertThat(run.document()).isEqualTo(host.denied(Refusal.OPERATION_KEY_INVALID));
        assertThat(run.out()).contains("operationKey").doesNotContain("updatedInput").doesNotContain("overwrite")
                .doesNotContain("ala3");
        assertThat(fixture.keyDirectory()).as("no key folder, so no key was created").doesNotExist();
    }

    static Stream<Arguments> nineWriteOps() {
        return Hosts.cross(Hosts.WRITE_OPS.stream().filter(op -> !"whoami".equals(op)).toList());
    }

    @ParameterizedTest
    @MethodSource("nineWriteOps")
    @DisplayName("HK-023 OPK-009 each of the twelve write tools signs the operationKey argument as its claim's key, and an empty key when it has none: nothing is generated")
    void nineWriteToolsSignTheOperationKey(HostScenario host, String op, HookFixture fixture) {
        JsonNode without = Json.of("{\"note\":\"x\"}");
        Run noKeyRun = fixture.runClean(host, host.event(host.toolName(op), without));
        Attestations.Stamp noKey = fixture.stamp(host, op, without, noKeyRun);
        assertThat(noKey.claims().operationKey()).isEmpty();
        assertThat(noKeyRun.document()).isEqualTo(host.allowed(without, noKey.token()));

        JsonNode with = Json.of("{\"note\":\"x\",\"operationKey\":\"key-" + op + "\"}");
        Run run = fixture.runClean(host, host.event(host.toolName(op), with));
        Attestations.Stamp stamp = fixture.stamp(host, op, with, run);
        assertThat(stamp.claims().operationKey()).isEqualTo("key-" + op);
        assertThat(run.document()).isEqualTo(host.allowed(with, stamp.token()));
    }

    /** whoami has no operation key whatever it carries: the arguments are not touched and never denied for it. */
    private static final List<String> WHOAMI_INPUTS = List.of(
            "{\"note\":\"x\"}", "{\"operationKey\":\"keep-me\"}", "{\"operationKey\":null}", "{\"operationKey\":7}",
            "{\"operationKey\":\"\"}");

    static Stream<Arguments> whoamiInputs() {
        return Hosts.cross(WHOAMI_INPUTS);
    }

    @ParameterizedTest
    @MethodSource("whoamiInputs")
    @DisplayName("HK-023 OPK-009 whoami: the operation key claim is empty and nothing is added (whoami has only _hostAttestation)")
    void whoamiHasNoOperationKey(HostScenario host, String inputJson, HookFixture fixture) {
        JsonNode input = Json.of(inputJson);
        Run run = fixture.runClean(host, host.event(host.toolName("whoami"), input));
        Attestations.Stamp stamp = fixture.stamp(host, "whoami", input, run);
        assertThat(stamp.claims().operationKey()).isEmpty();
        assertThat(run.document()).isEqualTo(host.allowed(input, stamp.token()));
    }

    // ---- HK-030: time ----

    @ParameterizedTest
    @MethodSource("org.agentledger.hook.Hosts#all")
    @DisplayName("HK-030 issuedAt follows the injected clock: runs 90 s apart give claims 90 s apart, and a fraction of a second is cut off; every token verifies")
    void issuedAtFollowsTheClock(HostScenario host, HookFixture fixture) {
        JsonNode input = Json.of("{\"operationKey\":\"r-30\"}");
        byte[] event = host.event(host.toolName("issue_save"), input);
        Attestations.Stamp first = fixture.stamp(host, "issue_save", input, fixture.runClean(host, event));
        fixture.at(HookFixture.NOW.plusSeconds(90));
        Attestations.Stamp second = fixture.stamp(host, "issue_save", input, fixture.runClean(host, event));
        Instant lateInTheSecond = HookFixture.NOW.plusSeconds(90).plusMillis(999);
        fixture.at(lateInTheSecond);
        Attestations.Stamp third = fixture.stamp(host, "issue_save", input, fixture.runClean(host, event));
        assertThat(first.claims().issuedAt()).isEqualTo(HookFixture.NOW.getEpochSecond());
        assertThat(second.claims().issuedAt() - first.claims().issuedAt()).isEqualTo(90L);
        assertThat(third.claims().issuedAt()).isEqualTo(second.claims().issuedAt());
    }
}
