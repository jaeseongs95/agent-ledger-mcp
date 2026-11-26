// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.domain.error;

import java.util.List;
import java.util.Map;

/** Failure of category {@link ErrorCategory#LOOKUP}. */
public final class LookupException extends LedgerException {

    private static final long serialVersionUID = 1L;

    public LookupException(LookupCode code, List<String> args) {
        this(code, args, Map.of());
    }

    private LookupException(LookupCode code, List<String> args, Map<String, List<String>> details) {
        super(code, args, details, null);
    }

    @Override
    protected LedgerException copyWith(Map<String, List<String>> newDetails) {
        return new LookupException((LookupCode) code(), args(), newDetails);
    }
}
