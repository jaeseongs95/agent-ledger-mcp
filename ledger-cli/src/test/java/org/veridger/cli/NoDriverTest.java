// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.DisplayName;
import org.veridger.cli.database.CliLedger;
import org.veridger.cli.support.CliExtension;
import org.veridger.cli.support.CliExtension.Cli;
import org.veridger.cli.support.CliExtension.Outcome;
import org.veridger.cli.support.RecordingOutput;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * CLI-015 (DRV-012 for the CLI): without a registered JDBC driver every command that needs the ledger database fails fast with
 * DRIVER_NOT_CONFIGURED (exit 1), the message names {@code driver-register}, and no database bean is created, so there is
 * no connection attempt and no vendor stack trace. {@code driver-register} and {@code secret-set} work without a driver
 * (CLI-014, CLI-028); {@code mcp-check} talks to the server over HTTP and needs no database either (McpCheckTest).
 */
@ExtendWith({ OutputCaptureExtension.class, CliExtension.class })
class NoDriverTest {

    @TempDir
    Path tmp;

    static Stream<Arguments> commandsThatNeedTheDatabase() {
        return Stream.of(
                Arguments.of((Object) new String[] { "migrate" }),
                // SET-018: the first setup uses the database like migrate (the seed is read first and needs no driver)
                Arguments.of((Object) new String[] { "setup" }),
                Arguments.of((Object) new String[] { "project-add", "e2e", "E2E", "End-to-end test project" }),
                Arguments.of((Object) new String[] { "code-set", "ISSUE_CATEGORY", "BUG", "ko-name" }),
                Arguments.of((Object) new String[] { "codes" }),
                Arguments.of((Object) new String[] { "grant", "claude:e2e-author", "e2e", "issue" }),
                Arguments.of((Object) new String[] { "revoke", "claude:e2e-author" }),
                Arguments.of((Object) new String[] { "grants" }),
                // ATA-008, ATA-022, ATA-031: the commands of the attachments need the ledger database as well
                Arguments.of((Object) new String[] { "attachments-check" }),
                Arguments.of((Object) new String[] { "attachments-check", "--purge-orphans" }),
                Arguments.of((Object) new String[] { "attachments-rekey", "--from=key-a" }),
                Arguments.of((Object) new String[] { "attachments-key-create" }));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("commandsThatNeedTheDatabase")
    @DisplayName("CLI-015 without a registered driver the command is DRIVER_NOT_CONFIGURED (exit 1), names driver-register and creates no database bean")
    void failsFast(String[] line, Cli cli, CapturedOutput captured) {
        Outcome result = cli.run(tmp.resolve("secrets"), null, new RecordingOutput(), line);

        assertThat(result.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
        assertThat(result.code()).contains(ErrorCode.DRIVER_NOT_CONFIGURED);
        assertThat(captured.getAll()).contains("driver-register").doesNotContain("Hikari");
        // the components of the database configuration are imported (their names are class names), so ask by type
        assertThat(result.context().getBeanFactory().getBeanNamesForType(CliLedger.class)).isEmpty();
        assertThat(result.context().containsBean("dataSource")).isFalse();
    }
}
