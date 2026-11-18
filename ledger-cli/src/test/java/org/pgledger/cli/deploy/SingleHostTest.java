// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.cli.deploy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.UsageException;

/**
 * PCS-020 to PCS-022: {@code --mcp-host} and {@code --admin-host} are one host name each (the name the operator's reverse proxy
 * passes in {@code Host}); the loopback names are always allowed; no list, no site and no renewal unit exist (PCS-002).
 */
class SingleHostTest {

    private static Map<String, String> plain() {
        Map<String, String> values = new HashMap<>();
        values.put(DeployOptions.JAVA, "/usr/lib/jvm/java-25/bin/java");
        values.put(DeployOptions.JDBC_URL, "jdbc:postgresql://db.example.org:5432/ledger");
        values.put(DeployOptions.APP_ACCOUNT, "ledger_app");
        return values;
    }

    private static String file(List<RenderedFile> files, String path) {
        return files.stream().filter(f -> f.path().equals(path)).map(RenderedFile::content).findFirst().orElseThrow();
    }

    private static final String LOOPBACK = "    allowed-hosts:\n      - '127.0.0.1'\n      - 'localhost'\n";

    @Test
    @DisplayName("PCS-021 no public name: the allowed hosts of both services are the two loopback names, and no passkey block is written")
    void pcs021_noName() {
        List<RenderedFile> files = DeployTestBeans.render(plain());

        assertThat(file(files, "/etc/pg-ledger-mcp/mcp.yaml")).contains(LOOPBACK).doesNotContain("example.org");
        assertThat(file(files, "/etc/pg-ledger-mcp/admin.yaml")).contains(LOOPBACK + "    driver-file:")
                .doesNotContain("passkey:");
    }

    @Test
    @DisplayName("PCS-021 one name for each service: the loopback names first, then that name once; the name of the other service is never in the settings of a service")
    void pcs021_oneNameForEachService() {
        Map<String, String> values = plain();
        values.put(DeployOptions.MCP_HOST, "mcp.example.org");
        values.put(DeployOptions.ADMIN_HOST, "admin.example.org");
        List<RenderedFile> files = DeployTestBeans.render(values);

        assertThat(file(files, "/etc/pg-ledger-mcp/mcp.yaml")).contains(LOOPBACK + "      - 'mcp.example.org'\n")
                .doesNotContain("admin.example.org");
        assertThat(file(files, "/etc/pg-ledger-mcp/admin.yaml")).contains(LOOPBACK + "      - 'admin.example.org'\n")
                .doesNotContain("mcp.example.org");
    }

    @Test
    @DisplayName("PCS-021 --mcp-host without --admin-host: the MCP server allows its name, the admin web keeps the loopback names")
    void pcs021_theMcpNameAlone() {
        Map<String, String> values = plain();
        values.put(DeployOptions.MCP_HOST, "mcp.example.org");
        List<RenderedFile> files = DeployTestBeans.render(values);

        assertThat(file(files, "/etc/pg-ledger-mcp/mcp.yaml")).contains(LOOPBACK + "      - 'mcp.example.org'\n");
        assertThat(file(files, "/etc/pg-ledger-mcp/admin.yaml")).contains(LOOPBACK + "    driver-file:");
    }

    @Test
    @DisplayName("PCS-022 --admin-host: the relying party id of the passkeys is the name and the one origin is https:// that name")
    void pcs022_thePasskeyOfTheAdminName() {
        Map<String, String> values = plain();
        values.put(DeployOptions.ADMIN_HOST, "admin.example.org");

        assertThat(file(DeployTestBeans.render(values), "/etc/pg-ledger-mcp/admin.yaml")).contains(
                "    passkey:\n      rp-id: 'admin.example.org'\n      origins:\n        - 'https://admin.example.org'\n");
    }

    @Test
    @DisplayName("PCS-002 the files are the two service definitions and the three settings files: no nginx site and no renewal unit or timer, for either name option")
    void pcs002_noSiteAndNoRenewal() {
        Map<String, String> values = plain();
        values.put(DeployOptions.MCP_HOST, "mcp.example.org");
        values.put(DeployOptions.ADMIN_HOST, "admin.example.org");

        assertThat(DeployTestBeans.render(values)).extracting(RenderedFile::path).containsExactly(
                "/etc/systemd/system/pg-ledger-mcp.service", "/etc/systemd/system/pg-ledger-mcp-admin.service",
                "/etc/pg-ledger-mcp/application.yaml", "/etc/pg-ledger-mcp/mcp.yaml", "/etc/pg-ledger-mcp/admin.yaml");
    }

    static Stream<Arguments> badNames() {
        return Stream.of(
                Arguments.of(DeployOptions.MCP_HOST, "a.example.org,b.example.org"), Arguments.of(DeployOptions.MCP_HOST, "a.example.org,"),
                Arguments.of(DeployOptions.MCP_HOST, ",a.example.org"), Arguments.of(DeployOptions.MCP_HOST, "a.example.org,,b.example.org"),
                Arguments.of(DeployOptions.MCP_HOST, ""), Arguments.of(DeployOptions.MCP_HOST, "A.example.org"),
                Arguments.of(DeployOptions.MCP_HOST, "a b"), Arguments.of(DeployOptions.MCP_HOST, "a.example.org:443"),
                Arguments.of(DeployOptions.MCP_HOST, "x".repeat(254)), Arguments.of(DeployOptions.MCP_HOST, "a$b.example.org"),
                Arguments.of(DeployOptions.MCP_HOST, "a%b.example.org"),
                Arguments.of(DeployOptions.ADMIN_HOST, "admin.example.org,a.example.org"), Arguments.of(DeployOptions.ADMIN_HOST, ""),
                Arguments.of(DeployOptions.ADMIN_HOST, "admin.example.org,"),
                // the same name in both options: a name is served by one of the services
                Arguments.of(DeployOptions.ADMIN_HOST, "mcp.example.org"));
    }

    @ParameterizedTest
    @MethodSource("badNames")
    @DisplayName("PCS-020 a comma list, an empty entry or value, a name that is not plain, a name longer than 253 characters and a name of both options are refused naming the option, never the value")
    void pcs020_badNames(String option, String value) {
        Map<String, String> values = plain();
        values.put(DeployOptions.MCP_HOST, "mcp.example.org");
        values.put(DeployOptions.ADMIN_HOST, "admin.example.org");
        values.put(option, value);

        assertThatThrownBy(() -> DeployTestBeans.parameters(values)).isInstanceOfSatisfying(UsageException.class, e -> {
            assertThat(e.code()).isEqualTo(ErrorCode.INVALID_DEPLOY_VALUE);
            assertThat(e.arguments()).containsExactly(option);
        });
    }

    @Test
    @DisplayName("PCS-002 the options of the site and of the certificate are no options of the installer commands any more")
    void pcs002_noSiteOptions() {
        assertThat(DeployOptions.NAMES).doesNotContain("nginx-conf", "tls-certificate", "tls-key", "tls-acme", "acme-directory");
        assertThat(PrepareHostCommand.OPTIONS).doesNotContain("nginx-conf", "tls-certificate", "tls-key", "tls-acme", "acme-directory");
        assertThat(RenderDeployCommand.OPTIONS).doesNotContain("nginx-conf", "tls-certificate", "tls-key", "tls-acme", "acme-directory");
    }
}
