// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.hook;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.agentledger.hookclient.ResidentAddress;
import tools.jackson.databind.JsonNode;

/**
 * Tests that start the light client of the staged plugin folder, the way a host does, with the resident program next to it:
 * HK-015, HK-016, HK-017, HK-020, HK-021, REM-008 and the 1 MB body of HK-014.
 */
@ExtendWith(HookExtension.class)
class JarProcessTest {

    private static final int HOST_LIMIT_MILLIS = 5_000;
    private static final JsonNode KOREAN_INPUT =
            Json.tree(Map.of("note", "한글 😀 é", "n", 9007199254740991L));

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** Three answers of every host that do not depend on the clock: denied, passed and silent for empty input. */
    static Stream<Arguments> cleanChannelCases() {
        return Hosts.ALL.stream().flatMap(host -> Stream.of(
                Arguments.argumentSet(host + " deny", host,
                        host.event(host.toolName("issue_save"), Json.tree(Map.of(Json.RESERVED_KEY, Json.of("null")))),
                        host.denied(Refusal.RESERVED_FIELD)),
                Arguments.argumentSet(host + " pass-through", host, host.event(host.toolName("get"), Json.of("{}")),
                        host.untouched()),
                Arguments.argumentSet(host + " empty input", host, new byte[0], Optional.empty())));
    }

    @ParameterizedTest
    @MethodSource("cleanChannelCases")
    @DisplayName("HK-016 real jar: stdout is at most one line of JSON, stderr is empty, exit 0 (deny, pass-through, empty input)")
    void cleanChannels(HostScenario host, byte[] stdin, Optional<JsonNode> expected, HookJar jar) throws IOException {
        Run run = jar.run(stdin, host.client().argument());
        assertThat(run.err()).as("stderr").isEmpty();
        assertThat(run.exit()).as("exit code").isZero();
        assertThat(run.out()).matches("(?s)|[^\\n]*\\n");
        assertThat(run.document()).isEqualTo(expected);
    }

    @ParameterizedTest
    @MethodSource("org.agentledger.hook.Hosts#all")
    @DisplayName("HK-016 real jar: a stamped answer is one line of JSON, stderr is empty, exit 0, and its token verifies with the key in the key folder")
    void stampedChannelsAreClean(HostScenario host, HookJar jar) throws IOException {
        Run run = jar.run(host.event(host.toolName("issue_save"), KOREAN_INPUT), host.client().argument());
        assertThat(run.err()).as("stderr").isEmpty();
        assertThat(run.exit()).as("exit code").isZero();
        assertThat(run.out()).matches("(?s)[^\\n]*\\n");
        Attestations.Stamp stamp = jar.stamp(host, "issue_save", KOREAN_INPUT, run);
        assertThat(run.document()).isEqualTo(host.allowed(KOREAN_INPUT, stamp.token()));
    }

    @ParameterizedTest
    @MethodSource("org.agentledger.hook.Hosts#all")
    @DisplayName("HK-016 real jar: Claude malformed JSON exits 2 with a stderr message and empty stdout; Codex and Antigravity deny on stdout, exit 0")
    void malformedThroughTheJar(HostScenario host, HookJar jar) throws IOException {
        InputErrorTest.assertMalformedAnswer(host, jar.run(bytes("{"), host.client().argument()));
    }

    private static final List<String> NON_DEFAULT_JVM = List.of(
            "-Dfile.encoding=windows-949", "-Dstdout.encoding=windows-949", "-Dstderr.encoding=windows-949",
            "-Dsun.jnu.encoding=windows-949", "-Dstdin.encoding=windows-949", "-Duser.language=tr", "-Duser.country=TR");

    @ParameterizedTest
    @MethodSource("org.agentledger.hook.Hosts#all")
    @DisplayName("HK-015 with another JVM default charset, locale and LC_ALL=C the output is the same UTF-8 text (the token, which differs with every signing time, is masked)")
    void outputIsUtf8WhateverTheJvmDefaults(HostScenario host, HookJar jar, HookFixture fixture) throws IOException {
        // an own operation key (the hook generates none: the answer carries it unchanged)
        JsonNode input = Json.tree(Map.of("note", "한글 😀 é", "operationKey", "req-15"));
        byte[] stdin = host.echoEvent("issue_save", input);
        // the first call starts the resident program, which gets the environment of this call: LC_ALL=C and LANG=C
        Run other = jar.start(HookJar.JAVA, NON_DEFAULT_JVM, Map.of("LC_ALL", "C", "LANG", "C"), stdin, host.client().argument()).run();
        assertThat(other.err()).as("stderr").isEmpty();
        assertThat(other.exit()).isZero();
        assertThat(other.stdout()).contains(bytes("한글 😀 é"));
        Run baseline = jar.run(stdin, host.client().argument());
        assertThat(baseline.exit()).isZero();
        assertThat(baseline.stdout()).contains(bytes("한글 😀 é"));
        Run inProcess = fixture.runClean(host, stdin);
        assertThat(inProcess.stdout()).contains(bytes("한글 😀 é"));
        assertThat(masked(host, other)).isEqualTo(masked(host, baseline));
        assertThat(masked(host, inProcess)).isEqualTo(masked(host, baseline));
    }

    /** The answer as text with the attestation token replaced: everything else must be identical between runs. */
    private static String masked(HostScenario host, Run run) {
        String token = host.echoed(run).path(Json.RESERVED_KEY).stringValue();
        return run.out().replace(token, "<token>");
    }

    /** The measured time of a call, written to the metrics folder of the build (HK-017). */
    private static void record(String name, int number, HookJar.Timed timed, String what) throws IOException {
        String report = "HK-017 " + what + " " + number + ": wall time of the client " + timed.millis() + " ms ; limit "
                + HOST_LIMIT_MILLIS + " ms ; client jar " + HookJar.JAR.toFile().length() + " bytes ; "
                + System.getProperty("java.vendor.version");
        Path dir = Path.of(System.getProperty("ledger.hook.metrics"));
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(name + "-" + number + ".txt"), report + "\n", StandardCharsets.UTF_8);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    @DisplayName("HK-017 first call (changed by the user decision of 2026-11-22): no resident program is running, so the call starts it and is answered, all inside the 5 s host hook limit; the measured time of each start is recorded")
    void firstCallStartsTheResidentProgramInsideTheLimit(int start, HookJar jar) throws IOException {
        CodexHost codex = new CodexHost();
        assertThat(Residents.running(jar.home())).as("nothing runs before the first call").isEmpty();
        HookJar.Timed timed = jar.start(codex.event(codex.toolName("issue_save"), KOREAN_INPUT), "codex");
        assertThat(timed.run().err()).isEmpty();
        Attestations.Stamp stamp = jar.stamp(codex, "issue_save", KOREAN_INPUT, timed.run());
        assertThat(timed.run().document()).isEqualTo(codex.allowed(KOREAN_INPUT, stamp.token()));
        assertThat(Residents.running(jar.home())).as("the resident program is left running").hasSize(1);
        record("hk-017-first-call", start, timed, "first call (resident program started)");
        assertThat(timed.millis()).isLessThan(HOST_LIMIT_MILLIS);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5})
    @DisplayName("HK-017 later calls (changed by the user decision of 2026-11-22): the resident program is running, so a call is the light client and one request, inside the 5 s host hook limit; the measured time of each call is recorded")
    void callsToARunningResidentProgramAreInsideTheLimit(int call, HookJar jar) throws IOException {
        CodexHost codex = new CodexHost();
        byte[] stdin = codex.event(codex.toolName("issue_save"), KOREAN_INPUT);
        jar.run(stdin, "codex");
        ResidentAddress running = Residents.running(jar.home()).getFirst();
        HookJar.Timed timed = jar.start(stdin, "codex");
        assertThat(timed.run().err()).isEmpty();
        Attestations.Stamp stamp = jar.stamp(codex, "issue_save", KOREAN_INPUT, timed.run());
        assertThat(timed.run().document()).isEqualTo(codex.allowed(KOREAN_INPUT, stamp.token()));
        assertThat(Residents.running(jar.home())).as("the same program answered").containsExactly(running);
        record("hk-017-running", call, timed, "call to a running resident program");
        assertThat(timed.millis()).isLessThan(HOST_LIMIT_MILLIS);
    }

    @Test
    @DisplayName("HK-014 a 1 MB body through the real jar stays inside the 5 s host limit")
    void oneMegabyteThroughTheJar(HookJar jar) throws IOException {
        ClaudeHost claude = new ClaudeHost();
        String text = "한글abc".repeat(120_000);
        JsonNode input = Json.tree(Map.of("body", text));
        byte[] stdin = claude.event(claude.toolName("issue_save"), input);
        assertThat(stdin.length).isGreaterThan(1_000_000);
        // the resident program is started and the body is signed in the same call: the whole first call is inside the limit
        HookJar.Timed timed = jar.start(stdin, "claude");
        assertThat(timed.run().err()).isEmpty();
        Attestations.Stamp stamp = jar.stamp(claude, "issue_save", input, timed.run());
        assertThat(timed.run().document()).isEqualTo(claude.allowed(input, stamp.token()));
        assertThat(timed.millis()).isLessThan(HOST_LIMIT_MILLIS);
        // and again with the program running
        HookJar.Timed second = jar.start(stdin, "claude");
        assertThat(second.run().err()).isEmpty();
        assertThat(second.millis()).isLessThan(HOST_LIMIT_MILLIS);
    }

    static Stream<Arguments> invalidArguments() {
        return Stream.of(
                Arguments.argumentSet("Claude", List.of("Claude")),
                Arguments.argumentSet("gemini", List.of("gemini")),
                Arguments.argumentSet("empty", List.of("")),
                Arguments.argumentSet("none", List.of()),
                Arguments.argumentSet("two", List.of("claude", "codex")),
                Arguments.argumentSet("CODEX", List.of("CODEX")),
                Arguments.argumentSet("leading space", List.of(" codex")),
                Arguments.argumentSet("session-key without operands", List.of("session-key")),
                Arguments.argumentSet("session-key with an author only", List.of("session-key", "claude:a")),
                Arguments.argumentSet("session-key with three operands",
                        List.of("session-key", "ledger", "claude:a", "claude:b")),
                Arguments.argumentSet("client with an author", List.of("claude", "claude:abc")),
                Arguments.argumentSet("Session-Key", List.of("Session-Key", "ledger", "claude:abc")));
    }

    static final String USAGE =
            "usage: java -jar ledger-client-hook.jar <claude|codex|antigravity>";

    @ParameterizedTest
    @MethodSource("invalidArguments")
    @DisplayName("HK-020 the argument must be exactly claude, codex or antigravity (or session-key with a server and an author): anything else exits 2 with the usage on stderr and nothing on stdout")
    void argumentIsAnEnum(List<String> arguments, HookJar jar, HookFixture fixture) throws IOException {
        CodexHost codex = new CodexHost();
        byte[] stdin = codex.event(codex.toolName("issue_save"), Json.of("{}"));
        String[] args = arguments.toArray(String[]::new);
        Run process = jar.run(stdin, args);
        Run inProcess = fixture.run(args, stdin);
        assertThat(List.of(process, inProcess)).allSatisfy(run -> {
            assertThat(run.exit()).isEqualTo(2);
            assertThat(run.stdout()).isEmpty();
            assertThat(run.err()).isEqualTo(USAGE + "\n");
        });
        assertThat(jar.keyDirectory()).as("no key is created for a usage error").doesNotExist();
    }

    @ParameterizedTest
    @MethodSource("org.agentledger.hook.Hosts#all")
    @DisplayName("HK-021 java -jar with nothing but the client jar and the resident program next to it (empty working directory, no CLASSPATH) runs on this JDK 25")
    void runsWithOnlyTheJar(HostScenario host, HookJar jar) throws IOException {
        assertThat(Runtime.version().feature()).isGreaterThanOrEqualTo(25);
        JsonNode input = Json.of("{}");
        Run run = jar.run(host.event(host.toolName("whoami"), input), host.client().argument());
        assertThat(run.err()).isEmpty();
        Attestations.Stamp stamp = jar.stamp(host, "whoami", input, run);
        assertThat(run.document()).isEqualTo(host.allowed(input, stamp.token()));
    }

    @Test
    @DisplayName("HK-021 on a JDK older than 25 the JVM refuses the client jar with UnsupportedClassVersionError (69.0), and the call is not answered (needs LEDGER_HOOK_OLDER_JAVA)")
    void olderJdkFailsClearly(HookJar jar) throws IOException {
        // The path of a JDK older than 25 is a property of the build machine, not of the product: the test reads it
        // from the environment of the machine and is skipped (NOT_RUN) where there is none.
        String olderJava = System.getenv("LEDGER_HOOK_OLDER_JAVA");
        Assumptions.assumeTrue(olderJava != null && !olderJava.isBlank(),
                "NOT_RUN: no JDK older than 25 on this host (set LEDGER_HOOK_OLDER_JAVA to its java executable)");
        CodexHost codex = new CodexHost();
        Run run = jar.start(Path.of(olderJava), List.of(), Map.of(), codex.event(codex.toolName("issue_save"), Json.of("{}")), "codex").run();
        assertThat(run.exit()).isNotZero();
        assertThat(run.stdout()).isEmpty();
        assertThat(run.err()).contains("UnsupportedClassVersionError").containsPattern("class file version 69\\.0");
    }

    /** Starts {@code starts} calls at once and returns the result of each. */
    private static List<HookJar.Timed> concurrentCalls(HookJar jar, int starts, byte[] stdin) throws Exception {
        CyclicBarrier ready = new CyclicBarrier(starts);
        try (var pool = Executors.newFixedThreadPool(starts)) {
            List<Future<HookJar.Timed>> futures = new ArrayList<>();
            for (int i = 0; i < starts; i++) {
                futures.add(pool.submit(() -> {
                    ready.await();
                    return jar.start(stdin, "codex");
                }));
            }
            List<HookJar.Timed> results = new ArrayList<>();
            for (Future<HookJar.Timed> future : futures) {
                results.add(future.get());
            }
            return results;
        }
    }

    @Test
    @DisplayName("REM-008 (D-0048 / BLOCKING-L4; changed by the user decision of 2026-11-22): 4 concurrent first calls, with no resident program running, start exactly one resident program, and each is answered with a valid attested answer inside the 5 s limit; 4 more concurrent calls are answered by the same program")
    void rem008_concurrentFirstCallsStartOneResidentProgramInsideTheLimit(HookJar jar) throws Exception {
        CodexHost codex = new CodexHost();
        byte[] stdin = codex.event(codex.toolName("issue_save"), KOREAN_INPUT);
        assertThat(Residents.startsOf(jar.home())).as("no resident program before the calls").isZero();

        for (HookJar.Timed timed : concurrentCalls(jar, 4, stdin)) {
            assertThat(timed.run().err()).isEmpty();
            assertThat(timed.run().exit()).isZero();
            assertThat(timed.run().json().path("hookSpecificOutput").path("updatedInput").path(Json.RESERVED_KEY).isString())
                    .as("a valid attested answer: %s", timed.run().out()).isTrue();
            assertThat(timed.millis()).isLessThan(HOST_LIMIT_MILLIS);
        }
        List<ResidentAddress> running = Residents.running(jar.home());
        assertThat(running).as("one resident program, found by its address file").hasSize(1);
        assertThat(Residents.startsOf(jar.home())).as("the four calls started one resident program, counted by the start line of its log").isEqualTo(1);

        for (HookJar.Timed timed : concurrentCalls(jar, 4, stdin)) {
            assertThat(timed.run().err()).isEmpty();
            assertThat(timed.run().exit()).isZero();
            assertThat(timed.millis()).isLessThan(HOST_LIMIT_MILLIS);
        }
        assertThat(Residents.running(jar.home())).as("the same program answered the later calls").isEqualTo(running);
        assertThat(Residents.startsOf(jar.home())).isEqualTo(1);
    }
}
