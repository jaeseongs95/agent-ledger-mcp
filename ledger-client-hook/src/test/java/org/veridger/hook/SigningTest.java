// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.hook;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.veridger.hook.AntigravityHost.Envelope;
import tools.jackson.databind.JsonNode;

/** HK-022, HK-023, OPK-009: tool naming recognition and operationKey preservation across all three hosts. */
@ExtendWith(HookExtension.class)
class SigningTest {

    private record Naming(String template, String server) {}

    private static final Map<Client, List<Naming>> NAMINGS = Map.of(
            Client.CLAUDE, List.of(new Naming("mcp__ledger__ledger_%s", "ledger"), new Naming("mcp__srv__ledger_%s", "srv"),
                    new Naming("mcp__ledger__ledger_ledger_%s", "ledger"), new Naming("mcp__srv-1__srv-1_ledger_%s", "srv-1"),
                    new Naming("mcp__veridger__ledger_ledger_%s", "veridger"),
                    new Naming("mcp__ledger__veridger_ledger_%s", "ledger")),
            Client.CODEX, List.of(new Naming("mcp__test__ledger_%s", "test"), new Naming("mcp__srv__ledger_%s", "srv"),
                    new Naming("mcp__test__test_ledger_%s", "test"),
                    new Naming("mcp__veridger__ledger_ledger_%s", "veridger"),
                    new Naming("mcp__portal__veridger_ledger_%s", "portal"),
                    new Naming("mcp__codex_apps__app_ledger_ledger_%s", "codex_apps.ledger"),
                    new Naming("mcp__codex_apps__app_my-app_ledger_%s", "codex_apps.my-app")),
            Client.ANTIGRAVITY, List.of(new Naming("mcp:test:ledger_%s", "test"), new Naming("mcp:test/ledger_%s", "test"),
                    new Naming("mcp:test_ledger_%s", "test"), new Naming("mcp:ledger_ledger_%s", "ledger"),
                    new Naming("mcp:test:test_ledger_%s", "test"),
                    new Naming("mcp:veridger:ledger_ledger_%s", "veridger"),
                    new Naming("mcp:veridger/ledger_ledger_%s", "veridger"),
                    new Naming("mcp:veridger_ledger_ledger_%s", "veridger"),
                    new Naming("mcp:my_server_ledger_%s", "my_server"),
                    new Naming("mcp:ledger:veridger_ledger_%s", "ledger")));

    static Stream<Arguments> namings() {
        return Hosts.ALL.stream().flatMap(host -> NAMINGS.get(host.client()).stream().flatMap(naming ->
                Hosts.WRITE_OPS.stream().map(op -> Arguments.argumentSet(host + " " + naming.template().formatted(op),
                        host, naming.template().formatted(op), op))));
    }

    @ParameterizedTest
    @MethodSource("namings")
    @DisplayName("HK-022 every accepted host naming variant stamps _hostAttestation with <vendor>:<host id>")
    void everyWriteNamingIsStamped(HostScenario host, String hostToolName, String op, HookFixture fixture) {
        JsonNode input = Json.tree(Map.of("marker", "naming"));
        Run run = fixture.runClean(host, host.event(hostToolName, input));
        Attestations.Stamp stamp = fixture.stampCarrier(host.author(HookFixture.HOST_ID), op, input,
                host.stampedPart(run));
        assertThat(run.document()).isEqualTo(host.allowed(input, stamp.token()));
    }

    private static final Map<Client, List<Naming>> ARTIFACT_NAMINGS = Map.of(
            Client.CLAUDE, List.of(new Naming("mcp__ledger__%s", "ledger"), new Naming("mcp__srv__%s", "srv"),
                    new Naming("mcp__ledger__ledger_%s", "ledger"), new Naming("mcp__ssh-test__ssh-test_%s", "ssh-test"),
                    new Naming("mcp__veridger__ledger_%s", "veridger"),
                    new Naming("mcp__ledger__veridger_%s", "ledger")),
            Client.CODEX, List.of(new Naming("mcp__test__%s", "test"), new Naming("mcp__test__test_%s", "test"),
                    new Naming("mcp__veridger__ledger_%s", "veridger"),
                    new Naming("mcp__portal__veridger_%s", "portal"),
                    new Naming("mcp__codex_apps__app_ledger_%s", "codex_apps.ledger"),
                    new Naming("mcp__codex_apps__app_my-app_%s", "codex_apps.my-app")),
            Client.ANTIGRAVITY, List.of(new Naming("mcp:test:%s", "test"), new Naming("mcp:test/%s", "test"),
                    new Naming("mcp:test_%s", "test"), new Naming("mcp:test:test_%s", "test"),
                    new Naming("mcp:ssh-test:ssh-test_%s", "ssh-test"),
                    new Naming("mcp:veridger:ledger_%s", "veridger"),
                    new Naming("mcp:ledger:veridger_%s", "ledger")));

    static Stream<Arguments> artifactNamings() {
        return Hosts.ALL.stream().flatMap(host -> ARTIFACT_NAMINGS.get(host.client()).stream().flatMap(naming ->
                Hosts.ARTIFACT_OPS.stream().map(op -> Arguments.argumentSet(host + " " + naming.template().formatted(op),
                        host, naming.template().formatted(op), op))));
    }

    @ParameterizedTest
    @MethodSource("artifactNamings")
    @DisplayName("HK-022 ATA-021 every attachment tool naming variant is stamped with <vendor>:<host id> and no operationKey is added")
    void attachmentToolsAreStamped(HostScenario host, String hostToolName, String op, HookFixture fixture) {
        JsonNode input = Json.tree(Map.of("marker", "attachment"));
        Run run = fixture.runClean(host, host.event(hostToolName, input));
        Attestations.Stamp stamp = fixture.stampCarrier(host.author(HookFixture.HOST_ID), op, input,
                host.stampedPart(run));
        assertThat(run.document()).isEqualTo(host.allowed(input, stamp.token()));
    }

    @ParameterizedTest
    @MethodSource("org.veridger.hook.Hosts#withArtifactOps")
    @DisplayName("HK-023 OPK-009 ATA-021 attachment tools pass any operationKey untouched and are never denied for it")
    void attachmentToolsHaveNoOperationKey(HostScenario host, String op, HookFixture fixture) {
        for (String inputJson : WHOAMI_INPUTS) {
            JsonNode input = Json.of(inputJson);
            Run run = fixture.runClean(host, host.event(host.toolName(op), input));
            Attestations.Stamp stamp = fixture.stamp(host, op, input, run);
            assertThat(run.document()).as(inputJson).isEqualTo(host.allowed(input, stamp.token()));
        }
    }

    private static final int LARGEST_CHUNK_CHARACTERS = 349_528;

    private record ChunkCall(String uploadId, int seq, String data) {}

    @ParameterizedTest
    @MethodSource("org.veridger.hook.Hosts#all")
    @DisplayName("ATA-021 end to end: artifact_chunk with the largest chunk (349528 base64 characters) is stamped untouched")
    void largestChunkIsStamped(HostScenario host, HookFixture fixture) {
        String data = "QUJD".repeat(LARGEST_CHUNK_CHARACTERS / 4);
        assertThat(data).hasSize(LARGEST_CHUNK_CHARACTERS);
        JsonNode input = Json.tree(new ChunkCall("0b8f3c1e-1111-4222-8333-444455556666", 7, data));
        Run run = fixture.runClean(host, host.event(host.toolName("artifact_chunk"), input));
        Attestations.Stamp stamp = fixture.stamp(host, "artifact_chunk", input, run);
        assertThat(run.document()).isEqualTo(host.allowed(input, stamp.token()));
    }

    private static final List<String> WRAPPER_TOOL_NAMES = List.of(
            "ledger_%s", "srv_ledger_%s", "ledger_ledger_%s", "veridger_ledger_%s");

    static Stream<Arguments> wrapperNamings() {
        return WRAPPER_TOOL_NAMES.stream().flatMap(template -> Hosts.WRITE_OPS.stream()
                .map(op -> Arguments.argumentSet(template.formatted(op), template.formatted(op), op)));
    }

    @ParameterizedTest
    @MethodSource("wrapperNamings")
    @DisplayName("HK-022 Antigravity call_mcp_tool: stamps _hostAttestation for every ToolName form")
    void wrapperToolIsStamped(String toolName, String op, HookFixture fixture) {
        AntigravityHost agy = AntigravityHost.INSTANCE;
        JsonNode input = Json.tree(Map.of("marker", "wrapper"));
        Run run = fixture.runClean(agy, agy.wrapperEvent(new Envelope("srv", toolName, input, null)));
        Attestations.Stamp stamp = fixture.stampCarrier(agy.author(HookFixture.HOST_ID), op, input, agy.echoed(run));
        assertThat(stamp.author()).isEqualTo(agy.author(HookFixture.HOST_ID));
    }

    private static final List<String> WITHOUT_OPERATION_KEY = List.of(
            "{\"note\":\"x\"}",
            "{\"note\":\"x\",\"operationKey\":null}");

    static Stream<Arguments> withoutOperationKey() {
        return Hosts.cross(WITHOUT_OPERATION_KEY);
    }

    @ParameterizedTest
    @MethodSource("withoutOperationKey")
    @DisplayName("HK-023 OPK-009 an absent or null operationKey is not fabricated; arguments stay unchanged except _hostAttestation")
    void generatesNoOperationKey(HostScenario host, String inputJson, HookFixture fixture) {
        JsonNode input = Json.of(inputJson);
        Run run = fixture.runClean(host, host.event(host.toolName("issue_save"), input));
        Attestations.Stamp stamp = fixture.stamp(host, "issue_save", input, run);
        assertThat(run.document()).isEqualTo(host.allowed(input, stamp.token()));
        assertThat(host.stampedPart(run).has("request_id")).as("no request_id is added").isFalse();
    }

    private static final List<String> NULL_MEMBER_INPUTS = List.of(
            "{\"operationKey\":\"k1\",\"entity\":\"issue\",\"fields\":{\"owner_actor_id\":null,\"title\":\"x\"}}",
            "{\"operationKey\":\"k1\",\"fields\":{\"owner_actor_id\":null},\"reason\":null}",
            "{\"operationKey\":\"k1\",\"note\":null,\"fields\":{\"a\":{\"b\":null,\"c\":1}}}",
            "{\"operationKey\":null,\"note\":null,\"fields\":{\"title\":null}}");

    static Stream<Arguments> nullMemberInputs() {
        return Hosts.cross(NULL_MEMBER_INPUTS);
    }

    @ParameterizedTest
    @MethodSource("nullMemberInputs")
    @DisplayName("OPK-021 null members stay untouched in the stamped arguments")
    void nullMembersStayInTheArguments(HostScenario host, String sent, HookFixture fixture) {
        JsonNode input = Json.of(sent);
        Run run = fixture.runClean(host, host.event(host.toolName("issue_save"), input));
        Attestations.Stamp stamp = fixture.stamp(host, "issue_save", input, run);
        assertThat(run.document()).isEqualTo(host.allowed(input, stamp.token()));
    }

    private static final List<String> OWN_OPERATION_KEYS = List.of(
            "abc", " Req 1 ", "UPPER-lower-Mixed", "  leading", "trailing  ", "550E8400-E29B-41D4-A716-446655440000",
            "한글-요청", "0", "", "   ", "\t\n", "x".repeat(200));

    static Stream<Arguments> ownOperationKeys() {
        return Hosts.cross(OWN_OPERATION_KEYS);
    }

    @ParameterizedTest
    @MethodSource("ownOperationKeys")
    @DisplayName("HK-023 OPK-009 any string operationKey is kept unchanged")
    void keepsTheOwnOperationKey(HostScenario host, String operationKey, HookFixture fixture) {
        JsonNode input = Json.tree(Map.of("note", "x", "operationKey", operationKey));
        Run run = fixture.runClean(host, host.event(host.toolName("issue_save"), input));
        Attestations.Stamp stamp = fixture.stamp(host, "issue_save", input, run);
        assertThat(run.document()).isEqualTo(host.allowed(input, stamp.token()));
    }

    private static final List<String> INVALID_OPERATION_KEYS = List.of(
            "7", "1.5", "0", "true", "false", "{}", "{\"a\":1}", "[]", "[\"x\"]");

    static Stream<Arguments> invalidOperationKeys() {
        return Hosts.cross(INVALID_OPERATION_KEYS);
    }

    @ParameterizedTest
    @MethodSource("invalidOperationKeys")
    @DisplayName("HK-023 OPK-009 an operationKey that is a number, boolean, object or array is denied OPERATION_KEY_INVALID")
    void deniesAnInvalidOperationKey(HostScenario host, String operationKeyJson, HookFixture fixture) {
        JsonNode input = Json.of("{\"note\":\"x\",\"operationKey\":" + operationKeyJson + "}");
        Run run = fixture.runClean(host, host.event(host.toolName("issue_save"), input));
        assertThat(run.document()).isEqualTo(host.denied(Refusal.OPERATION_KEY_INVALID));
        assertThat(run.out()).contains("operationKey").doesNotContain("updatedInput").doesNotContain("overwrite");
    }

    static Stream<Arguments> keyedWriteOps() {
        return Hosts.cross(Hosts.WRITE_OPS.stream().filter(op -> !"whoami".equals(op)).toList());
    }

    @ParameterizedTest
    @MethodSource("keyedWriteOps")
    @DisplayName("HK-023 OPK-009 each of the twelve keyed write tools stamps _hostAttestation without generating operationKey")
    void keyedWriteToolsPreserveOperationKey(HostScenario host, String op, HookFixture fixture) {
        JsonNode without = Json.of("{\"note\":\"x\"}");
        Run noKeyRun = fixture.runClean(host, host.event(host.toolName(op), without));
        Attestations.Stamp noKey = fixture.stamp(host, op, without, noKeyRun);
        assertThat(noKeyRun.document()).isEqualTo(host.allowed(without, noKey.token()));

        JsonNode with = Json.of("{\"note\":\"x\",\"operationKey\":\"key-" + op + "\"}");
        Run run = fixture.runClean(host, host.event(host.toolName(op), with));
        Attestations.Stamp stamp = fixture.stamp(host, op, with, run);
        assertThat(run.document()).isEqualTo(host.allowed(with, stamp.token()));
    }

    private static final List<String> WHOAMI_INPUTS = List.of(
            "{\"note\":\"x\"}", "{\"operationKey\":\"keep-me\"}", "{\"operationKey\":null}", "{\"operationKey\":7}",
            "{\"operationKey\":\"\"}");

    static Stream<Arguments> whoamiInputs() {
        return Hosts.cross(WHOAMI_INPUTS);
    }

    @ParameterizedTest
    @MethodSource("whoamiInputs")
    @DisplayName("HK-023 OPK-009 whoami passes any arguments untouched and stamps _hostAttestation")
    void whoamiHasNoOperationKey(HostScenario host, String inputJson, HookFixture fixture) {
        JsonNode input = Json.of(inputJson);
        Run run = fixture.runClean(host, host.event(host.toolName("whoami"), input));
        Attestations.Stamp stamp = fixture.stamp(host, "whoami", input, run);
        assertThat(run.document()).isEqualTo(host.allowed(input, stamp.token()));
    }
}
