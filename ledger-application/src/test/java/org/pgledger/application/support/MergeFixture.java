package org.pgledger.application.support;

import org.pgledger.application.write.TransitionCommand;
import org.pgledger.application.write.TransitionService;
import org.pgledger.application.write.WriteResult.TransitionResult;

/** Merges an issue into another issue through the author channel. */
public final class MergeFixture {

    private MergeFixture() {
    }

    public static TransitionResult merge(Scenario s, String source, long version, String target) {
        return s.bean(TransitionService.class).transition(Scenario.AUTHOR, new TransitionCommand(Scenario.uuid(),
                "issue", source, version, "MERGED", Scenario.REASON, null, null, target));
    }
}
