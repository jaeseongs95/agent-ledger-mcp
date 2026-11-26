// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.hook;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import tools.jackson.databind.JsonNode;

/**
 * HK-055: the settings of the hook come from the jar, the environment and the system properties, never from the working
 * directory. A host starts the hook in the directory of the project the user works on, which may be a clone of someone
 * else's repository with an {@code application.properties} or a {@code config/application.properties} of its own.
 */
@ExtendWith(HookExtension.class)
class ConfigurationIsolationTest {

    private static final String HOSTILE = "spring.main.banner-mode=console\nspring.main.log-startup-info=true\n"
            + "logging.level.root=DEBUG\nledger.hook.user-name=nobody-by-this-name\n";

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
    @DisplayName("HK-055 real jar in a project directory: stamping still outputs clean JSON on stdout with zero banner/log contamination")
    void settingsOfTheProjectAreNotUsed(HookJar jar) throws IOException {
        ClaudeHost claude = new ClaudeHost();
        JsonNode input = Json.of("{\"note\":\"isolated\"}");
        Run run = jar.start(HookJar.JAVA, List.of(), Map.of(), PROJECT_FILES,
                claude.event(claude.toolName("issue_save"), input), "claude").run();
        assertThat(run.stderr()).as("stderr bytes").isEmpty();
        assertThat(run.exit()).isZero();
        Attestations.Stamp stamp = jar.stamp(claude, "issue_save", input, run);
        assertThat(run.document()).isEqualTo(claude.allowed(input, stamp.token()));
    }
}
