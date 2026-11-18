// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.migrate1x.service;

/** Why {@link LegacyRetireService} refuses to list or to remove the objects of the 1.x schema. */
public enum RetireRefusal {

    /** The legacy schema holds the tables of 2.0 itself (a {@code schema_history}): dropping it would drop the ledger. */
    OWN_SCHEMA,
    /** Another session runs as a 1.x role or holds a lock in the legacy schema: the 1.x service is not stopped. Arguments: the sessions. */
    SESSIONS_CONNECTED,
    /** The account that runs the command does not own objects of the legacy schema. Arguments: their names. */
    NOT_OWNER,
    /** The list read now is not the list that was confirmed: the legacy database changed. Argument: the digest of the list now. */
    SOURCE_CHANGED
}
