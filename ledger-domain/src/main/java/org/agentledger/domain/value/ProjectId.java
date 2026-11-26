// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.domain.value;

import java.util.regex.Pattern;
import org.agentledger.domain.error.ValidationException;
import org.agentledger.domain.error.ValidationReason;

/** Primary key of a project (the {@code project_id} of the tool contract); not the same as its code. */
public record ProjectId(String value) {

    /** The format the constructor enforces (an import checks it without throwing). */
    public static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    public ProjectId {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw ValidationException.invalid("project_id", ValidationReason.MUST_MATCH, FORMAT.pattern());
        }
    }

    public static ProjectId of(String value) {
        return new ProjectId(value);
    }
}
