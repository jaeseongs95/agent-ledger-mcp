// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.cli.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.modelcontextprotocol.client.transport.McpHttpClientTransportAuthorizationException;
import java.io.IOException;
import java.net.URI;
import java.nio.channels.UnresolvedAddressException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.veridger.cli.CliCommand;
import org.veridger.cli.ErrorCode;
import org.veridger.cli.FailureException;
import org.veridger.cli.UsageException;
import org.veridger.cli.seed.InstallDefaults;
import org.veridger.cli.seed.PackagedSeed;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unit tests for {@link McpCheckCommand} and {@link McpCheckProperties} covering URL validation, token redaction,
 * and exception-chain translation without leaking bearer tokens.
 */
class McpCheckCommandUnitTest {

    private static McpCheckCommand command(String token, List<String> lines) {
        return new McpCheckCommand(new McpCheckProperties(token), new InstallDefaults(new PackagedSeed()),
                JsonMapper.builder().build(), lines::add, null);
    }

    @Test
    @DisplayName("MCHK-01 McpCheckProperties hides token in toString and command validates metadata, operands, and token")
    void propertiesRedactionAndBasicValidation() {
        McpCheckProperties props = new McpCheckProperties("super-secret-bearer-token");
        assertThat(props.toString()).doesNotContain("super-secret-bearer-token").contains("hidden");

        List<String> lines = new ArrayList<>();
        McpCheckCommand missingToken = command("   ", lines);
        assertThat(missingToken.name()).isEqualTo(CliCommand.MCP_CHECK);
        assertThat(missingToken.options()).containsExactly("url");

        assertThatThrownBy(() -> missingToken.run(List.of("extra"), new DefaultApplicationArguments()))
                .isInstanceOfSatisfying(UsageException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.TOO_MANY_ARGUMENTS));
        assertThatThrownBy(() -> missingToken.run(List.of(), new DefaultApplicationArguments()))
                .isInstanceOfSatisfying(FailureException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.MCP_TOKEN_MISSING));
        assertThatThrownBy(missingToken::requireToken)
                .isInstanceOfSatisfying(FailureException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.MCP_TOKEN_MISSING));
        assertThatThrownBy(() -> command(null, lines).requireToken())
                .isInstanceOfSatisfying(FailureException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.MCP_TOKEN_MISSING));

        McpCheckCommand valid = command("valid-token", lines);
        assertThat(valid.requireToken()).isEqualTo("valid-token");
    }

    @Test
    @DisplayName("MCHK-02 resolveEndpoint and shown validate schemes, hosts, secrets in URI, and default /mcp path")
    void endpointValidationAndFormatting() {
        McpCheckCommand cmd = command("tok", new ArrayList<>());

        URI defaultUri = cmd.resolveEndpoint(null);
        assertThat(defaultUri.getScheme()).isEqualTo("http");
        assertThat(cmd.shown(defaultUri)).endsWith("/mcp");

        URI httpsRoot = cmd.resolveEndpoint("https://example.test/");
        assertThat(cmd.shown(httpsRoot)).isEqualTo("https://example.test/mcp");
        URI httpsNoPath = cmd.resolveEndpoint("https://example.test");
        assertThat(cmd.shown(httpsNoPath)).isEqualTo("https://example.test/mcp");
        URI customPath = cmd.resolveEndpoint("http://127.0.0.1:9999/custom");
        assertThat(cmd.shown(customPath)).isEqualTo("http://127.0.0.1:9999/custom");

        for (String bad : List.of(
                "://not-a-uri",
                "ftp://example.test/mcp",
                "http:///no-host",
                "http://user:pass@127.0.0.1:8080/mcp",
                "http://127.0.0.1:8080/mcp?token=leak",
                "http://127.0.0.1:8080/mcp#frag")) {
            assertThatThrownBy(() -> cmd.resolveEndpoint(bad))
                    .as("invalid URL: %s", bad)
                    .isInstanceOfSatisfying(UsageException.class,
                            e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_OPTION_VALUE));
        }
    }

    @Test
    @DisplayName("MCHK-03 callTool translates authorization, unreachable, and generic failures without leaking messages")
    void callToolExceptionTranslation() {
        McpCheckCommand cmd = command("tok", new ArrayList<>());
        String shown = "http://127.0.0.1:8080/mcp";

        assertThat(cmd.callTool(null, shown, () -> "ok")).isEqualTo("ok");

        assertThatThrownBy(() -> cmd.callTool(null, shown, () -> {
            throw new RuntimeException("outer Bearer secret-xyz",
                    new McpHttpClientTransportAuthorizationException("401 Bearer secret-xyz", null, null));
        })).isInstanceOfSatisfying(FailureException.class, e -> {
            assertThat(e.code()).isEqualTo(ErrorCode.MCP_AUTHORIZATION_REFUSED);
            assertThat(e.getCause()).isNull();
            assertThat(e.arguments()).doesNotContain("secret-xyz");
        });

        assertThatThrownBy(() -> cmd.callTool(null, shown, () -> {
            throw new RuntimeException("outer", new IOException("connection refused"));
        })).isInstanceOfSatisfying(FailureException.class, e -> {
            assertThat(e.code()).isEqualTo(ErrorCode.MCP_UNREACHABLE);
            assertThat(e.getCause()).isNull();
        });

        assertThatThrownBy(() -> cmd.callTool(null, shown, () -> {
            throw new RuntimeException("outer", new UnresolvedAddressException());
        })).isInstanceOfSatisfying(FailureException.class, e -> {
            assertThat(e.code()).isEqualTo(ErrorCode.MCP_UNREACHABLE);
            assertThat(e.getCause()).isNull();
        });

        assertThatThrownBy(() -> cmd.callTool(null, shown, () -> {
            throw new IllegalStateException("Bearer secret-must-not-appear");
        })).isInstanceOfSatisfying(FailureException.class, e -> {
            assertThat(e.code()).isEqualTo(ErrorCode.MCP_FAILED);
            assertThat(e.getCause()).isNull();
            assertThat(e.arguments()).containsExactly(shown, "IllegalStateException");
        });
    }
}
