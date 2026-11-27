// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.hook;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** SEC-SYS-09: HookCli enforces a bounded stdin cap and exits with code 2 instead of exhausting heap. */
class HookCliStdinLimitTest {

    @Test
    @DisplayName("SEC-SYS-09: oversized stdin fails closed with exit code 2 before invoking LedgerHook")
    void oversizedStdinFailsClosedWithExit2() {
        HookCli cli = new HookCli(null, null, 64);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exit = cli.run(new String[] {"codex"}, new ByteArrayInputStream(new byte[65]), out, err);

        assertThat(exit).isEqualTo(2);
        assertThat(out.size()).isZero();
    }
}
