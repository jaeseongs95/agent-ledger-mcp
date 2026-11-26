// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.domain.rule;

/** Whether a deliverable row was recorded natively by this ledger or imported from another ledger. */
public enum DeliverableOrigin {
    NATIVE, IMPORTED
}
