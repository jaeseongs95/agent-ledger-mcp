// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.database.repository;

/** The rows of {@code id_counter}: the surrogate keys that need a sequence of numbers (no IDENTITY, no sequence). */
public enum Counter {
    HISTORY, DELIVERABLE, ISSUE_CASE, SESSION_GRANT, SESSION_KEY, SESSION_TOKEN, OWNER_AUDIT
}
