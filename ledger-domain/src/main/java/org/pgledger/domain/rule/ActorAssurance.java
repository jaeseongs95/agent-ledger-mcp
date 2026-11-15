package org.pgledger.domain.rule;

/** How well the actor of a history entry is known: DECLARED for every new write, IMPORTED_* for imported rows. */
public enum ActorAssurance {
    DECLARED, IMPORTED_LOGIN, IMPORTED_SHARED, IMPORTED_UNKNOWN
}
