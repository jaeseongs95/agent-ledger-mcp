// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.hook;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/**
 * HK-050: the real {@code main} of the jar, started as a process: the start of Spring Boot writes nothing on stdout (no
 * banner, no startup line, no log line), so the bytes on stdout are the answer to the host and nothing else, and a word of
 * the command line is never a Boot setting.
 */
@ExtendWith(HookExtension.class)
class StartupOutputTest {

    private static final byte[] NOTHING = new byte[0];

    @ParameterizedTest
    @MethodSource("org.agentledger.hook.Hosts#all")
    @DisplayName("HK-050 real main: empty input gives not one byte on stdout and none on stderr, exit 0, for each host")
    void noOutputForEmptyInput(HostScenario host, HookJar jar) throws IOException {
        Run run = jar.run(NOTHING, host.client().argument());
        assertThat(run.stdout()).as("stdout bytes").isEmpty();
        assertThat(run.stderr()).as("stderr bytes").isEmpty();
        assertThat(run.exit()).isZero();
    }

    @ParameterizedTest
    @MethodSource("org.agentledger.hook.Hosts#all")
    @DisplayName("HK-050 real main: a stamped answer on stdout is one JSON document and its line feed, from the first byte to the last")
    void stdoutIsTheAnswerAlone(HostScenario host, HookJar jar) throws IOException {
        JsonNode input = Json.of("{\"note\":\"startup\"}");
        Run run = jar.run(host.event(host.toolName("issue_save"), input), host.client().argument());
        assertThat(run.stderr()).as("stderr bytes").isEmpty();
        assertThat(run.exit()).isZero();
        byte[] stdout = run.stdout();
        assertThat(stdout).isNotEmpty();
        assertThat(stdout[0]).as("first byte").isEqualTo((byte) '{');
        assertThat(stdout[stdout.length - 1]).as("last byte").isEqualTo((byte) '\n');
        assertThat(run.out().chars().filter(c -> c == '\n').count()).as("line feeds").isEqualTo(1);
        // the whole of stdout is one JSON document (a banner or a log line in front of it would not parse)
        assertThat(Json.MAPPER.readTree(stdout).isObject()).isTrue();
    }

    @Test
    @DisplayName("HK-050 real main: session-key prints exactly its four lines and nothing before or after them")
    void sessionKeyIsFourLinesAlone(HookJar jar) throws IOException {
        Run run = jar.run(NOTHING, "session-key", "ledger", "claude:startup-1");
        assertThat(run.stderr()).as("stderr bytes").isEmpty();
        assertThat(run.exit()).isZero();
        assertThat(run.out()).matches("server: ledger\nauthor: claude:startup-1\npublic-key: [A-Za-z0-9+/=]+\nfingerprint: [0-9a-f]{64}\n");
    }

    @ParameterizedTest
    @ValueSource(strings = {"--debug", "--trace", "--spring.main.banner-mode=console", "--spring.main.log-startup-info=true",
            "--logging.level.root=DEBUG", "--spring.profiles.active=other"})
    @DisplayName("HK-050 real main: a word that Spring Boot would take as a setting is an unknown word of the hook: usage on stderr, exit 2, nothing on stdout")
    void commandLineWordsAreNeverSettings(String word, HookJar jar) throws IOException {
        Run run = jar.run(NOTHING, word);
        assertThat(run.stdout()).as("stdout bytes").isEmpty();
        assertThat(run.err()).isEqualTo(JarProcessTest.USAGE + "\n");
        assertThat(run.exit()).isEqualTo(2);
    }

    @ParameterizedTest
    @MethodSource("org.agentledger.hook.Hosts#all")
    @DisplayName("HK-050 real main (changed by the user decision of 2026-11-22): with the DEBUG level of Boot's own logging asked for (DEBUG=true, LOGGING_LEVEL_ROOT=DEBUG in the environment of the resident program), stdout and stderr of the call still hold only the answer (the log goes to the log file of the state folder)")
    void loggingNeverReachesStdout(HostScenario host, HookJar jar) throws IOException {
        Run run = jar.start(HookJar.JAVA, List.of(), Map.of("DEBUG", "true", "LOGGING_LEVEL_ROOT", "DEBUG"), NOTHING,
                host.client().argument()).run();
        assertThat(run.stdout()).as("stdout bytes").isEmpty();
        assertThat(run.stderr()).as("stderr bytes").isEmpty();
        assertThat(run.exit()).isZero();
        assertThat(java.nio.file.Files.readString(Residents.stateFolders(jar.home()).getFirst().resolve("resident.log")))
                .as("the log of the resident program").contains("DEBUG");
    }
}
