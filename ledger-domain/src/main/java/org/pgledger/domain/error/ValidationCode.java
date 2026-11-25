// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.domain.error;

/** Error tokens of category VALIDATION. */
public enum ValidationCode implements ErrorCode {
    VALIDATION_FAILED,
    REASON_INVALID,
    CODE_REQUIRED,
    CODE_INVALID,
    ID_PREFIX_MISMATCH,
    /** An explicit issue, spec or task id whose number is above the next number of the project. Arguments: the id, the next id. */
    ID_NUMBER_AHEAD,
    /** An upload purpose that the ledger does not support (it supports only evidence). Argument: the purpose. */
    ARTIFACT_UNSUPPORTED_PURPOSE;

    @Override
    public ErrorCategory category() {
        return ErrorCategory.VALIDATION;
    }
}
