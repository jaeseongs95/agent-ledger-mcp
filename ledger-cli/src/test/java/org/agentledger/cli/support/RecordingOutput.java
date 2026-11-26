// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.cli.support;

import java.util.ArrayList;
import java.util.List;

import org.agentledger.cli.CliOutput;

/** Test double for the single console output target. */
public final class RecordingOutput implements CliOutput {

    private final List<String> info = new ArrayList<>();

    @Override
    public void info(String text) {
        info.add(text);
    }

    public String info() {
        return String.join("\n", info);
    }
}
