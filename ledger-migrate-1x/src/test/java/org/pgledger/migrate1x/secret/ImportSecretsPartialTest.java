package org.pgledger.migrate1x.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.cli.CliMessages;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.FailureException;
import org.pgledger.cli.secret.SecretName;
import org.pgledger.cli.secret.SecretVault;
import org.pgledger.cli.secret.StandardInput;
import org.pgledger.migrate1x.support.RecordingOutput;
import org.pgledger.migrate1x.tool.MigrationError;
import org.springframework.boot.DefaultApplicationArguments;

/**
 * M1X-025 (review B7): {@code import-secrets-1x --replace} writes nothing until every value is checked and every secret path was
 * looked at, and when a later write fails after an earlier one succeeded, the refusal says which secrets are stored and that the
 * command is to be run again: the two tokens of the agents are never left apart without a word. The store is played by a mock
 * (the real one cannot be made to fail between two writes).
 */
class ImportSecretsPartialTest {

    private static final String AGENT = "0123456789abcdef0123456789abcdef";
    private static final String ADMIN = "fedcba9876543210fedcba9876543210";
    private static final SecretName MCP = new SecretName("ledger.mcp.token");
    private static final SecretName AGENT_COPY = new SecretName("ledger.admin.agent-token");
    private static final SecretName ADMIN_TOKEN = new SecretName("ledger.admin.token");

    private static ImportSecrets1xCommand command(SecretVault store, String environment, RecordingOutput output) {
        return new ImportSecrets1xCommand(store, new StandardInput(new ByteArrayInputStream(
                environment.getBytes(StandardCharsets.UTF_8))), output, mock(CliMessages.class));
    }

    private static final String ENVIRONMENT = "DBHUB_AUTH_TOKEN=" + AGENT + "\nPGLEDGER_ADMIN_TOKEN=" + ADMIN + "\n";

    @Test
    @DisplayName("M1X-025 / LGN-015 a value that cannot be used (a short token of the agents) is refused before the store is asked to write anything; the admin token of 1.x is not looked at (equal to the agent token, it is no refusal and the store is never asked about ledger.admin.token)")
    void nothingIsWrittenBeforeEveryValueIsChecked() {
        SecretVault store = mock(SecretVault.class);
        String shortAgent = "DBHUB_AUTH_TOKEN=short-token\nPGLEDGER_ADMIN_TOKEN=" + ADMIN + "\n";

        assertThatThrownBy(() -> command(store, shortAgent, new RecordingOutput()).run(List.of(),
                new DefaultApplicationArguments("--stdin", "--replace"))).isInstanceOf(FailureException.class)
                .extracting(failure -> ((FailureException) failure).code()).isEqualTo(MigrationError.INVALID_1X_VALUE);
        verify(store, never()).store(any(), any());

        SecretVault equalStore = mock(SecretVault.class);
        String equal = "DBHUB_AUTH_TOKEN=" + AGENT + "\nPGLEDGER_ADMIN_TOKEN=" + AGENT + "\n";
        command(equalStore, equal, new RecordingOutput()).run(List.of(), new DefaultApplicationArguments("--stdin", "--replace"));
        verify(equalStore, never()).exists(eq(ADMIN_TOKEN));
        verify(equalStore, never()).store(eq(ADMIN_TOKEN), any());
        verify(equalStore, times(1)).store(eq(MCP), any());
        verify(equalStore, times(1)).store(eq(AGENT_COPY), any());
    }

    @Test
    @DisplayName("M1X-025 a secret path that cannot be used is refused before the first write: nothing is stored")
    void aPathThatCannotBeUsedIsFoundBeforeTheFirstWrite() {
        SecretVault store = mock(SecretVault.class);
        when(store.exists(any())).thenReturn(true);
        when(store.exists(AGENT_COPY)).thenThrow(new FailureException(ErrorCode.UNEXPECTED_FILE_TYPE, "/etc/x/secrets/ledger.admin.agent-token"));

        assertThatThrownBy(() -> command(store, ENVIRONMENT, new RecordingOutput()).run(List.of(),
                new DefaultApplicationArguments("--stdin", "--replace"))).isInstanceOf(FailureException.class)
                .extracting(failure -> ((FailureException) failure).code()).isEqualTo(ErrorCode.UNEXPECTED_FILE_TYPE);
        verify(store, never()).store(any(), any());
    }

    @Test
    @DisplayName("M1X-025 a write that fails after another succeeded is SECRETS_PARTIAL naming the secrets that are stored and the one that is not (the cause is kept); a failure of the first write is the failure itself")
    void aFailureAfterAWriteSaysWhatIsStored() {
        SecretVault store = mock(SecretVault.class);
        when(store.exists(any())).thenReturn(true);
        FailureException cause = new FailureException(ErrorCode.IO_FAILED, "/etc/x/secrets", "IOException");
        doThrow(cause).when(store).store(eq(AGENT_COPY), any());

        assertThatThrownBy(() -> command(store, ENVIRONMENT, new RecordingOutput()).run(List.of(),
                new DefaultApplicationArguments("--stdin", "--replace"))).isInstanceOfSatisfying(FailureException.class,
                        failure -> {
                            assertThat(failure.code()).isEqualTo(MigrationError.SECRETS_PARTIAL);
                            assertThat(failure.arguments()).containsExactly("ledger.mcp.token", "ledger.admin.agent-token");
                            assertThat(failure.getCause()).isSameAs(cause);
                        });
        verify(store, times(1)).store(eq(MCP), any());
        verify(store, never()).store(eq(ADMIN_TOKEN), any());

        SecretVault first = mock(SecretVault.class);
        when(first.exists(any())).thenReturn(true);
        doThrow(cause).when(first).store(eq(MCP), any());
        assertThatThrownBy(() -> command(first, ENVIRONMENT, new RecordingOutput()).run(List.of(),
                new DefaultApplicationArguments("--stdin", "--replace"))).isSameAs(cause);
    }
}
