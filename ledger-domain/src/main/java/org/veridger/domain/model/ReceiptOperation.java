// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.domain.model;

/** The operation a receipt was issued for; an operation key may be reused only for the same operation. */
public enum ReceiptOperation {
    SAVE, TRANSITION, RECORD_DELIVERABLE, RECORD_CASE, LINK, CORRECT, BATCH
}
