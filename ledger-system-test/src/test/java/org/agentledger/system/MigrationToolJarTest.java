// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.system;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * M1X-002 and M1X-041 (docs/testing/TEST_CASES.md section 43): the packaged migration tool {@code ledger-migrate-1x.jar} exists and
 * starts as a separate Java process, and the jars of the 2.0 services hold nothing of it and nothing named after 1.x. The commands
 * that need a database are tested in process (CLI-044, RET-007 ..); here the commands that need none run on the jar itself.
 */
class MigrationToolJarTest {

    private static final int PROCESS_SECONDS = 120;
    private static final String TOOL_MAIN = "BOOT-INF/classes/org/agentledger/migrate1x/Migrate1xApplication.class";
    /** A class named after 1.x, whatever the case: the body has none (the tool's own classes are under migrate1x). */
    private static final Pattern ONE_X_CLASS = Pattern.compile(".*(?:(?i:legacy|1x)|OneX|ONE_X).*\\.class");
    private static final String TOKEN = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private static Path jar(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("the test property " + property + " is not set (ledger-system-test/build.gradle.kts passes it)");
        }
        return Path.of(value);
    }

    /** Every entry name of the jar and of the jars of the product modules inside it (BOOT-INF/lib/ledger-*.jar). */
    private static List<String> entries(Path outer) throws IOException {
        List<String> names = new ArrayList<>();
        try (JarFile jar = new JarFile(outer.toFile())) {
            for (JarEntry entry : jar.stream().toList()) {
                names.add(entry.getName());
                if (entry.getName().startsWith("BOOT-INF/lib/ledger-") && entry.getName().endsWith(".jar")) {
                    try (JarInputStream nested = new JarInputStream(jar.getInputStream(entry))) {
                        for (JarEntry inner = nested.getNextJarEntry(); inner != null; inner = nested.getNextJarEntry()) {
                            names.add(entry.getName() + "!" + inner.getName());
                        }
                    }
                }
            }
        }
        return names;
    }

    @Test
    @DisplayName("M1X-002 the tool jar exists and holds its main class and none of the classes of the three applications; the jars of the three applications hold no class of the tool and no class named after 1.x")
    void m1x002_theJarsAreSeparate() throws IOException {
        Path tool = jar("ledger.migrate.jar");
        assertThat(tool).as("the jar of ledger-migrate-1x").isRegularFile();
        List<String> toolEntries = entries(tool);
        assertThat(toolEntries).contains(TOOL_MAIN);
        assertThat(toolEntries).noneMatch(name -> name.startsWith("BOOT-INF/classes/org/agentledger/cli/")
                || name.startsWith("BOOT-INF/classes/org/agentledger/mcp/") || name.startsWith("BOOT-INF/classes/org/agentledger/admin/"));
        for (String property : List.of("ledger.cli.jar", "ledger.mcp.jar", "ledger.admin.jar")) {
            List<String> names = entries(jar(property));
            assertThat(names).as(property + " has no class of the tool")
                    .noneMatch(name -> name.contains("org/agentledger/migrate1x/"));
            assertThat(names).as(property + " has no class named after 1.x").noneMatch(name -> ONE_X_CLASS.matcher(name).matches());
        }
    }

    @Test
    @DisplayName("M1X-041 the tool jar as a process: no command is exit 2 with the usage of the four commands on the standard error; stop-1x on a directory without units is exit 1 and starts no program; import-secrets-1x --stdin stores the two secrets without printing a value and does not import the admin token")
    void m1x041_theToolRunsAsAProcess(@TempDir Path work) throws IOException {
        Path tool = jar("ledger.migrate.jar");
        Path home = Files.createDirectories(work.resolve("etc"));
        String instance = "--ledger.secrets.home=" + home.toString().replace('\\', '/');

        JavaProcess.Result none = JavaProcess.run(work, Map.of(), null, PROCESS_SECONDS, tool, instance);
        assertThat(none.exit()).as(none.streams()).isEqualTo(2);
        assertThat(none.err()).contains("Usage: java -jar ledger-migrate-1x.jar").contains("stop-1x").contains("import-secrets-1x")
                .contains("import-1x").contains("retire-1x");

        Path units = Files.createDirectories(work.resolve("units"));
        JavaProcess.Result stop = JavaProcess.run(work, Map.of(), null, PROCESS_SECONDS, tool, instance, "stop-1x",
                "--unit-dir=" + units.toString().replace('\\', '/'));
        assertThat(stop.exit()).as(stop.streams()).isEqualTo(1);
        assertThat(stop.err()).contains("No 1.x service was found");

        String environment = "DBHUB_AUTH_TOKEN=" + TOKEN + "\nPGLEDGER_ADMIN_TOKEN=" + TOKEN.replace('0', 'f') + "\n";
        JavaProcess.Result secrets = JavaProcess.run(work, Map.of(), environment, PROCESS_SECONDS, tool, instance,
                "import-secrets-1x", "--stdin");
        assertThat(secrets.exit()).as(secrets.streams()).isZero();
        assertThat(secrets.out()).contains("ledger.mcp.token").contains("ledger.admin.agent-token").contains("ledger.admin.token")
                .contains("was not imported").doesNotContain(TOKEN).doesNotContain(TOKEN.replace('0', 'f'));
        assertThat(secrets.err()).doesNotContain(TOKEN);
        assertThat(Files.readString(home.resolve("secrets").resolve("mcp").resolve("ledger.mcp.token"))).isEqualTo(TOKEN);
        assertThat(Files.readString(home.resolve("secrets").resolve("admin").resolve("ledger.admin.agent-token"))).isEqualTo(TOKEN);
        // LGN-015: the admin token of 1.x is not imported (no installer ran here, so the file does not exist at all)
        assertThat(home.resolve("secrets").resolve("admin").resolve("ledger.admin.token")).doesNotExist();
    }
}
