// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.application.support;

import org.agentledger.application.write.TransitionCommand;
import org.agentledger.application.write.TransitionService;
import org.agentledger.application.write.WriteResult.TransitionResult;

/** Merges an issue into another issue through the author channel. */
public final class MergeFixture {

    private MergeFixture() {
    }

    public static TransitionResult merge(Scenario s, String source, long version, String target) {
        return s.bean(TransitionService.class).transition(Scenario.AUTHOR, new TransitionCommand(Scenario.uuid(),
                "issue", source, version, "MERGED", Scenario.REASON, null, null, target));
    }
}
