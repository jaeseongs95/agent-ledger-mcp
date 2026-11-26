// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application.support;

import org.veridger.application.write.TransitionCommand;
import org.veridger.application.write.TransitionService;
import org.veridger.application.write.WriteResult.TransitionResult;

/** Merges an issue into another issue through the author channel. */
public final class MergeFixture {

    private MergeFixture() {
    }

    public static TransitionResult merge(Scenario s, String source, long version, String target) {
        return s.bean(TransitionService.class).transition(Scenario.AUTHOR, new TransitionCommand(Scenario.uuid(),
                "issue", source, version, "MERGED", Scenario.REASON, null, null, target));
    }
}
