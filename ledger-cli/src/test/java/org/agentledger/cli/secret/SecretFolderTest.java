// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.cli.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.agentledger.cli.ErrorCode;
import org.agentledger.cli.FailureException;

/**
 * SPL-001 (defect D-0031): the one place that says which secret belongs in which folder. The names are the properties that the
 * processes read (the table of TEST_CASES section 46.2).
 */
class SecretFolderTest {

    static Stream<Arguments> routed() {
        return Stream.of(
                Arguments.of("ledger.jdbc.password", SecretFolder.COMMON),
                Arguments.of("ledger.mcp.token", SecretFolder.MCP),
                Arguments.of("ledger.admin.token", SecretFolder.ADMIN),
                Arguments.of("ledger.admin.agent-token", SecretFolder.ADMIN),
                Arguments.of("spring.security.oauth2.client.registration.google.client-secret", SecretFolder.ADMIN),
                Arguments.of("spring.security.oauth2.client.registration.second.client-secret", SecretFolder.ADMIN),
                Arguments.of("ledger.import.source.password", SecretFolder.TOOL));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("routed")
    @DisplayName("SPL-001 each secret of the product has its folder: the database password is common, the token of the agents is the MCP server's, the admin secrets (also the OpenID Connect client secret) the admin web's, the 1.x source password the tool's")
    void spl001_routedNames(String name, SecretFolder folder) {
        assertThat(SecretFolder.of(new SecretName(name))).isEqualTo(folder);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"cloudflare-api-token", "ledger.mcp", "ledger.administrator", "ledger.admin.", "ledger.admin.oidc-secret",
        "ledger.admin.tokens", "ledger.admin.agent-token.x", "ledger.jdbc.passwords",
        "ledger.mcp.token.x", "ledger.import.source.username", "spring.security.oauth2.client.registration.google.client-id",
        "spring.security.oauth2.client.registration..client-secret", "common", "tool"})
    @DisplayName("SPL-001 a name that is no secret of the product is UNKNOWN_SECRET_NAME, and the name is not echoed")
    void spl001_unknownNames(String name) {
        assertThatThrownBy(() -> SecretFolder.of(new SecretName(name))).isInstanceOfSatisfying(FailureException.class, failure -> {
            assertThat(failure.code()).isEqualTo(ErrorCode.UNKNOWN_SECRET_NAME);
            assertThat(failure.arguments()).isEmpty();
        });
    }

    @Test
    @DisplayName("SPL-001 the folders are common, admin, mcp and tool, in the order the installer makes them, and each name is a plain folder name")
    void spl001_theFolders() {
        assertThat(SecretFolder.all()).containsExactly(SecretFolder.COMMON, SecretFolder.ADMIN, SecretFolder.MCP, SecretFolder.TOOL);
        assertThat(SecretFolder.all().stream().map(SecretFolder::directory).toList())
                .isEqualTo(List.of("common", "admin", "mcp", "tool"));
    }
}
