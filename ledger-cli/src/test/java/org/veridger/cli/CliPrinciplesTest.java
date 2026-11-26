// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.veridger.cli.support.CliExtension;
import org.veridger.cli.support.CliExtension.Cli;
import org.veridger.cli.support.CliExtension.Outcome;
import org.veridger.cli.support.RecordingOutput;
import org.veridger.database.LedgerDatabaseConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Import;

/**
 * CLI-PRN-09 and CLI-PRN-10: the clock of the tool and the cause of an option that is no number.
 */
@ExtendWith(CliExtension.class)
class CliPrinciplesTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("CLI-PRN-09 the clock configuration of the tool has no condition and makes exactly one Clock, in UTC; the ledger database configuration does not make a second one")
    void oneUtcClock() {
        new ApplicationContextRunner().withUserConfiguration(CliClockConfiguration.class).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(Clock.class);
            assertThat(context.getBean(Clock.class).getZone()).isEqualTo(ZoneOffset.UTC);
        });
        assertThat(org.veridger.cli.database.CliLedgerConfiguration.class.getAnnotation(Import.class).value())
                .as("the database configuration of the tool does not import the clock of the database module")
                .doesNotContain(LedgerDatabaseConfiguration.class);
    }

    @Test
    @DisplayName("CLI-PRN-10 code-set --sort=abc is INVALID_OPTION_VALUE (exit 2), the number format failure is the cause of the refusal, and the value is in no argument of it")
    void aSortThatIsNoNumberKeepsItsCause(Cli cli) {
        Outcome result = cli.run(tmp.resolve("secrets"), null, new RecordingOutput(), "code-set", "ISSUE_CATEGORY", "BUG",
                "ko-name", "--sort=abc-4711");

        assertThat(result.exitCode()).isEqualTo(ExitStatus.USAGE.code());
        CliException refusal = result.refusal().orElseThrow();
        assertThat(refusal.code()).isEqualTo(ErrorCode.INVALID_OPTION_VALUE);
        assertThat(refusal.getCause()).isInstanceOf(NumberFormatException.class);
        assertThat(refusal.arguments()).isEmpty();
        assertThat(refusal.getMessage()).doesNotContain("4711");
    }
}
