// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.domain.value;

/**
 * Who acted. A declared author ({@link AuthorId}) is what every new write carries; rows imported from another
 * ledger may hold an actor id that does not follow the author format ({@link ImportedActorId}).
 */
public sealed interface ActorId permits AuthorId, ImportedActorId {

    String value();

    default SessionKey sessionKey() {
        return new SessionKey(value());
    }

    /** Reads a stored actor column: the author format becomes an {@link AuthorId}, anything else an {@link ImportedActorId}. */
    static ActorId ofStored(String stored) {
        return AuthorId.matches(stored) ? new AuthorId(stored) : new ImportedActorId(stored);
    }
}
