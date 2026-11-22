// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.system;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R9-D0095-A (docs/testing/TEST_CASES.md): the programs that reach the one class that calls the operating system (the names of the
 * well-known accounts of Windows, found by their SID with the foreign function API) allow native access in the manifest of their
 * executable jar, so that the JVM prints no warning, and the program that does not reach it does not. On a Windows host the packaged
 * command line tool really makes the call (it asks for the service account to protect the secret it stores): nothing is added to its
 * standard output or standard error.
 */
class NativeAccessJarTest {

    private static final int PROCESS_SECONDS = 120;
    private static final String ATTRIBUTE = "Enable-Native-Access";

    private static Path jar(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("the test property " + property + " is not set (ledger-system-test/build.gradle.kts passes it)");
        }
        return Path.of(value);
    }

    private static String attribute(Path jar) throws IOException {
        try (JarFile file = new JarFile(jar.toFile())) {
            return file.getManifest().getMainAttributes().getValue(ATTRIBUTE);
        }
    }

    @Test
    @DisplayName("R9-D0095-A the jars of the command line tool, the MCP server, the admin web and the migration tool carry Enable-Native-Access: ALL-UNNAMED; the jar of the hook, which reaches no account of Windows, does not")
    void theManifestsAllowNativeAccessWhereTheCallIs() throws IOException {
        for (String property : new String[] { "ledger.cli.jar", "ledger.mcp.jar", "ledger.admin.jar", "ledger.migrate.jar" }) {
            assertThat(attribute(jar(property))).as(property).isEqualTo("ALL-UNNAMED");
        }
        assertThat(attribute(jar("ledger.hook.jar"))).as("the hook").isNull();
    }

    @Test
    @DisplayName("R9-D0095-A the packaged command line tool stores a secret with nothing on its standard error and nothing but its own message on its standard output (on Windows it asks the operating system for the service account to protect the secret)")
    void theToolPrintsNoWarning(@TempDir Path work) throws IOException {
        Path home = Files.createDirectory(work.resolve("home"));
        String secret = "native-access-probe-0123456789";

        JavaProcess.Result result = JavaProcess.run(work, java.util.Map.of(), secret + "\n", PROCESS_SECONDS, jar("ledger.cli.jar"),
                "secret-set", "ledger.jdbc.password", "--stdin", "--ledger.secrets.home=" + home);

        assertThat(result.exit()).as(result.streams()).isZero();
        assertThat(result.err()).as("no warning of the JVM about a restricted method").doesNotContain("WARNING").doesNotContain("restricted")
                .isEmpty();
        assertThat(result.out()).doesNotContain(secret).doesNotContain("WARNING");
        assertThat(home.resolve("secrets/common/ledger.jdbc.password")).isRegularFile();
    }
}
