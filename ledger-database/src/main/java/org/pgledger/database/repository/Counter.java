// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.database.repository;

/**
 * The rows of {@code id_counter}: the surrogate keys that need a sequence of numbers (no IDENTITY, no sequence).
 *
 * <p>The row of a counter is locked by the UPDATE that takes a number and stays locked until the transaction ends, so the order in
 * which one transaction takes numbers is a lock order. Numbers are taken after the project, entity and grant rows of the use case
 * (the order of {@code LockOrder}), and every use case that takes more than one number takes them in this one order:
 * {@code DELIVERABLE}, {@code ISSUE_CASE}, {@code HISTORY} (the writes and the batch), and {@code SESSION_GRANT},
 * {@code SESSION_KEY}, {@code SESSION_TOKEN}, {@code OWNER_AUDIT} (the owner's operations; the grant screen with a key issues the
 * grants, binds the key, then writes its audit rows). The audit row of the owner is always the last number of its transaction.
 *
 * <p>The one exception is the owner's operations on the passkeys ({@code PasskeyService}): they take no other number, so their
 * audit row, taken first, is also their last, and it is the lock that serializes them.
 */
public enum Counter {
    HISTORY, DELIVERABLE, ISSUE_CASE, SESSION_GRANT, SESSION_KEY, SESSION_TOKEN, OWNER_AUDIT
}
