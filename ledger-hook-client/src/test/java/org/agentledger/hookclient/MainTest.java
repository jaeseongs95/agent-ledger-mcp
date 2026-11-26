// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.hookclient;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URISyntaxException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.agentledger.files.ProcessAccount;

/** R9-D0095 c: {@link Main#environment()} carries the process account from {@link ProcessAccount}, not {@code user.name}. */
class MainTest {

    @Test
    @DisplayName("R9-D0095 c: Main.environment().userName() uses ProcessAccount.name() (including Windows domain prefix), not bare user.name")
    void environmentUsesProcessAccountName() throws URISyntaxException {
        assertThat(Main.environment().userName()).isEqualTo(ProcessAccount.name().orElse(""));
    }
}
