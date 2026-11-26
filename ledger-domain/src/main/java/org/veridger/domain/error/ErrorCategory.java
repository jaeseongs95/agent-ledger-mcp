// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.domain.error;

/** Groups of error codes; one category per sealed {@link LedgerException} subtype. */
public enum ErrorCategory {
    TRUST, VALIDATION, TRANSITION, CONFLICT, LOOKUP, ADMIN, INFRASTRUCTURE
}
