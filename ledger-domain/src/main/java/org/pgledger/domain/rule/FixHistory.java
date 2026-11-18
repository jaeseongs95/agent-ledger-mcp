package org.pgledger.domain.rule;

import java.util.List;
import org.pgledger.domain.value.SessionKey;

/**
 * The sessions that took part in one defect (facts for the transition policy): the sessions that recorded a fix commit and the
 * sessions that reported it, that is the one that registered the defect and the ones that recorded a case of it. A defect is
 * verified by a session that is none of them.
 *
 * @param fixers    the sessions of the recorded fixes, one entry for each fix
 * @param reporters the session that registered the defect (none when the row has none) and the sessions of its cases
 */
public record FixHistory(List<SessionKey> fixers, List<SessionKey> reporters) {

    public static final FixHistory NONE = new FixHistory(List.of(), List.of());

    public FixHistory {
        fixers = List.copyOf(fixers);
        reporters = List.copyOf(reporters);
    }

    public boolean hasFix() {
        return !fixers.isEmpty();
    }

    /** True when {@code actor} recorded a fix, registered the defect or recorded a case of it: it cannot verify the defect. */
    public boolean involves(SessionKey actor) {
        return fixers.contains(actor) || reporters.contains(actor);
    }
}
