// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.application.write;

/** Outcome of a write; every result of this ledger is COMMITTED (a failure is an exception). */
public enum WriteStatus {
    COMMITTED
}
