// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.hook;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * HK-052: the hook logs with SLF4J (Spring Boot's Logback), to stderr and from the level WARN on, so a call that goes well
 * leaves stderr empty (HK-016) and a log line, when the level asks for it, never lands on stdout, which is the answer to
 * the host.
 */
@ExtendWith(HookExtension.class)
class LoggingTest {

    private static final byte[] MALFORMED = "{".getBytes(StandardCharsets.UTF_8);

    @ParameterizedTest
    @MethodSource("org.pgledger.hook.Hosts#all")
    @DisplayName("HK-052 real jar at the DEBUG level of the hook's loggers: the log line of malformed input is on stderr, the answer on stdout and the exit code are those of HK-008")
    void debugLineGoesToStderr(HostScenario host, HookJar jar) throws IOException {
        Run run = jar.start(HookJar.JAVA, List.of("-Dlogging.level.org.pgledger.hook=DEBUG"), Map.of(), MALFORMED,
                host.client().argument()).run();
        HostScenario.Malformed expected = host.malformedInput();
        assertThat(run.exit()).as("exit code").isEqualTo(expected.exit());
        assertThat(run.document()).as("stdout").isEqualTo(expected.stdout());
        assertThat(run.err()).as("stderr").contains(expected.stderr()).contains("DEBUG")
                .contains("the hook input is not valid JSON");
    }

    @ParameterizedTest
    @MethodSource("org.pgledger.hook.Hosts#all")
    @DisplayName("HK-052 real jar at the default level: the same malformed input leaves nothing but the answer of HK-008 on stderr (no log line)")
    void defaultLevelLogsNothing(HostScenario host, HookJar jar) throws IOException {
        Run run = jar.run(MALFORMED, host.client().argument());
        assertThat(run.err()).isEqualTo(host.malformedInput().stderr());
    }

    @ParameterizedTest
    @MethodSource("org.pgledger.hook.Hosts#all")
    @DisplayName("HK-052 stdin that cannot be read: exit 2 and nothing written to the host's streams (the failure is logged at ERROR, not printed)")
    void unreadableStdinIsExitTwo(HostScenario host, HookFixture fixture) {
        InputStream broken = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("stdin is gone");
            }
        };
        Run run = fixture.run(new String[] {host.client().argument()}, broken);
        assertThat(run.exit()).isEqualTo(2);
        assertThat(run.stdout()).isEmpty();
        assertThat(run.stderr()).isEmpty();
    }

    @Test
    @DisplayName("HK-052 the logging configuration sends the log to stderr and not below WARN")
    void configurationTargetsStderr() throws IOException {
        String configuration = Files.readString(Path.of("src", "main", "resources", "logback-spring.xml"));
        assertThat(configuration).contains("<target>System.err</target>").contains("<root level=\"WARN\">")
                .doesNotContain("System.out");
    }
}
