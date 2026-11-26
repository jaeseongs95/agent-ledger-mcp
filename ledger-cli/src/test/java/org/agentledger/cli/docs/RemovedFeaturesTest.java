// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.cli.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PCS-003, PCS-013: what the unit pcscope removed is gone from the main sources of every module and from the build, and the default
 * URL of {@code mcp-check} is the loopback one. Text scans of the repository, like the other tests of this package.
 */
class RemovedFeaturesTest {

    private static final List<String> MODULES = List.of("ledger-domain", "ledger-attestation", "ledger-application", "ledger-database",
            "ledger-seed", "ledger-cli", "ledger-mcp-server", "ledger-admin-web", "ledger-hook-client", "ledger-client-hook", "ledger-migrate-1x");

    @Test
    @DisplayName("PCS-003 no main source or resource of any module, no catalog entry and no build file names nginx or ACME, acme4j is in no build file, and the package of the TLS commands is gone")
    void pcs003_noProxyNoCertificateNoAcme() {
        for (String module : MODULES) {
            for (String suffix : List.of(".java", ".properties", ".yml", ".yaml", ".html", ".xml", ".sql", ".kts")) {
                String directory = module + "/src/main";
                if (!Repository.isDirectory(directory)) {
                    continue;
                }
                for (java.nio.file.Path file : Repository.files(directory, suffix)) {
                    assertThat(Repository.read(file).toLowerCase(Locale.ROOT)).as(file.toString()).doesNotContain("nginx")
                            .doesNotContain("acme");
                }
            }
            if (Repository.exists(module + "/build.gradle.kts")) {
                assertThat(Repository.read(module + "/build.gradle.kts").toLowerCase(Locale.ROOT)).as(module + " build")
                        .doesNotContain("acme");
            }
        }
        assertThat(Repository.read("gradle/libs.versions.toml").toLowerCase(Locale.ROOT)).doesNotContain("acme4j").doesNotContain("acme");
        assertThat(Repository.read("build.gradle.kts").toLowerCase(Locale.ROOT)).doesNotContain("acme");
        assertThat(Repository.exists("ledger-cli/src/main/java/org/agentledger/cli/tls")).isFalse();
        assertThat(Repository.exists("ledger-cli/src/main/java/org/agentledger/cli/deploy/NginxSite.java")).isFalse();
    }

    @Test
    @DisplayName("PCS-013 the default URL of mcp-check is the loopback address with the MCP port of the install defaults (the text of McpCheckCommand); the server test McpCheckTest (CLI-040) runs it against a server")
    void pcs013_theDefaultUrlIsLoopback() {
        String source = Repository.read("ledger-cli/src/main/java/org/agentledger/cli/mcp/McpCheckCommand.java");

        assertThat(source).contains("\"http://127.0.0.1:\" + defaults.mcpPort() + DEFAULT_PATH");
    }
}
