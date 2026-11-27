// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.hookclient;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** SEC-SYS-09: light hook client enforces a bounded stdin cap and exits with code 2 before calling the resident. */
class ClientCommandStdinLimitTest {

    @Test
    @DisplayName("SEC-SYS-09: oversized stdin exits with code 2 without invoking the resident hook")
    void oversizedStdinFailsClosedWithExit2() {
        ClientCommand command = new ClientCommand(null, 64);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        int exit = command.run(new String[] {"codex"},
                new ByteArrayInputStream(new byte[65]),
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));

        assertThat(exit).isEqualTo(2);
        assertThat(stdout.size()).isZero();
        assertThat(stderr.toString(StandardCharsets.UTF_8)).contains("hook I/O failure");
    }
}
