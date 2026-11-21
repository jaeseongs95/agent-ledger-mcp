// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.application.gate;

import java.util.List;
import java.util.Optional;
import org.pgledger.domain.value.AuthorId;

/**
 * What the gate asks once it knows the author: did this author already get an answer to the operation key of the call,
 * and which targets did the call that was answered need. A replay returns what was stored, whatever has changed since
 * in the entities it touched (their state, their version), so its grants are those of the call that was answered and not
 * those that the state of the entities needs now (a verification needs {@code verify} and the reviewer channel, and an
 * entity that has been verified since makes the same request look like one). The grants must still be live: a replay
 * does not read a stored response for an author who lost them.
 */
@FunctionalInterface
public interface AnsweredCall {

    /**
     * @param author the proven author of the call
     * @param now    the targets that the call needs on the entities as they are now
     * @return the targets of the call that was answered, or none when the author has no answer to the key
     */
    Optional<List<Target>> targets(AuthorId author, List<Target> now);
}
