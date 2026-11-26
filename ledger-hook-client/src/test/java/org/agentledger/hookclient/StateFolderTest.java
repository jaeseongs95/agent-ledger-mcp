// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.hookclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R9-D0096-F (state folder): one resident program per key folder, found from the home folder and the key folder text. */
class StateFolderTest {

    @TempDir
    Path home;

    @Test
    @DisplayName("R9-D0096-F the state folder is under <home>/.agent-ledger-mcp/resident, named by 16 hex digits of the key folder text; the same text gives the same folder, another text another one, no variable the same as an empty one")
    void oneFolderPerKeyFolder() {
        StateFolder first = StateFolder.locate(home.toString(), "D:\\keys\\a");
        assertThat(first.directory().getParent()).isEqualTo(home.resolve(".agent-ledger-mcp").resolve("resident"));
        assertThat(first.directory().getFileName().toString()).matches("[0-9a-f]{16}");
        assertThat(StateFolder.locate(home.toString(), "D:\\keys\\a")).isEqualTo(first);
        assertThat(StateFolder.locate(home.toString(), "D:\\keys\\b")).isNotEqualTo(first);
        assertThat(StateFolder.locate(home.toString(), null)).isEqualTo(StateFolder.locate(home.toString(), ""));
        // a relative or unusable key folder text names a folder too: the resident program answers it with the refusal of the key store
        assertThat(StateFolder.locate(home.toString(), "keys").directory().getFileName().toString()).matches("[0-9a-f]{16}");
    }

    @Test
    @DisplayName("R9-D0096-F the files of the state folder are fixed names inside it")
    void namesInsideTheFolder() {
        StateFolder state = StateFolder.locate(home.toString(), null);
        assertThat(state.addressFile().getParent()).isEqualTo(state.directory());
        assertThat(state.startupLock().getParent()).isEqualTo(state.directory());
        assertThat(state.instanceLock().getParent()).isEqualTo(state.directory());
        assertThat(state.logFile().getParent()).isEqualTo(state.directory());
        assertThat(java.util.Set.of(state.addressFile(), state.startupLock(), state.instanceLock(), state.logFile())).hasSize(4);
    }

    @Test
    @DisplayName("R9-D0096-F a missing, blank or relative home folder is refused with a message (no folder is guessed relative to the working directory)")
    void refusesAnUnusableHome() {
        assertThatThrownBy(() -> StateFolder.locate(null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StateFolder.locate(" ", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StateFolder.locate("home", null)).isInstanceOf(IllegalArgumentException.class);
    }
}
