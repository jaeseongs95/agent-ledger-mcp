package org.pgledger.domain.rule;

import org.pgledger.domain.value.Token;

/**
 * How the two ends of a relation are kept, a property of the code of the relation kind (a RELATION_KIND common code), so
 * that a kind added later declares it too. Every other code group has no relation and is {@link #CANONICAL}.
 */
public enum CodeDirection implements Token {
    /**
     * The relation has a direction: the ends stay in the order the caller gave them, {@code a} is the subject and
     * {@code b} the object (for {@code caused_by}: a is caused by b). (a, b) and (b, a) are two different relations.
     */
    GIVEN,
    /**
     * The relation has no direction of its own (or is defined by the order of the ids): the ends are kept in canonical
     * order, so (a, b) and (b, a) are one relation.
     */
    CANONICAL;

    @Override
    public String token() {
        return name();
    }
}
