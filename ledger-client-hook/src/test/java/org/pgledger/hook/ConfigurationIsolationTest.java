package org.pgledger.hook;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * HK-055: the settings of the hook come from the jar, the environment and the system properties, never from the working
 * directory. A host starts the hook in the directory of the project the user works on, which may be a clone of someone
 * else's repository with an {@code application.properties} or a {@code config/application.properties} of its own (Spring
 * Boot reads {@code ./} and {@code ./config/} by default).
 */
@ExtendWith(HookExtension.class)
class ConfigurationIsolationTest {

    /** What a project could carry to hurt: output on stdout, a key owner nobody is, another key folder. */
    private static final String HOSTILE = "spring.main.banner-mode=console\nspring.main.log-startup-info=true\n"
            + "logging.level.root=DEBUG\nledger.hook.user-name=nobody-by-this-name\nledger.hook.key-dir=hostile-keys\n";

    private static final Map<String, String> PROJECT_FILES = Map.of(
            "application.properties", HOSTILE,
            "config/application.properties", HOSTILE,
            "config/application.yml", "spring:\n  main:\n    banner-mode: console\n");

    @Test
    @DisplayName("HK-055 real jar started in a directory with application.properties files of its own: not one byte on stdout and none on stderr for empty input")
    void emptyInputInAProjectDirectory(HookJar jar) throws IOException {
        Run run = jar.start(HookJar.JAVA, List.of(), Map.of(), PROJECT_FILES, new byte[0], "claude").run();
        assertThat(run.stdout()).as("stdout bytes").isEmpty();
        assertThat(run.stderr()).as("stderr bytes").isEmpty();
        assertThat(run.exit()).isZero();
    }

    @Test
    @DisplayName("HK-055 real jar in a project directory: session-key still prints its four lines for the real account, and the key is made in the folder of the environment variable")
    void settingsOfTheProjectAreNotUsed(HookJar jar) throws IOException {
        Run run = jar.start(HookJar.JAVA, List.of(), Map.of(), PROJECT_FILES, new byte[0], "session-key", "ledger", "claude:project-1")
                .run();
        assertThat(run.stderr()).as("stderr bytes").isEmpty();
        assertThat(run.exit()).isZero();
        assertThat(run.out()).matches("server: ledger\nauthor: claude:project-1\npublic-key: [A-Za-z0-9+/=]+\nfingerprint: [0-9a-f]{64}\n");
        assertThat(jar.keyDirectory().resolve(Attestations.keyFileName("ledger", "claude:project-1"))).isRegularFile();
        assertThat(jar.workingDirectories()).allSatisfy(directory -> assertThat(directory.resolve("hostile-keys")).doesNotExist());
    }
}
